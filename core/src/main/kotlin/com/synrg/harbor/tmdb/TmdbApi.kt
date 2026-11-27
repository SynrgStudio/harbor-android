package com.synrg.harbor.tmdb

import com.synrg.harbor.core.CoreStrings
import com.synrg.harbor.core.CoreText
import com.synrg.harbor.core.HttpException
import com.synrg.harbor.core.await
import com.synrg.harbor.data.CastMember
import com.synrg.harbor.data.Details
import com.synrg.harbor.data.Episode
import com.synrg.harbor.data.MediaItem
import com.synrg.harbor.data.MediaType
import com.synrg.harbor.data.Person
import com.synrg.harbor.data.Saga
import com.synrg.harbor.data.SeasonInfo
import com.synrg.harbor.data.SettingsRepository
import com.synrg.harbor.data.WatchProvider
import com.synrg.harbor.data.WatchProviders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.CacheControl
import java.util.concurrent.TimeUnit
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Coroutine context marker: requests made under it are answered only from the HTTP disk cache,
 * even if stale, and fail fast otherwise. Used to paint screens instantly before refreshing.
 */
object CacheOnly : AbstractCoroutineContextElement(CacheOnly.Key) {
    // A separate key object: using CacheOnly itself as the key reads it before it exists.
    object Key : CoroutineContext.Key<CacheOnly>
}

private val STALE_OK = CacheControl.Builder().onlyIfCached().maxStale(30, TimeUnit.DAYS).build()

/** TMDB's own API, used when the user saved a personal key in Settings. */
class TmdbApi(
    private val http: OkHttpClient,
    private val json: Json,
    private val settings: SettingsRepository,
) {
    private val detailsCache = ConcurrentHashMap<String, Details>()
    private val seasonCache = ConcurrentHashMap<String, List<Episode>>()
    private val genreMutex = Mutex()
    private var genres: Map<Int, String>? = null
    private var genresLanguage: String? = null

    private suspend fun <T> get(path: String, strategy: DeserializationStrategy<T>, vararg params: Pair<String, String?>): T {
        val s = settings.current
        val key = s.tmdbKey
        val bearer = key.startsWith("eyJ")
        val url = "https://api.themoviedb.org/3/$path".toHttpUrl().newBuilder().apply {
            if (params.none { it.first == "language" }) addQueryParameter("language", s.language)
            params.forEach { (k, v) -> if (v != null) addQueryParameter(k, v) }
            if (!bearer) addQueryParameter("api_key", key)
        }.build()
        val request = Request.Builder().url(url).apply {
            if (bearer) header("Authorization", "Bearer $key")
            if (currentCoroutineContext()[CacheOnly.Key] != null) cacheControl(STALE_OK)
        }.build()
        val response = http.newCall(request).await()
        return withContext(Dispatchers.Default) {
            response.use {
                if (!it.isSuccessful) throw HttpException(it.code, CoreStrings.get(CoreText.MetaErrorTmdb, it.code))
                json.decodeFromString(strategy, it.body.string())
            }
        }
    }

    private val imageLanguages: String
        get() = settings.current.language.substringBefore('-').let { if (it == "en") "en,null" else "$it,en,null" }

    suspend fun trending(type: String = "all", page: Int = 1): List<MediaItem> =
        get("trending/$type/week", TmdbPage.serializer(), "page" to page.toString()).results.mapNotNull { it.toMediaItem(null) }

    suspend fun list(type: MediaType, path: String, page: Int = 1, vararg extra: Pair<String, String?>): List<MediaItem> =
        get("${type.tmdb}/$path", TmdbPage.serializer(), "page" to page.toString(), "region" to settings.current.region, *extra)
            .results.mapNotNull { it.toMediaItem(type) }

    suspend fun discover(type: MediaType, vararg params: Pair<String, String?>): List<MediaItem> =
        get("discover/${type.tmdb}", TmdbPage.serializer(), *params).results.mapNotNull { it.toMediaItem(type) }

    suspend fun search(query: String, page: Int = 1): List<MediaItem> =
        get("search/multi", TmdbPage.serializer(), "query" to query, "page" to page.toString(), "include_adult" to "false")
            .results.mapNotNull { it.toMediaItem(null) }

    suspend fun logo(type: MediaType, id: Int): String? =
        get("${type.tmdb}/$id/images", TmdbImages.serializer(), "include_image_language" to imageLanguages).bestLogo()

    suspend fun genreNames(): Map<Int, String> = genreMutex.withLock {
        val lang = settings.current.language
        genres?.takeIf { genresLanguage == lang }?.let { return it }
        val movie = get("genre/movie/list", TmdbGenreList.serializer()).genres
        val tv = get("genre/tv/list", TmdbGenreList.serializer()).genres
        (movie + tv).associate { it.id to it.name }.also {
            genres = it
            genresLanguage = lang
        }
    }

    suspend fun details(type: MediaType, id: Int): Details {
        val cacheKey = "${type.tmdb}:$id:${settings.current.language}"
        detailsCache[cacheKey]?.let { return it }
        val append = if (type == MediaType.Movie) "credits,images,recommendations,external_ids,release_dates,watch/providers"
        else "credits,images,recommendations,external_ids,content_ratings,watch/providers"
        val d = get(
            "${type.tmdb}/$id", TmdbDetails.serializer(),
            "append_to_response" to append,
            "include_image_language" to imageLanguages,
        )
        val region = settings.current.region
        val certification = if (type == MediaType.Movie) {
            val byCountry = d.releaseDates?.results.orEmpty()
            (byCountry.firstOrNull { it.country == region } ?: byCountry.firstOrNull { it.country == "US" })
                ?.dates?.firstNotNullOfOrNull { it.certification?.takeIf(String::isNotBlank) }
        } else {
            val ratings = d.contentRatings?.results.orEmpty()
            (ratings.firstOrNull { it.country == region } ?: ratings.firstOrNull { it.country == "US" })?.rating?.takeIf(String::isNotBlank)
        }
        val item = MediaItem(
            id = d.id,
            type = type,
            title = d.title ?: d.name ?: "",
            posterPath = d.posterPath,
            backdropPath = d.backdropPath,
            overview = d.overview.orEmpty(),
            year = (d.releaseDate ?: d.firstAirDate)?.take(4)?.takeIf { it.isNotBlank() },
            rating = d.voteAverage,
            genreIds = d.genres.map { it.id },
        )
        val details = Details(
            item = item,
            tagline = d.tagline?.takeIf { it.isNotBlank() },
            genres = d.genres.map { it.name },
            runtime = d.runtime ?: d.episodeRunTime.firstOrNull(),
            seasons = d.seasons.filter { it.seasonNumber > 0 && it.episodeCount > 0 }
                .map { SeasonInfo(it.seasonNumber, it.name ?: CoreStrings.get(CoreText.CommonSeasonN, it.seasonNumber), it.episodeCount) },
            cast = d.credits?.cast.orEmpty().take(20).map { CastMember(it.id, it.name, it.character, it.profilePath, personId = it.id) },
            recommendations = d.recommendations?.results.orEmpty().mapNotNull { it.toMediaItem(type) }.filter { it.posterPath != null },
            imdbId = d.imdbId ?: d.externalIds?.imdbId,
            logoPath = d.images?.bestLogo(),
            status = d.status,
            certification = certification,
            collectionId = d.collection?.id,
            providers = d.watchProviders?.results?.get(region)?.toProviders(),
        )
        detailsCache[cacheKey] = details
        return details
    }

    private fun TmdbRegionProviders.toProviders(): WatchProviders {
        fun List<TmdbProvider>.mapped() = sortedBy { it.priority }.distinctBy { it.id }.map { WatchProvider(it.id, it.name, it.logoPath) }
        return WatchProviders(
            link = link,
            stream = flatrate.mapped(),
            free = (free + ads).mapped(),
            rent = rent.mapped(),
            buy = buy.mapped(),
        )
    }

    /** A movie collection ("The Matrix Collection"), parts in release order. */
    suspend fun collection(id: Int): Saga? {
        val c = get("collection/$id", TmdbCollection.serializer())
        val parts = c.parts.sortedWith(compareBy(nullsLast()) { it.releaseDate?.takeIf(String::isNotBlank) })
            .mapNotNull { it.toMediaItem(MediaType.Movie) }
        return if (parts.size < 2) null else Saga(c.name ?: return null, parts)
    }

    suspend fun person(id: Int): Person {
        val p = get("person/$id", TmdbPerson.serializer(), "append_to_response" to "combined_credits")
        // Many biographies exist only in English.
        val bio = p.biography?.takeIf(String::isNotBlank)
            ?: if (!settings.current.language.startsWith("en")) {
                runCatching { get("person/$id", TmdbPerson.serializer(), "language" to "en-US").biography }.getOrNull()
            } else null
        val directing = p.department == "Directing"
        val credits = p.credits?.cast.orEmpty().filter { c -> c.character?.let(::isSelf) != true } +
            p.credits?.crew.orEmpty().filter { directing && it.job == "Director" }
        val titles = credits
            .filter { c -> c.posterPath != null && c.genreIds.none { it in NOT_ROLES } }
            .distinctBy { "${it.mediaType}:${it.id}" }
        val known = titles.sortedByDescending { it.voteCount }.take(12).mapNotNull { it.asItem().toMediaItem(null) }
        val all = titles.sortedWith(compareByDescending(nullsFirst()) { (it.releaseDate ?: it.firstAirDate)?.takeIf(String::isNotBlank) })
            .mapNotNull { it.asItem().toMediaItem(null) }
        return Person(
            name = p.name,
            profilePath = p.profilePath,
            biography = bio?.takeIf(String::isNotBlank),
            department = p.department,
            birthday = p.birthday?.takeIf(String::isNotBlank),
            deathday = p.deathday?.takeIf(String::isNotBlank),
            birthplace = p.birthplace?.takeIf(String::isNotBlank),
            knownFor = known,
            credits = all,
        )
    }

    private companion object {
        /** Talk shows, news, reality and documentaries: appearances, not roles. */
        val NOT_ROLES = setOf(10767, 10763, 10764, 99)
        private val SELF = Regex("""^(self|himself|herself|themselves).*""", RegexOption.IGNORE_CASE)
        fun isSelf(character: String) = SELF.matches(character.trim())
    }

    suspend fun season(tvId: Int, number: Int): List<Episode> {
        val cacheKey = "$tvId:$number:${settings.current.language}"
        seasonCache[cacheKey]?.let { return it }
        val season = get("tv/$tvId/season/$number", TmdbSeason.serializer())
        val episodes = season.episodes.map {
            Episode(
                season = it.seasonNumber,
                number = it.episodeNumber,
                name = it.name?.takeIf(String::isNotBlank) ?: CoreStrings.get(CoreText.CommonEpisodeN, it.episodeNumber),
                overview = it.overview.orEmpty(),
                stillPath = it.stillPath,
                runtime = it.runtime,
                airDate = it.airDate,
            )
        }
        seasonCache[cacheKey] = episodes
        return episodes
    }

    private fun TmdbImages.bestLogo(): String? {
        val lang = settings.current.language.substringBefore('-')
        val ranked = logos.sortedWith(
            compareBy<TmdbImage> {
                when (it.lang) {
                    lang -> 0
                    "en" -> 1
                    null -> 2
                    else -> 3
                }
            }.thenByDescending { it.vote },
        )
        return ranked.firstOrNull()?.filePath
    }
}

internal fun TmdbItem.toMediaItem(forced: MediaType?): MediaItem? {
    val type = forced ?: MediaType.fromTmdb(mediaType) ?: return null
    if (adult) return null
    return MediaItem(
        id = id,
        type = type,
        title = title ?: name ?: return null,
        posterPath = posterPath,
        backdropPath = backdropPath,
        overview = overview.orEmpty(),
        year = (releaseDate ?: firstAirDate)?.take(4)?.takeIf { it.isNotBlank() },
        rating = voteAverage,
        genreIds = genreIds,
    )
}
