package com.synrg.harbor.tmdb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TmdbPage(
    val page: Int = 1,
    val results: List<TmdbItem> = emptyList(),
    @SerialName("total_pages") val totalPages: Int = 1,
)

@Serializable
data class TmdbItem(
    val id: Int,
    @SerialName("media_type") val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val overview: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("vote_count") val voteCount: Int = 0,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
    val adult: Boolean = false,
)

@Serializable
data class TmdbGenre(val id: Int, val name: String)

@Serializable
data class TmdbGenreList(val genres: List<TmdbGenre> = emptyList())

@Serializable
data class TmdbDetails(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    val tagline: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val runtime: Int? = null,
    @SerialName("episode_run_time") val episodeRunTime: List<Int> = emptyList(),
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    val genres: List<TmdbGenre> = emptyList(),
    val seasons: List<TmdbSeasonSummary> = emptyList(),
    val status: String? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    val credits: TmdbCredits? = null,
    val images: TmdbImages? = null,
    val recommendations: TmdbPage? = null,
    val similar: TmdbPage? = null,
    @SerialName("external_ids") val externalIds: TmdbExternalIds? = null,
    @SerialName("release_dates") val releaseDates: TmdbReleaseDates? = null,
    @SerialName("content_ratings") val contentRatings: TmdbContentRatings? = null,
)

@Serializable
data class TmdbSeasonSummary(
    @SerialName("season_number") val seasonNumber: Int,
    val name: String? = null,
    @SerialName("episode_count") val episodeCount: Int = 0,
    @SerialName("air_date") val airDate: String? = null,
)

@Serializable
data class TmdbCredits(val cast: List<TmdbCast> = emptyList())

@Serializable
data class TmdbCast(
    val id: Int,
    val name: String,
    val character: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
)

@Serializable
data class TmdbImages(val logos: List<TmdbImage> = emptyList())

@Serializable
data class TmdbImage(
    @SerialName("file_path") val filePath: String,
    @SerialName("iso_639_1") val lang: String? = null,
    @SerialName("vote_average") val vote: Double = 0.0,
)

@Serializable
data class TmdbExternalIds(@SerialName("imdb_id") val imdbId: String? = null)

@Serializable
data class TmdbReleaseDates(val results: List<TmdbReleaseCountry> = emptyList())

@Serializable
data class TmdbReleaseCountry(
    @SerialName("iso_3166_1") val country: String,
    @SerialName("release_dates") val dates: List<TmdbReleaseDate> = emptyList(),
)

@Serializable
data class TmdbReleaseDate(val certification: String? = null)

@Serializable
data class TmdbContentRatings(val results: List<TmdbContentRating> = emptyList())

@Serializable
data class TmdbContentRating(@SerialName("iso_3166_1") val country: String, val rating: String? = null)

@Serializable
data class TmdbSeason(
    @SerialName("season_number") val seasonNumber: Int,
    val name: String? = null,
    val episodes: List<TmdbEpisode> = emptyList(),
)

@Serializable
data class TmdbEpisode(
    @SerialName("episode_number") val episodeNumber: Int,
    @SerialName("season_number") val seasonNumber: Int,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("still_path") val stillPath: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    val runtime: Int? = null,
)

@Serializable
data class TmdbFindResult(
    @SerialName("movie_results") val movies: List<TmdbItem> = emptyList(),
    @SerialName("tv_results") val tv: List<TmdbItem> = emptyList(),
)
