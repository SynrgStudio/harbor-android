package com.synrg.harbor.tmdb

import com.synrg.harbor.R
import com.synrg.harbor.core.HttpException
import com.synrg.harbor.core.Strings
import com.synrg.harbor.core.await
import com.synrg.harbor.data.CastMember
import com.synrg.harbor.data.Details
import com.synrg.harbor.data.Episode
import com.synrg.harbor.data.MediaItem
import com.synrg.harbor.data.MediaType
import com.synrg.harbor.data.SeasonInfo
import com.synrg.harbor.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * TMDB data without a key of our own: the community "TMDB addon" for Stremio asks TMDB with its
 * key and answers in the addon protocol, in the language we pass in its config. Same numeric ids
 * and image.tmdb.org images as the direct API, so saved progress and lists work with either.
 */
class TmdbAddon(
    private val http: OkHttpClient,
    private val json: Json,
    private val settings: SettingsRepository,
) {
    private val metaCache = ConcurrentHashMap<String, AddonMeta>()

    private fun url(vararg segments: String, extra: Pair<String, String>? = null): HttpUrl = BASE_URL.toHttpUrl().newBuilder().apply {
        addPathSegment("""{"language":"${settings.current.language}"}""")
        segments.forEach(::addPathSegment)
        // The server reads "key=value" like a query string, so the value is fully escaped the way
        // Stremio does it: a raw "&" (as in "Sci-Fi & Fantasy") would cut the genre in two.
        if (extra != null) addEncodedPathSegment("${escape(extra.first)}=${escape(extra.second)}.json")
    }.build()

    private fun escape(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private suspend fun <T> get(url: HttpUrl, strategy: DeserializationStrategy<T>): T {
        val request = Request.Builder().url(url).apply {
            if (currentCoroutineContext()[CacheOnly.Key] != null) cacheControl(STALE_OK)
        }.build()
        val response = http.newCall(request).await()
        return withContext(Dispatchers.Default) {
            response.use {
                if (!it.isSuccessful) throw HttpException(it.code, Strings.get(R.string.meta_error_server, it.code))
                json.decodeFromString(strategy, it.body.string())
            }
        }
    }

    /** A catalog page; [extra] is one Stremio extra such as genre=Comedia. */
    suspend fun catalog(type: MediaType, id: String, extra: Pair<String, String>? = null): List<MediaItem> {
        val url = if (extra == null) url("catalog", type.stremio, "$id.json") else url("catalog", type.stremio, id, extra = extra)
        return get(url, AddonCatalog.serializer()).metas.mapNotNull { it.toMediaItem(type) }
    }

    suspend fun trending(): List<MediaItem> = coroutineScope {
        val movies = async { catalog(MediaType.Movie, "tmdb.trending", "genre" to "Week") }
        val series = async { catalog(MediaType.Tv, "tmdb.trending", "genre" to "Week") }
        interleave(movies.await(), series.await())
    }

    suspend fun search(query: String): List<MediaItem> = coroutineScope {
        val movies = async { runCatching { catalog(MediaType.Movie, "tmdb.search", "search" to query) }.getOrDefault(emptyList()) }
        val series = async { runCatching { catalog(MediaType.Tv, "tmdb.search", "search" to query) }.getOrDefault(emptyList()) }
        val q = query.trim().lowercase()
        // Each list comes ranked by TMDB; titles that match what was typed go first.
        interleave(movies.await(), series.await()).sortedBy {
            val t = it.title.lowercase()
            when {
                t == q -> 0
                t.startsWith(q) -> 1
                q in t -> 2
                else -> 3
            }
        }
    }

    /**
     * A person's titles without a key: the addon's search finds the movies and series a name
     * worked on. Titles *about* them (documentaries named after them) and talk, news or reality
     * appearances are left out.
     */
    suspend fun personCredits(name: String): List<MediaItem> = coroutineScope {
        val movies = async { runCatching { catalog(MediaType.Movie, "tmdb.search", "search" to name) }.getOrDefault(emptyList()) }
        val series = async { runCatching { catalog(MediaType.Tv, "tmdb.search", "search" to name) }.getOrDefault(emptyList()) }
        val lower = name.lowercase()
        (movies.await() + series.await()).filter { item ->
            item.posterPath != null && item.year != null && lower !in item.title.lowercase() &&
                item.genres.none { it in APPEARANCE_GENRES }
        }.distinctBy { "${it.type}:${it.id}" }
    }

    suspend fun meta(type: MediaType, id: Int): AddonMeta {
        val key = "${settings.current.language}:${type.stremio}:$id"
        metaCache[key]?.let { return it }
        val meta = get(url("meta", type.stremio, "tmdb:$id.json"), AddonMetaResponse.serializer()).meta
            ?: throw IOException(Strings.get(R.string.meta_error_not_found))
        // Only network answers are kept in memory, so a stale cached copy gets refreshed later.
        if (currentCoroutineContext()[CacheOnly.Key] == null) metaCache[key] = meta
        return meta
    }

    /** The title behind an IMDb id (Stremio's library uses IMDb ids); its episodes stay cached for [season]. */
    suspend fun itemByImdb(type: MediaType, imdbId: String): MediaItem? {
        val meta = get(url("meta", type.stremio, "$imdbId.json"), AddonMetaResponse.serializer()).meta ?: return null
        val item = meta.toMediaItem(type) ?: return null
        metaCache["${settings.current.language}:${type.stremio}:${item.id}"] = meta
        return item
    }

    suspend fun logo(type: MediaType, id: Int): String? = tmdbPath(meta(type, id).logo)

    suspend fun details(type: MediaType, id: Int): Details {
        val m = meta(type, id)
        val item = m.toMediaItem(type) ?: throw IOException(Strings.get(R.string.meta_error_incomplete))
        val seasons = m.episodes().filter { it.season > 0 }.groupBy { it.season }.toSortedMap()
            .map { (n, eps) -> SeasonInfo(n, Strings.get(R.string.common_season_n, n), eps.size) }
        return Details(
            item = item,
            tagline = null,
            genres = m.genreList,
            runtime = parseRuntime(m.runtime),
            seasons = seasons,
            cast = m.extras?.cast.orEmpty().take(20).mapIndexed { i, c ->
                CastMember(i, c.name, c.character?.takeIf(String::isNotBlank), tmdbPath(c.photo))
            },
            recommendations = emptyList(),
            imdbId = m.imdbId?.takeIf { it.startsWith("tt") },
            logoPath = tmdbPath(m.logo),
            status = m.status,
            certification = m.ageRating?.takeIf(String::isNotBlank),
        )
    }

    suspend fun season(tvId: Int, number: Int): List<Episode> =
        meta(MediaType.Tv, tvId).episodes().filter { it.season == number }.sortedBy { it.number }

    /** "More like this": the most popular titles of the same main genre (the addon has no recommendations). */
    suspend fun similar(details: Details): List<MediaItem> {
        val item = details.item
        val genre = details.genres.firstOrNull() ?: return emptyList()
        return catalog(item.type, "tmdb.top", "genre" to genre).filter { it.id != item.id && it.posterPath != null }
    }

    private fun AddonMeta.episodes(): List<Episode> = videos.mapNotNull { v ->
        val season = v.season ?: return@mapNotNull null
        val number = v.episode ?: v.number ?: return@mapNotNull null
        Episode(
            season = season,
            number = number,
            name = v.name?.takeIf(String::isNotBlank) ?: v.title?.takeIf(String::isNotBlank) ?: Strings.get(R.string.common_episode_n, number),
            overview = v.overview ?: v.description ?: "",
            stillPath = tmdbPath(v.thumbnail),
            runtime = parseRuntime(v.runtime),
            airDate = (v.released ?: v.firstAired)?.take(10),
        )
    }

    companion object {
        const val HOST = "tmdb.elfhosted.com"
        private const val BASE_URL = "https://$HOST/"
        private val STALE_OK = CacheControl.Builder().onlyIfCached().maxStale(30, TimeUnit.DAYS).build()
        /** Genre names (as the addon spells them in each language) of shows people appear in as themselves. */
        private val APPEARANCE_GENRES = setOf("Talk", "News", "Noticias", "Notícias", "Reality", "Documentary", "Documental", "Documentário")
        private val TMDB_IMAGE = Regex("""^https?://image\.tmdb\.org/t/p/[^/]+(/.+)$""")
        private val RUNTIME = Regex("""(?:(\d+)\s*h)?\s*(?:(\d+)\s*min)?""")

        /** image.tmdb.org URLs become TMDB paths, so the UI can pick the size; other URLs stay whole. */
        fun tmdbPath(url: String?): String? {
            if (url.isNullOrBlank()) return null
            return TMDB_IMAGE.find(url)?.groupValues?.get(1) ?: url
        }

        /** "2h19min", "56min" or "1h" in minutes. */
        fun parseRuntime(text: String?): Int? {
            val m = RUNTIME.matchEntire(text?.trim() ?: return null) ?: return null
            val minutes = (m.groupValues[1].toIntOrNull() ?: 0) * 60 + (m.groupValues[2].toIntOrNull() ?: 0)
            return minutes.takeIf { it > 0 }
        }

        private fun <T> interleave(a: List<T>, b: List<T>): List<T> = buildList {
            for (i in 0 until maxOf(a.size, b.size)) {
                a.getOrNull(i)?.let(::add)
                b.getOrNull(i)?.let(::add)
            }
        }
    }
}

@Serializable
data class AddonCatalog(val metas: List<AddonMeta> = emptyList())

@Serializable
data class AddonMetaResponse(val meta: AddonMeta? = null)

@Serializable
data class AddonMeta(
    val id: String,
    val name: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val year: String? = null,
    val imdbRating: String? = null,
    val genre: List<String>? = null,
    val genres: List<String>? = null,
    val runtime: String? = null,
    val status: String? = null,
    val ageRating: String? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    val videos: List<AddonVideo> = emptyList(),
    @SerialName("app_extras") val extras: AddonExtras? = null,
) {
    val genreList: List<String> get() = genres ?: genre.orEmpty()

    fun toMediaItem(type: MediaType): MediaItem? {
        val tmdbId = id.removePrefix("tmdb:").toIntOrNull() ?: return null
        return MediaItem(
            id = tmdbId,
            type = type,
            title = name?.takeIf(String::isNotBlank) ?: return null,
            posterPath = TmdbAddon.tmdbPath(poster),
            backdropPath = TmdbAddon.tmdbPath(background),
            overview = description.orEmpty(),
            year = (releaseInfo ?: year)?.take(4)?.takeIf { it.isNotBlank() },
            rating = imdbRating?.toDoubleOrNull() ?: 0.0,
            genres = genreList,
        )
    }
}

@Serializable
data class AddonVideo(
    val id: String? = null,
    val name: String? = null,
    val title: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val number: Int? = null,
    val thumbnail: String? = null,
    val overview: String? = null,
    val description: String? = null,
    val runtime: String? = null,
    val released: String? = null,
    val firstAired: String? = null,
)

@Serializable
data class AddonExtras(val cast: List<AddonCast> = emptyList())

@Serializable
data class AddonCast(val name: String, val character: String? = null, val photo: String? = null)
