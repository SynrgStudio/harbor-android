package com.synrg.harbor.core

import java.util.Locale

/**
 * Text the core shows to people: errors that reach the screen, fallback names ("Season 2") and
 * the Home row titles. The core has no translations of its own: each app supplies them through
 * [CoreStrings.source], so they follow the app's language like the rest of its text.
 */
enum class CoreText {
    CommonSeasonN,
    CommonEpisodeN,
    StremioAddonResponded,
    StremioNoResponse,
    StremioUnexpectedResponse,
    StremioNotSignedIn,
    StremioNoSession,
    StreamsFallbackTitle,
    MetaErrorServer,
    MetaErrorTmdb,
    MetaErrorNotFound,
    MetaErrorIncomplete,
    MetaRowTrending,
    MetaRowPopularMovies,
    MetaRowPopularSeries,
    MetaRowNewReleases,
    MetaRowNowPlaying,
    MetaRowAnime,
    MetaRowNewSeries,
    MetaRowScifi,
    MetaRowComedy,
    MetaRowHorror,
    MetaRowTopMovies,
    MetaRowTopSeries,
    MetaRowAiring,
    SubsLanguageUnknown,
    SubsLanguageBrazil,
    PlayerEpisodeLabel,
    PlayerEpisodeLabelTitled,
}

/** Where [CoreText] gets its words, and the locale for dates and numbers. The app sets [source] at startup. */
object CoreStrings {
    interface Source {
        /** The language the app is shown in. */
        val locale: Locale

        /** [text] in that language, with [args] filled in like `String.format`. */
        fun get(text: CoreText, vararg args: Any): String
    }

    /** Until an app sets its own (and in unit tests): the names of the texts, in the JVM's locale. */
    @Volatile
    var source: Source = object : Source {
        override val locale: Locale get() = Locale.getDefault()
        override fun get(text: CoreText, vararg args: Any) = listOf(text.name, *args).joinToString(" ")
    }

    val locale: Locale get() = source.locale

    fun get(text: CoreText, vararg args: Any): String = source.get(text, *args)
}
