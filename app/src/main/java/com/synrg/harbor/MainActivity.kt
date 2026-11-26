package com.synrg.harbor

import android.Manifest
import android.content.Intent
import android.os.Build
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.synrg.harbor.core.AppLink
import com.synrg.harbor.data.MediaType
import com.synrg.harbor.episodes.EpisodeAlerts
import com.synrg.harbor.episodes.EpisodeCheckJob
import com.synrg.harbor.ui.HarborRoot
import com.synrg.harbor.ui.theme.HarborTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    companion object {
        /** From a new-episode notification: open a title's page ([EXTRA_TYPE] "movie"/"tv", [EXTRA_ID]). */
        const val ACTION_OPEN = "com.synrg.harbor.OPEN"
        /** From a launcher shortcut: resume the progress entry [EXTRA_KEY]. */
        const val ACTION_RESUME = "com.synrg.harbor.RESUME"
        /** From the downloads notification. */
        const val ACTION_DOWNLOADS = "com.synrg.harbor.DOWNLOADS"
        const val EXTRA_TYPE = "type"
        const val EXTRA_ID = "id"
        const val EXTRA_KEY = "key"
        const val EXTRA_SEASON = "season"
    }

    /** What a notification or shortcut asked this window to open; its HarborRoot takes it and clears it. */
    private val links = MutableStateFlow<AppLink?>(null)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent { HarborTheme { HarborRoot(links) } }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // Picks up what was watched on other devices (throttled, silent when offline).
        if (graph.settings.current.stremioAuthKey != null) graph.sync.pullInBackground()
        graph.updater.checkInBackground()
        graph.downloads.resumePending()
        val settings = graph.settings.current
        if (settings.episodeAlerts) graph.episodes.seed()
        EpisodeCheckJob.schedule(this, settings.episodeAlerts)
        // Asked once, and only when there is something to be told about.
        if (Build.VERSION.SDK_INT >= 33 && settings.episodeAlerts && !settings.notificationPromptShown && !EpisodeAlerts.permitted(this)) {
            lifecycleScope.launch {
                if (withContext(Dispatchers.IO) { graph.episodes.followsAny }) {
                    graph.settings.update { it.copy(notificationPromptShown = true) }
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from Android's "install unknown apps" screen: carry on with a pending update.
        graph.updater.resume()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** stremio://…/manifest.json links (the "Install" buttons on addon sites) add the addon. */
    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_OPEN -> {
                val type = MediaType.fromTmdb(intent.getStringExtra(EXTRA_TYPE) ?: "") ?: return
                val id = intent.getIntExtra(EXTRA_ID, -1)
                val season = intent.getIntExtra(EXTRA_SEASON, -1).takeIf { it >= 0 }
                if (id > 0) links.value = AppLink.Open(type, id, season)
                return
            }
            ACTION_RESUME -> {
                intent.getStringExtra(EXTRA_KEY)?.let { links.value = AppLink.Resume(it) }
                return
            }
            ACTION_DOWNLOADS -> {
                links.value = AppLink.Downloads
                return
            }
        }
        val data = intent?.data ?: return
        if (data.scheme != "stremio") return
        intent.data = null
        lifecycleScope.launch {
            runCatching { graph.addons.fetchManifest(data.toString()) }
                .onSuccess { entry ->
                    graph.settings.update { s -> s.copy(addons = s.addons.filterNot { it.id == entry.id } + entry, addonsSeeded = true) }
                    Toast.makeText(this@MainActivity, "${entry.name} instalado", Toast.LENGTH_SHORT).show()
                }
                .onFailure { Toast.makeText(this@MainActivity, it.message ?: "No se pudo instalar el addon", Toast.LENGTH_LONG).show() }
        }
    }
}
