package com.synrg.harbor.tmdb

import com.synrg.harbor.core.CoreText
import com.synrg.harbor.data.Details
import com.synrg.harbor.data.Episode
import com.synrg.harbor.data.MediaItem
import com.synrg.harbor.data.MediaType
import com.synrg.harbor.data.Person
import com.synrg.harbor.data.Saga
import com.synrg.harbor.data.SettingsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A Home row: [title] is translated by the app when shown, so it follows the app language even on a kept ViewModel. */
class HomeRowSpec(val key: String, val title: CoreText, val load: suspend () -> List<MediaItem>)

/**
 * Where titles, images and episodes come from. Without a key (the default) the TMDB addon answers;
 * with a personal TMDB key saved in Settings, TMDB itself, which adds recommendations, age ratings
 * and a few more home rows. Both use TMDB ids, so nothing saved depends on the mode.
 */
class Metadata(
    private val settings: SettingsRepository,
    private val api: TmdbApi,
    private val addon: TmdbAddon,
    private val wikidata: Wikidata,
) {
    private val direct: Boolean get() = settings.current.tmdbKey.isNotBlank()

    /** With a TMDB key, people have their own pages (biography, full credits) by TMDB id. */
    val hasKey: Boolean get() = direct

    private val wikiLanguage: String get() = settings.current.language.substringBefore('-')

    suspend fun trending(): List<MediaItem> = if (direct) api.trending() else addon.trending()

    suspend fun search(query: String): List<MediaItem> = if (direct) api.search(query) else addon.search(query)

    suspend fun details(type: MediaType, id: Int): Details = if (direct) api.details(type, id) else addon.details(type, id)

    suspend fun season(tvId: Int, number: Int): List<Episode> = if (direct) api.season(tvId, number) else addon.season(tvId, number)

    suspend fun logo(type: MediaType, id: Int): String? = if (direct) api.logo(type, id) else addon.logo(type, id)

    /** Genre names by TMDB id; only the direct API needs them (addon items carry names). */
    suspend fun genreNames(): Map<Int, String> = if (direct) api.genreNames() else emptyMap()

    /** A person by TMDB id (needs a key). */
    suspend fun person(id: Int): Person = api.person(id)

    /** Without a key: the titles the addon's search finds for a name (quick, with posters, at most 40 per type). */
    suspend fun personCredits(name: String): List<MediaItem> = addon.personCredits(name)

    /** Without a key: everything a person was in, from Wikidata (no posters; see [item]). */
    suspend fun filmography(titleImdb: String, name: String): List<Wikidata.Work>? =
        runCatching { wikidata.filmography(titleImdb, name, wikiLanguage) }.getOrNull()

    /** One title as a list item (its poster and name in the metadata language). */
    suspend fun item(type: MediaType, id: Int): MediaItem? = runCatching {
        if (direct) api.details(type, id).item else addon.meta(type, id).toMediaItem(type)
    }.getOrNull()

    /** The saga a movie belongs to: TMDB's collection with a key, Wikidata's film series without one. */
    suspend fun saga(details: Details): Saga? {
        if (details.item.type != MediaType.Movie) return null
        if (direct) return details.collectionId?.let { runCatching { api.collection(it) }.getOrNull() }
        val imdb = details.imdbId ?: return null
        val ref = runCatching { wikidata.saga(imdb, wikiLanguage) }.getOrNull() ?: return null
        val gate = Semaphore(6)
        val parts = coroutineScope {
            ref.tmdbIds.map { id -> async { gate.withPermit { item(MediaType.Movie, id) } } }.awaitAll().filterNotNull()
        }
        return if (parts.size >= 2) Saga(ref.name, parts) else null
    }

    suspend fun recommendations(details: Details): List<MediaItem> =
        if (direct) details.recommendations else addon.similar(details)

    /** The title behind a Stremio id: an IMDb id ("tt…") or "tmdb:123". Other catalogs' ids give null. */
    suspend fun itemByStremioId(type: MediaType, id: String): MediaItem? = when {
        id.startsWith("tt") -> addon.itemByImdb(type, id)
        id.startsWith("tmdb:") -> id.removePrefix("tmdb:").toIntOrNull()?.let { details(type, it).item }
        else -> null
    }

    fun homeRows(): List<HomeRowSpec> = if (direct) directRows() else addonRows()

    /** The addon filters by genre *name*, spelled in the metadata language (checked against its manifest). */
    private class AddonGenres(val animation: String, val sciFi: String, val comedy: String, val horror: String)

    private val addonGenres: AddonGenres
        get() = when {
            settings.current.language.startsWith("es") ->
                AddonGenres(animation = "Animación", sciFi = "Ciencia ficción", comedy = "Comedia", horror = "Terror")
            settings.current.language.startsWith("pt") ->
                AddonGenres(animation = "Animação", sciFi = "Ficção científica", comedy = "Comédia", horror = "Terror")
            else -> AddonGenres(animation = "Animation", sciFi = "Science Fiction", comedy = "Comedy", horror = "Horror")
        }

    private fun addonRows(): List<HomeRowSpec> = listOf(
        HomeRowSpec("trending", CoreText.MetaRowTrending) { addon.trending().drop(6) },
        HomeRowSpec("movies", CoreText.MetaRowPopularMovies) { addon.catalog(MediaType.Movie, "tmdb.top") },
        HomeRowSpec("series", CoreText.MetaRowPopularSeries) { addon.catalog(MediaType.Tv, "tmdb.top") },
        HomeRowSpec("cinema", CoreText.MetaRowNewReleases) { addon.catalog(MediaType.Movie, "tmdb.latest") },
        HomeRowSpec("anime", CoreText.MetaRowAnime) {
            addon.catalog(MediaType.Tv, "tmdb.language", "genre" to "Japanese").filter { addonGenres.animation in it.genres }
        },
        HomeRowSpec("new_series", CoreText.MetaRowNewSeries) { addon.catalog(MediaType.Tv, "tmdb.latest") },
        HomeRowSpec("scifi", CoreText.MetaRowScifi) { addon.catalog(MediaType.Movie, "tmdb.top", "genre" to addonGenres.sciFi) },
        HomeRowSpec("comedy", CoreText.MetaRowComedy) { addon.catalog(MediaType.Movie, "tmdb.top", "genre" to addonGenres.comedy) },
        HomeRowSpec("horror", CoreText.MetaRowHorror) { addon.catalog(MediaType.Movie, "tmdb.top", "genre" to addonGenres.horror) },
    )

    private fun directRows(): List<HomeRowSpec> = listOf(
        HomeRowSpec("trending", CoreText.MetaRowTrending) { api.trending().drop(6) },
        HomeRowSpec("movies", CoreText.MetaRowPopularMovies) { api.list(MediaType.Movie, "popular") },
        HomeRowSpec("series", CoreText.MetaRowPopularSeries) { api.list(MediaType.Tv, "popular") },
        HomeRowSpec("cinema", CoreText.MetaRowNowPlaying) { api.list(MediaType.Movie, "now_playing") },
        HomeRowSpec("anime", CoreText.MetaRowAnime) {
            api.discover(
                MediaType.Tv,
                "with_genres" to "16",
                "with_original_language" to "ja",
                "sort_by" to "popularity.desc",
                "vote_count.gte" to "50",
            )
        },
        HomeRowSpec("top_movies", CoreText.MetaRowTopMovies) { api.list(MediaType.Movie, "top_rated") },
        HomeRowSpec("top_series", CoreText.MetaRowTopSeries) { api.list(MediaType.Tv, "top_rated") },
        HomeRowSpec("airing", CoreText.MetaRowAiring) { api.list(MediaType.Tv, "on_the_air") },
    )
}
