package com.synrg.harbor.tmdb

import androidx.annotation.StringRes
import com.synrg.harbor.R
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

/** A Home row: [title] is a string resource, so it follows the app language even on a kept ViewModel. */
class HomeRowSpec(val key: String, @StringRes val title: Int, val load: suspend () -> List<MediaItem>)

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
        HomeRowSpec("trending", R.string.meta_row_trending) { addon.trending().drop(6) },
        HomeRowSpec("movies", R.string.meta_row_popular_movies) { addon.catalog(MediaType.Movie, "tmdb.top") },
        HomeRowSpec("series", R.string.meta_row_popular_series) { addon.catalog(MediaType.Tv, "tmdb.top") },
        HomeRowSpec("cinema", R.string.meta_row_new_releases) { addon.catalog(MediaType.Movie, "tmdb.latest") },
        HomeRowSpec("anime", R.string.meta_row_anime) {
            addon.catalog(MediaType.Tv, "tmdb.language", "genre" to "Japanese").filter { addonGenres.animation in it.genres }
        },
        HomeRowSpec("new_series", R.string.meta_row_new_series) { addon.catalog(MediaType.Tv, "tmdb.latest") },
        HomeRowSpec("scifi", R.string.meta_row_scifi) { addon.catalog(MediaType.Movie, "tmdb.top", "genre" to addonGenres.sciFi) },
        HomeRowSpec("comedy", R.string.meta_row_comedy) { addon.catalog(MediaType.Movie, "tmdb.top", "genre" to addonGenres.comedy) },
        HomeRowSpec("horror", R.string.meta_row_horror) { addon.catalog(MediaType.Movie, "tmdb.top", "genre" to addonGenres.horror) },
    )

    private fun directRows(): List<HomeRowSpec> = listOf(
        HomeRowSpec("trending", R.string.meta_row_trending) { api.trending().drop(6) },
        HomeRowSpec("movies", R.string.meta_row_popular_movies) { api.list(MediaType.Movie, "popular") },
        HomeRowSpec("series", R.string.meta_row_popular_series) { api.list(MediaType.Tv, "popular") },
        HomeRowSpec("cinema", R.string.meta_row_now_playing) { api.list(MediaType.Movie, "now_playing") },
        HomeRowSpec("anime", R.string.meta_row_anime) {
            api.discover(
                MediaType.Tv,
                "with_genres" to "16",
                "with_original_language" to "ja",
                "sort_by" to "popularity.desc",
                "vote_count.gte" to "50",
            )
        },
        HomeRowSpec("top_movies", R.string.meta_row_top_movies) { api.list(MediaType.Movie, "top_rated") },
        HomeRowSpec("top_series", R.string.meta_row_top_series) { api.list(MediaType.Tv, "top_rated") },
        HomeRowSpec("airing", R.string.meta_row_airing) { api.list(MediaType.Tv, "on_the_air") },
    )
}
