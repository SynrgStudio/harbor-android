package com.synrg.harbor.tmdb

import com.synrg.harbor.data.Details
import com.synrg.harbor.data.Episode
import com.synrg.harbor.data.MediaItem
import com.synrg.harbor.data.MediaType
import com.synrg.harbor.data.SettingsRepository

class HomeRowSpec(val key: String, val title: String, val load: suspend () -> List<MediaItem>)

/**
 * Where titles, images and episodes come from. Without a key (the default) the TMDB addon answers;
 * with a personal TMDB key saved in Settings, TMDB itself, which adds recommendations, age ratings
 * and a few more home rows. Both use TMDB ids, so nothing saved depends on the mode.
 */
class Metadata(
    private val settings: SettingsRepository,
    private val api: TmdbApi,
    private val addon: TmdbAddon,
) {
    private val direct: Boolean get() = settings.current.tmdbKey.isNotBlank()

    suspend fun trending(): List<MediaItem> = if (direct) api.trending() else addon.trending()

    suspend fun search(query: String): List<MediaItem> = if (direct) api.search(query) else addon.search(query)

    suspend fun details(type: MediaType, id: Int): Details = if (direct) api.details(type, id) else addon.details(type, id)

    suspend fun season(tvId: Int, number: Int): List<Episode> = if (direct) api.season(tvId, number) else addon.season(tvId, number)

    suspend fun logo(type: MediaType, id: Int): String? = if (direct) api.logo(type, id) else addon.logo(type, id)

    /** Genre names by TMDB id; only the direct API needs them (addon items carry names). */
    suspend fun genreNames(): Map<Int, String> = if (direct) api.genreNames() else emptyMap()

    suspend fun recommendations(details: Details): List<MediaItem> =
        if (direct) details.recommendations else addon.similar(details)

    /** The title behind a Stremio id: an IMDb id ("tt…") or "tmdb:123". Other catalogs' ids give null. */
    suspend fun itemByStremioId(type: MediaType, id: String): MediaItem? = when {
        id.startsWith("tt") -> addon.itemByImdb(type, id)
        id.startsWith("tmdb:") -> id.removePrefix("tmdb:").toIntOrNull()?.let { details(type, it).item }
        else -> null
    }

    fun homeRows(): List<HomeRowSpec> = if (direct) directRows() else addonRows()

    private fun addonRows(): List<HomeRowSpec> = listOf(
        HomeRowSpec("trending", "Tendencias de la semana") { addon.trending().drop(6) },
        HomeRowSpec("movies", "Películas populares") { addon.catalog(MediaType.Movie, "tmdb.top") },
        HomeRowSpec("series", "Series populares") { addon.catalog(MediaType.Tv, "tmdb.top") },
        HomeRowSpec("cinema", "Estrenos") { addon.catalog(MediaType.Movie, "tmdb.latest") },
        HomeRowSpec("anime", "Anime") {
            addon.catalog(MediaType.Tv, "tmdb.language", "genre" to "Japanese").filter { "Animación" in it.genres }
        },
        HomeRowSpec("new_series", "Series nuevas") { addon.catalog(MediaType.Tv, "tmdb.latest") },
        HomeRowSpec("scifi", "Ciencia ficción") { addon.catalog(MediaType.Movie, "tmdb.top", "genre" to "Ciencia ficción") },
        HomeRowSpec("comedy", "Para reírse") { addon.catalog(MediaType.Movie, "tmdb.top", "genre" to "Comedia") },
        HomeRowSpec("horror", "Terror") { addon.catalog(MediaType.Movie, "tmdb.top", "genre" to "Terror") },
    )

    private fun directRows(): List<HomeRowSpec> = listOf(
        HomeRowSpec("trending", "Tendencias de la semana") { api.trending().drop(6) },
        HomeRowSpec("movies", "Películas populares") { api.list(MediaType.Movie, "popular") },
        HomeRowSpec("series", "Series populares") { api.list(MediaType.Tv, "popular") },
        HomeRowSpec("cinema", "Ahora en cines") { api.list(MediaType.Movie, "now_playing") },
        HomeRowSpec("anime", "Anime") {
            api.discover(
                MediaType.Tv,
                "with_genres" to "16",
                "with_original_language" to "ja",
                "sort_by" to "popularity.desc",
                "vote_count.gte" to "50",
            )
        },
        HomeRowSpec("top_movies", "Películas mejor valoradas") { api.list(MediaType.Movie, "top_rated") },
        HomeRowSpec("top_series", "Series aclamadas") { api.list(MediaType.Tv, "top_rated") },
        HomeRowSpec("airing", "Series en emisión") { api.list(MediaType.Tv, "on_the_air") },
    )
}
