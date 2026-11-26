package com.synrg.harbor.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.synrg.harbor.BuildConfig
import com.synrg.harbor.episodes.EpisodeAlerts
import com.synrg.harbor.episodes.EpisodeCheckJob
import com.synrg.harbor.data.AddonEntry
import com.synrg.harbor.graph
import com.synrg.harbor.ui.components.GlassButton
import com.synrg.harbor.ui.components.PrimaryButton
import com.synrg.harbor.ui.theme.HIcons
import com.synrg.harbor.ui.theme.HarborColors
import com.synrg.harbor.update.UpdateCard
import com.synrg.harbor.update.UpdateState
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(bottomPadding: Dp) {
    val context = LocalContext.current
    val graph = context.graph
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    Column(
        Modifier.fillMaxSize().background(HarborColors.Bg).statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(bottom = bottomPadding + 32.dp),
    ) {
        Text("Ajustes", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 8.dp))

        // Stremio account
        Section("Cuenta de Stremio", "Trae tus addons y mantiene “Seguir viendo” y “Mi lista” iguales en Stremio, Harbor de escritorio y acá.") {
            val email = settings.stremioEmail
            if (email != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(HarborColors.Good))
                    Spacer(Modifier.width(8.dp))
                    Text(email, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(12.dp))
                var syncing by remember { mutableStateOf(false) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(if (syncing) "Sincronizando…" else "Sincronizar", HIcons.Refresh, enabled = !syncing, onClick = {
                        syncing = true
                        scope.launch {
                            runCatching {
                                val addons = graph.stremio.syncAddons()
                                addons to graph.sync.pull()
                            }
                                .onSuccess { (addons, lib) -> toast(syncSummary(addons, lib.continueWatching, lib.added)) }
                                .onFailure { toast(it.message ?: "No se pudo sincronizar") }
                            syncing = false
                        }
                    }, modifier = Modifier.weight(1f))
                    GlassButton("Salir", null, onClick = { graph.stremio.logout() })
                }
            } else {
                var user by remember { mutableStateOf("") }
                var pass by remember { mutableStateOf("") }
                var busy by remember { mutableStateOf(false) }
                Field(user, { user = it }, "Email", KeyboardType.Email)
                Spacer(Modifier.height(8.dp))
                Field(pass, { pass = it }, "Contraseña", KeyboardType.Password, secret = true)
                Spacer(Modifier.height(12.dp))
                PrimaryButton(if (busy) "Conectando…" else "Conectar", null, enabled = !busy && user.isNotBlank() && pass.isNotBlank(), onClick = {
                    busy = true
                    scope.launch {
                        runCatching {
                            graph.stremio.login(user, pass)
                            graph.sync.pull()
                        }
                            .onSuccess { toast("Cuenta conectada"); pass = "" }
                            .onFailure { toast(it.message ?: "No se pudo iniciar sesión") }
                        busy = false
                    }
                }, modifier = Modifier.fillMaxWidth())
                Text(
                    "La contraseña solo se usa para pedir la sesión; se guarda la clave de sesión, no la contraseña.",
                    style = MaterialTheme.typography.bodySmall, color = HarborColors.TextFaint, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        // Addons
        Section("Addons de fuentes", "Se consultan todos a la vez; cada uno aparece en cuanto responde.") {
            settings.addons.forEach { addon ->
                AddonRow(
                    addon,
                    onToggle = { on -> graph.settings.update { s -> s.copy(addons = s.addons.map { if (it.transportUrl == addon.transportUrl) it.copy(enabled = on) else it }) } },
                    onRemove = { graph.settings.update { s -> s.copy(addons = s.addons.filterNot { it.transportUrl == addon.transportUrl }) } },
                )
            }
            if (settings.addons.isEmpty()) {
                Text("No hay addons. Conectá tu cuenta o pegá la URL de uno.", style = MaterialTheme.typography.bodySmall, color = HarborColors.TextDim)
            }
            Spacer(Modifier.height(10.dp))
            var url by remember { mutableStateOf("") }
            var adding by remember { mutableStateOf(false) }
            Field(url, { url = it }, "URL del addon (manifest.json o stremio://)", KeyboardType.Uri)
            Spacer(Modifier.height(8.dp))
            GlassButton(if (adding) "Agregando…" else "Agregar addon", HIcons.Add, onClick = {
                if (url.isBlank() || adding) return@GlassButton
                adding = true
                scope.launch {
                    runCatching { graph.addons.fetchManifest(url) }
                        .onSuccess { entry ->
                            graph.settings.update { s -> s.copy(addons = s.addons.filterNot { it.id == entry.id } + entry, addonsSeeded = true) }
                            toast("${entry.name} agregado")
                            url = ""
                        }
                        .onFailure { toast(it.message ?: "No es un addon válido") }
                    adding = false
                }
            }, modifier = Modifier.fillMaxWidth())
        }

        // Playback
        Section("Reproducción", null) {
            Label("Audio preferido")
            Choice(listOf("" to "Original", "es" to "Español", "en" to "Inglés", "ja" to "Japonés"), settings.audioLanguage) { v ->
                graph.settings.update { it.copy(audioLanguage = v) }
            }
            Spacer(Modifier.height(14.dp))
            Label("Subtítulos")
            Choice(listOf("" to "No", "es" to "Español", "en" to "Inglés"), settings.subtitleLanguage) { v ->
                graph.settings.update { it.copy(subtitleLanguage = v) }
            }
            Spacer(Modifier.height(14.dp))
            Label("Tamaño de subtítulos")
            Choice(listOf("0.85" to "Chico", "1.0" to "Normal", "1.2" to "Grande", "1.45" to "Enorme"), settings.subtitleScale.toString()) { v ->
                graph.settings.update { it.copy(subtitleScale = v.toFloat()) }
            }
            Spacer(Modifier.height(14.dp))
            ToggleRow("Siguiente episodio automático", settings.autoplayNext) { on -> graph.settings.update { it.copy(autoplayNext = on) } }
            ToggleRow("Preguntar antes de usar datos móviles", settings.askOnMobileData) { on -> graph.settings.update { it.copy(askOnMobileData = on) } }
        }

        // Downloads
        Section("Descargas", "Para ver sin internet: tocá ⬇ en una fuente. Las ves en Mi lista → Descargas.") {
            ToggleRow("Descargar solo con Wi-Fi", settings.downloadWifiOnly) { on -> graph.settings.update { it.copy(downloadWifiOnly = on) } }
        }

        // New-episode alerts
        Section("Avisos", "Cuando sale un episodio de una serie de tu lista o que estás viendo. También aparecen en Continuar.") {
            var permitted by remember { mutableStateOf(EpisodeAlerts.permitted(context)) }
            LifecycleResumeEffect(Unit) {
                permitted = EpisodeAlerts.permitted(context)
                onPauseOrDispose { }
            }
            fun openSystemSettings() = context.startActivity(
                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
            val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                permitted = EpisodeAlerts.permitted(context)
                if (!granted) openSystemSettings() // denied twice: only Android's screen can turn it on
            }
            fun requestPermission() {
                if (Build.VERSION.SDK_INT >= 33) ask.launch(Manifest.permission.POST_NOTIFICATIONS) else openSystemSettings()
            }
            ToggleRow("Avisarme de episodios nuevos", settings.episodeAlerts) { on ->
                graph.settings.update { it.copy(episodeAlerts = on) }
                if (on) graph.episodes.seed()
                EpisodeCheckJob.schedule(context, on)
                if (on && !permitted) requestPermission()
            }
            if (settings.episodeAlerts && !permitted) {
                Spacer(Modifier.height(8.dp))
                Text("Android tiene bloqueados los avisos de Harbor.", style = MaterialTheme.typography.bodySmall, color = HarborColors.Warn)
                Spacer(Modifier.height(8.dp))
                GlassButton("Permitir avisos", HIcons.Bell, onClick = ::requestPermission, modifier = Modifier.fillMaxWidth())
            }
        }

        // Metadata
        Section("Fichas y portadas", "Vienen de TMDB a través de su addon, sin clave. Con una clave propia de TMDB se suman recomendaciones y calificaciones por edad.") {
            Label("Idioma")
            Choice(listOf("es-MX" to "Español (Latam)", "es-ES" to "Español (España)", "en-US" to "Inglés"), settings.language) { v ->
                graph.settings.update { it.copy(language = v) }
            }
            Spacer(Modifier.height(14.dp))
            var key by remember { mutableStateOf(settings.tmdbKey) }
            Field(key, { key = it }, "Clave de TMDB (opcional)", KeyboardType.Ascii, secret = true)
            Spacer(Modifier.height(8.dp))
            GlassButton("Guardar clave", HIcons.Check, onClick = {
                graph.settings.update { it.copy(tmdbKey = key.trim()) }
                toast(if (key.isBlank()) "Usando TMDB sin clave" else "Clave guardada")
            }, modifier = Modifier.fillMaxWidth())
        }

        if (BuildConfig.SELF_UPDATE) {
            Section("Actualizaciones", "Tenés la versión ${BuildConfig.VERSION_NAME}. Las nuevas salen en GitHub y se instalan encima, sin perder nada.") {
                val update by graph.updater.state.collectAsStateWithLifecycle()
                ToggleRow("Avisar cuando haya una versión nueva", settings.checkUpdates) { on -> graph.settings.update { it.copy(checkUpdates = on) } }
                Spacer(Modifier.height(12.dp))
                if (update is UpdateState.Idle) {
                    var checking by remember { mutableStateOf(false) }
                    GlassButton(if (checking) "Buscando…" else "Buscar ahora", HIcons.Refresh, onClick = {
                        if (!checking) {
                            checking = true
                            scope.launch {
                                runCatching { graph.updater.check() }
                                    .onSuccess { if (it == null) toast("Ya tenés la última versión") }
                                    .onFailure { toast(it.message ?: "No se pudo consultar GitHub") }
                                checking = false
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth())
                } else {
                    UpdateCard(update)
                }
            }
        }

        Text(
            "Harbor para Android ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = HarborColors.TextFaint,
            modifier = Modifier.padding(top = 24.dp).align(Alignment.CenterHorizontally),
        )
    }
}

private fun syncSummary(addons: Int, continueWatching: Int, added: Int): String = buildList {
    add("$addons addons")
    if (continueWatching > 0) add("$continueWatching en Seguir viendo")
    if (added > 0) add("$added nuevos en Mi lista")
}.joinToString(" · ")

@Composable
private fun Section(title: String, subtitle: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(HarborColors.Surface)
            .border(1.dp, HarborColors.Outline, RoundedCornerShape(18.dp)).padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = HarborColors.TextDim, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.height(14.dp))
        content()
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = HarborColors.TextDim, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, type: KeyboardType, secret: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = HarborColors.Accent,
            unfocusedBorderColor = HarborColors.Outline,
            focusedLabelColor = HarborColors.Accent,
            cursorColor = HarborColors.Accent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Choice(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(HarborColors.SurfaceHigh).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(if (active) HarborColors.SurfaceHighest else HarborColors.SurfaceHigh)
                    .clickable { onSelect(value) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = if (active) HarborColors.Accent else HarborColors.TextDim, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = HarborColors.Accent, checkedThumbColor = HarborColors.OnAccent))
    }
}

@Composable
private fun AddonRow(addon: AddonEntry, onToggle: (Boolean) -> Unit, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).background(HarborColors.SurfaceHigh), contentAlignment = Alignment.Center) {
            if (addon.logo != null) AsyncImage(addon.logo, null, modifier = Modifier.size(36.dp))
            else Icon(HIcons.Link, null, tint = HarborColors.TextDim, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(addon.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                addon.resources.joinToString(" · ") { if (it.name == "stream") "fuentes" else if (it.name == "subtitles") "subtítulos" else it.name },
                style = MaterialTheme.typography.bodySmall, color = HarborColors.TextFaint, maxLines = 1,
            )
        }
        Icon(
            HIcons.Delete, "Quitar", tint = HarborColors.TextFaint,
            modifier = Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onRemove).padding(8.dp),
        )
        Switch(addon.enabled, onToggle, colors = SwitchDefaults.colors(checkedTrackColor = HarborColors.Accent, checkedThumbColor = HarborColors.OnAccent))
    }
}
