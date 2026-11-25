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
)

@Immutable
data class HeroItem(
    val item: MediaItem,
    val logoPath: String?,
    val genres: List<String>,
)

@Immutable
data class CastMember(val id: Int, val name: String, val character: String?, val profilePath: String?)

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
)

object Img {
    private const val BASE = "https://image.tmdb.org/t/p/"
    fun poster(path: String?, size: String = "w342") = path?.let { BASE + size + it }
    fun backdrop(path: String?, size: String = "w1280") = path?.let { BASE + size + it }
    fun still(path: String?, size: String = "w300") = path?.let { BASE + size + it }
    fun logo(path: String?, size: String = "w500") = path?.let { BASE + size + it }
    fun profile(path: String?, size: String = "w185") = path?.let { BASE + size + it }
}
