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
import java.util.Locale

/**
 * Localized strings for code with no Context at hand: view models, repositories, services, and
 * errors that the UI shows later. Composables use `stringResource` instead.
 *
 * It doesn't read the application context directly: after a per-app language switch (Android 13+)
 * activities come back in the new language, but the application context can keep the old one.
 * So it builds its own context in the language in effect, and rebuilds it whenever an activity is
 * created or the configuration changes.
 */
object Strings {
    private lateinit var app: Application

    @Volatile
    private var localized: Context? = null

    fun init(application: Application) {
        app = application
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
    val locale: Locale get() = context().resources.configuration.locales[0]

    fun get(@StringRes id: Int, vararg args: Any): String = context().getString(id, *args)

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
