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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.synrg.harbor.BuildConfig
import com.synrg.harbor.R
import com.synrg.harbor.core.Strings
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
    // Read here: the toasts below are shown from click handlers and coroutines, outside composition.
    val syncFailed = stringResource(R.string.settings_sync_failed)
    val signedIn = stringResource(R.string.settings_signed_in)
    val signInFailed = stringResource(R.string.settings_sign_in_failed)
    val addonInvalid = stringResource(R.string.settings_addon_invalid)
    val keyCleared = stringResource(R.string.settings_key_cleared)
    val keySaved = stringResource(R.string.settings_key_saved)
    val upToDate = stringResource(R.string.settings_up_to_date)
    val checkFailed = stringResource(R.string.settings_check_failed)

    Column(
        Modifier.fillMaxSize().background(HarborColors.Bg).statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(bottom = bottomPadding + 32.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 8.dp))

        // Stremio account
        Section(stringResource(R.string.settings_stremio_title), stringResource(R.string.settings_stremio_subtitle)) {
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
                    PrimaryButton(stringResource(if (syncing) R.string.settings_syncing else R.string.settings_sync), HIcons.Refresh, enabled = !syncing, onClick = {
                        syncing = true
                        scope.launch {
                            runCatching {
                                val addons = graph.stremio.syncAddons()
                                addons to graph.sync.pull()
                            }
                                .onSuccess { (addons, lib) -> toast(syncSummary(addons, lib.continueWatching, lib.added)) }
                                .onFailure { toast(it.message ?: syncFailed) }
                            syncing = false
                        }
                    }, modifier = Modifier.weight(1f))
                    GlassButton(stringResource(R.string.settings_sign_out), null, onClick = { graph.stremio.logout() })
                }
            } else {
                var user by remember { mutableStateOf("") }
                var pass by remember { mutableStateOf("") }
                var busy by remember { mutableStateOf(false) }
                Field(user, { user = it }, stringResource(R.string.settings_email), KeyboardType.Email)
                Spacer(Modifier.height(8.dp))
                Field(pass, { pass = it }, stringResource(R.string.settings_password), KeyboardType.Password, secret = true)
                Spacer(Modifier.height(12.dp))
                PrimaryButton(stringResource(if (busy) R.string.settings_signing_in else R.string.settings_sign_in), null, enabled = !busy && user.isNotBlank() && pass.isNotBlank(), onClick = {
                    busy = true
                    scope.launch {
                        runCatching {
                            graph.stremio.login(user, pass)
                            graph.sync.pull()
                        }
                            .onSuccess { toast(signedIn); pass = "" }
                            .onFailure { toast(it.message ?: signInFailed) }
                        busy = false
                    }
                }, modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.settings_password_note),
                    style = MaterialTheme.typography.bodySmall, color = HarborColors.TextFaint, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        // Addons
        Section(stringResource(R.string.settings_addons_title), stringResource(R.string.settings_addons_subtitle)) {
            settings.addons.forEach { addon ->
                AddonRow(
                    addon,
                    onToggle = { on -> graph.settings.update { s -> s.copy(addons = s.addons.map { if (it.transportUrl == addon.transportUrl) it.copy(enabled = on) else it }) } },
                    onRemove = { graph.settings.update { s -> s.copy(addons = s.addons.filterNot { it.transportUrl == addon.transportUrl }) } },
                )
            }
            if (settings.addons.isEmpty()) {
                Text(stringResource(R.string.settings_addons_empty), style = MaterialTheme.typography.bodySmall, color = HarborColors.TextDim)
            }
            Spacer(Modifier.height(10.dp))
            var url by remember { mutableStateOf("") }
            var adding by remember { mutableStateOf(false) }
            Field(url, { url = it }, stringResource(R.string.settings_addon_url), KeyboardType.Uri)
            Spacer(Modifier.height(8.dp))
            GlassButton(stringResource(if (adding) R.string.settings_adding_addon else R.string.settings_add_addon), HIcons.Add, onClick = {
                if (url.isBlank() || adding) return@GlassButton
                adding = true
                scope.launch {
                    runCatching { graph.addons.fetchManifest(url) }
                        .onSuccess { entry ->
                            graph.settings.update { s -> s.copy(addons = s.addons.filterNot { it.id == entry.id } + entry, addonsSeeded = true) }
                            toast(Strings.get(R.string.settings_addon_added, entry.name))
                            url = ""
                        }
                        .onFailure { toast(it.message ?: addonInvalid) }
                    adding = false
                }
            }, modifier = Modifier.fillMaxWidth())
        }

        // Playback
        Section(stringResource(R.string.settings_playback_title), null) {
            val spanish = stringResource(R.string.settings_lang_spanish)
            val english = stringResource(R.string.settings_lang_english)
            Label(stringResource(R.string.settings_preferred_audio))
            Choice(
                listOf("" to stringResource(R.string.settings_lang_original), "es" to spanish, "en" to english, "ja" to stringResource(R.string.settings_lang_japanese)),
                settings.audioLanguage,
            ) { v ->
                graph.settings.update { it.copy(audioLanguage = v) }
            }
            Spacer(Modifier.height(14.dp))
            Label(stringResource(R.string.settings_subtitles))
            Choice(listOf("" to stringResource(R.string.settings_lang_off), "es" to spanish, "en" to english), settings.subtitleLanguage) { v ->
                graph.settings.update { it.copy(subtitleLanguage = v) }
            }
            Spacer(Modifier.height(14.dp))
            Label(stringResource(R.string.settings_subtitle_size))
            Choice(
                listOf(
                    "0.85" to stringResource(R.string.settings_size_small),
                    "1.0" to stringResource(R.string.settings_size_normal),
                    "1.2" to stringResource(R.string.settings_size_large),
                    "1.45" to stringResource(R.string.settings_size_huge),
                ),
                settings.subtitleScale.toString(),
            ) { v ->
                graph.settings.update { it.copy(subtitleScale = v.toFloat()) }
            }
            Spacer(Modifier.height(14.dp))
            ToggleRow(stringResource(R.string.settings_autoplay_next), settings.autoplayNext) { on -> graph.settings.update { it.copy(autoplayNext = on) } }
            ToggleRow(stringResource(R.string.settings_ask_mobile_data), settings.askOnMobileData) { on -> graph.settings.update { it.copy(askOnMobileData = on) } }
        }

        // Downloads
        Section(stringResource(R.string.settings_downloads_title), stringResource(R.string.settings_downloads_subtitle)) {
            ToggleRow(stringResource(R.string.settings_download_wifi_only), settings.downloadWifiOnly) { on -> graph.settings.update { it.copy(downloadWifiOnly = on) } }
        }

        // New-episode alerts
        Section(stringResource(R.string.settings_alerts_title), stringResource(R.string.settings_alerts_subtitle)) {
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
            ToggleRow(stringResource(R.string.settings_alerts_toggle), settings.episodeAlerts) { on ->
                graph.settings.update { it.copy(episodeAlerts = on) }
                if (on) graph.episodes.seed()
                EpisodeCheckJob.schedule(context, on)
                if (on && !permitted) requestPermission()
            }
            if (settings.episodeAlerts && !permitted) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.settings_alerts_blocked), style = MaterialTheme.typography.bodySmall, color = HarborColors.Warn)
                Spacer(Modifier.height(8.dp))
                GlassButton(stringResource(R.string.settings_alerts_allow), HIcons.Bell, onClick = ::requestPermission, modifier = Modifier.fillMaxWidth())
            }
        }

        // Metadata
        Section(stringResource(R.string.settings_metadata_title), stringResource(R.string.settings_metadata_subtitle)) {
            Label(stringResource(R.string.settings_language))
            Choice(
                listOf(
                    "es-MX" to stringResource(R.string.settings_lang_spanish_latam),
                    "es-ES" to stringResource(R.string.settings_lang_spanish_spain),
                    "en-US" to stringResource(R.string.settings_lang_english),
                ),
                settings.language,
            ) { v ->
                graph.settings.update { it.copy(language = v) }
            }
            Spacer(Modifier.height(14.dp))
            var key by remember { mutableStateOf(settings.tmdbKey) }
            Field(key, { key = it }, stringResource(R.string.settings_tmdb_key), KeyboardType.Ascii, secret = true)
            Spacer(Modifier.height(8.dp))
            GlassButton(stringResource(R.string.settings_save_key), HIcons.Check, onClick = {
                graph.settings.update { it.copy(tmdbKey = key.trim()) }
                toast(if (key.isBlank()) keyCleared else keySaved)
            }, modifier = Modifier.fillMaxWidth())
        }

        if (BuildConfig.SELF_UPDATE) {
            Section(stringResource(R.string.settings_updates_title), stringResource(R.string.settings_updates_subtitle, BuildConfig.VERSION_NAME)) {
                val update by graph.updater.state.collectAsStateWithLifecycle()
                ToggleRow(stringResource(R.string.settings_updates_toggle), settings.checkUpdates) { on -> graph.settings.update { it.copy(checkUpdates = on) } }
                Spacer(Modifier.height(12.dp))
                if (update is UpdateState.Idle) {
                    var checking by remember { mutableStateOf(false) }
                    GlassButton(stringResource(if (checking) R.string.settings_checking else R.string.settings_check_now), HIcons.Refresh, onClick = {
                        if (!checking) {
                            checking = true
                            scope.launch {
                                runCatching { graph.updater.check() }
                                    .onSuccess { if (it == null) toast(upToDate) }
                                    .onFailure { toast(it.message ?: checkFailed) }
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
            stringResource(R.string.settings_version_footer, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = HarborColors.TextFaint,
            modifier = Modifier.padding(top = 24.dp).align(Alignment.CenterHorizontally),
        )
    }
}

private fun syncSummary(addons: Int, continueWatching: Int, added: Int): String = buildList {
    add(Strings.plural(R.plurals.settings_sync_addons, addons, addons))
    if (continueWatching > 0) add(Strings.get(R.string.settings_sync_continue_watching, continueWatching))
    if (added > 0) add(Strings.plural(R.plurals.settings_sync_added, added, added))
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
            val streams = stringResource(R.string.settings_addon_streams)
            val subtitles = stringResource(R.string.settings_addon_subtitles)
            Text(addon.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                addon.resources.joinToString(" · ") { if (it.name == "stream") streams else if (it.name == "subtitles") subtitles else it.name },
                style = MaterialTheme.typography.bodySmall, color = HarborColors.TextFaint, maxLines = 1,
            )
        }
        Icon(
            HIcons.Delete, stringResource(R.string.common_remove), tint = HarborColors.TextFaint,
            modifier = Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onRemove).padding(8.dp),
        )
        Switch(addon.enabled, onToggle, colors = SwitchDefaults.colors(checkedTrackColor = HarborColors.Accent, checkedThumbColor = HarborColors.OnAccent))
    }
}
