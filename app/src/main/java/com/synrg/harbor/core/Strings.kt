package com.synrg.harbor.core

import android.app.Activity
import android.app.Application
import android.app.LocaleManager
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.synrg.harbor.R
import java.util.Locale

/**
 * Localized strings for code with no Context at hand: view models, repositories, services, and
 * errors that the UI shows later. Composables use `stringResource` instead.
 *
 * It doesn't read the application context directly: after a per-app language switch (Android 13+)
 * activities come back in the new language, but the application context can keep the old one.
 * So it builds its own context in the language in effect, and rebuilds it whenever an activity is
 * created or the configuration changes.
 *
 * It also gives the core module its words ([CoreText]), from the same resources.
 */
object Strings : CoreStrings.Source {
    private lateinit var app: Application

    @Volatile
    private var localized: Context? = null

    fun init(application: Application) {
        app = application
        CoreStrings.source = this
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                localized = null
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        application.registerComponentCallbacks(object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                localized = null
            }
            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = Unit
        })
    }

    /** The language the app is shown in, for formatting dates and numbers. */
    override val locale: Locale get() = context().resources.configuration.locales[0]

    fun get(@StringRes id: Int, vararg args: Any): String = context().getString(id, *args)

    override fun get(text: CoreText, vararg args: Any): String = get(text.res, *args)

    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
        context().resources.getQuantityString(id, count, *args)

    private fun context(): Context = localized ?: build().also { localized = it }

    private fun build(): Context {
        if (Build.VERSION.SDK_INT < 33) return app
        val picked = app.getSystemService(LocaleManager::class.java)?.applicationLocales
        val locales = if (picked == null || picked.isEmpty) Resources.getSystem().configuration.locales else picked
        val config = Configuration(app.resources.configuration).apply { setLocales(locales) }
        return app.createConfigurationContext(config)
    }
}

/** The resource with each core text. */
@get:StringRes
val CoreText.res: Int
    get() = when (this) {
        CoreText.CommonSeasonN -> R.string.common_season_n
        CoreText.CommonEpisodeN -> R.string.common_episode_n
        CoreText.StremioAddonResponded -> R.string.stremio_addon_responded
        CoreText.StremioNoResponse -> R.string.stremio_no_response
        CoreText.StremioUnexpectedResponse -> R.string.stremio_unexpected_response
        CoreText.StremioNotSignedIn -> R.string.stremio_not_signed_in
        CoreText.StremioNoSession -> R.string.stremio_no_session
        CoreText.StreamsFallbackTitle -> R.string.streams_fallback_title
        CoreText.MetaErrorServer -> R.string.meta_error_server
        CoreText.MetaErrorTmdb -> R.string.meta_error_tmdb
        CoreText.MetaErrorNotFound -> R.string.meta_error_not_found
        CoreText.MetaErrorIncomplete -> R.string.meta_error_incomplete
        CoreText.MetaRowTrending -> R.string.meta_row_trending
        CoreText.MetaRowPopularMovies -> R.string.meta_row_popular_movies
        CoreText.MetaRowPopularSeries -> R.string.meta_row_popular_series
        CoreText.MetaRowNewReleases -> R.string.meta_row_new_releases
        CoreText.MetaRowNowPlaying -> R.string.meta_row_now_playing
        CoreText.MetaRowAnime -> R.string.meta_row_anime
        CoreText.MetaRowNewSeries -> R.string.meta_row_new_series
        CoreText.MetaRowScifi -> R.string.meta_row_scifi
        CoreText.MetaRowComedy -> R.string.meta_row_comedy
        CoreText.MetaRowHorror -> R.string.meta_row_horror
        CoreText.MetaRowTopMovies -> R.string.meta_row_top_movies
        CoreText.MetaRowTopSeries -> R.string.meta_row_top_series
        CoreText.MetaRowAiring -> R.string.meta_row_airing
        CoreText.SubsLanguageUnknown -> R.string.subs_language_unknown
        CoreText.SubsLanguageBrazil -> R.string.subs_language_brazil
        CoreText.PlayerEpisodeLabel -> R.string.player_episode_label
        CoreText.PlayerEpisodeLabelTitled -> R.string.player_episode_label_titled
    }
