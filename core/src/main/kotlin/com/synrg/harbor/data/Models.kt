package com.synrg.harbor.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
enum class MediaType(val tmdb: String, val stremio: String) {
    Movie("movie", "movie"),
    Tv("tv", "series");

    companion object {
        fun fromTmdb(value: String?): MediaType? = when (value) {
            "movie" -> Movie
            "tv" -> Tv
            else -> null
        }
    }
}

/** A title as it appears in lists (posters, search results, rows). */
@Immutable
@Serializable
data class MediaItem(
    val id: Int,
    val type: MediaType,
    val title: String,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val overview: String = "",
    val year: String? = null,
    val rating: Double = 0.0,
    val genreIds: List<Int> = emptyList(),
    /** Genre names, when the source gives names instead of ids. */
    val genres: List<String> = emptyList(),
)

@Immutable
data class HeroItem(
    val item: MediaItem,
    val logoPath: String?,
    val genres: List<String>,
)

/** [personId] is TMDB's person id, known only with a TMDB key; without one, the person is found by name. */
@Immutable
data class CastMember(val id: Int, val name: String, val character: String?, val profilePath: String?, val personId: Int? = null)

@Immutable
data class SeasonInfo(val number: Int, val name: String, val episodeCount: Int)

@Immutable
data class Episode(
    val season: Int,
    val number: Int,
    val name: String,
    val overview: String,
    val stillPath: String?,
    val runtime: Int?,
    val airDate: String?,
) {
    val released: Boolean
        get() = airDate.isNullOrBlank() || airDate <= java.time.LocalDate.now().toString()
}

@Immutable
data class Details(
    val item: MediaItem,
    val tagline: String?,
    val genres: List<String>,
    val runtime: Int?,
    val seasons: List<SeasonInfo>,
    val cast: List<CastMember>,
    val recommendations: List<MediaItem>,
    val imdbId: String?,
    val logoPath: String?,
    val status: String?,
    val certification: String?,
    /** TMDB's collection for a movie (direct API only); without a key, sagas come from Wikidata. */
    val collectionId: Int? = null,
    /** Where the title streams, rents or sells in the region (direct API only). */
    val providers: WatchProviders? = null,
)

/** An actor or director: who they are and what they were in, best known first. */
@Immutable
data class Person(
    val name: String,
    val profilePath: String?,
    val biography: String?,
    val department: String?,
    val birthday: String?,
    val deathday: String?,
    val birthplace: String?,
    /** Most known titles first. */
    val knownFor: List<MediaItem>,
    /** Everything, newest first. */
    val credits: List<MediaItem>,
)

/** The movies of a saga, in release order. */
@Immutable
data class Saga(val name: String, val parts: List<MediaItem>)

@Immutable
data class WatchProvider(val id: Int, val name: String, val logoPath: String?)

/** Legal services that have a title in the user's region (TMDB's data, from JustWatch). */
@Immutable
data class WatchProviders(
    val link: String?,
    val stream: List<WatchProvider>,
    val free: List<WatchProvider>,
    val rent: List<WatchProvider>,
    val buy: List<WatchProvider>,
) {
    val isEmpty: Boolean get() = stream.isEmpty() && free.isEmpty() && rent.isEmpty() && buy.isEmpty()
}

/** Image URLs from TMDB paths at the size each place needs; full URLs (other hosts) pass through. */
object Img {
    private const val BASE = "https://image.tmdb.org/t/p/"
    private fun url(path: String?, size: String) = path?.let { if (it.startsWith("http")) it else BASE + size + it }
    fun poster(path: String?, size: String = "w342") = url(path, size)
    fun backdrop(path: String?, size: String = "w1280") = url(path, size)
    fun still(path: String?, size: String = "w300") = url(path, size)
    fun logo(path: String?, size: String = "w500") = url(path, size)
    fun profile(path: String?, size: String = "w185") = url(path, size)
}
