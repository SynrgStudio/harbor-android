package com.synrg.harbor

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.synrg.harbor.ui.HarborRoot
import com.synrg.harbor.ui.theme.HarborTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent { HarborTheme { HarborRoot() } }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** stremio://…/manifest.json links (the "Install" buttons on addon sites) add the addon. */
    private fun handleIntent(intent: Intent?) {
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
