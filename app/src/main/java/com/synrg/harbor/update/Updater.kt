package com.synrg.harbor.update

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.runtime.Immutable
import androidx.core.content.IntentCompat
import com.synrg.harbor.BuildConfig
import com.synrg.harbor.R
import com.synrg.harbor.core.HttpException
import com.synrg.harbor.core.Strings
import com.synrg.harbor.core.await
import com.synrg.harbor.data.SettingsRepository
import com.synrg.harbor.graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

@Immutable
data class Release(val version: String, val notes: String, val apkUrl: String, val size: Long, val sha256: String?)

sealed interface UpdateState {
    data object Idle : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    /** Downloaded and verified; Android needs "install unknown apps" allowed for Harbor first. */
    data class NeedsPermission(val release: Release) : UpdateState
    /** Handed to Android's installer, which asks for confirmation. */
    data class Installing(val release: Release) : UpdateState
    data class Failed(val release: Release?, val message: String) : UpdateState
}

/**
 * Updates from GitHub Releases, where scripts/release.ps1 publishes: finds a newer version, downloads
 * the APK for this device's ABI, checks its SHA-256 against the one GitHub reports and installs it
 * with PackageInstaller. Same signing key, so it installs over the current version keeping its data.
 */
class Updater(
    private val app: Application,
    private val http: OkHttpClient,
    private val json: Json,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    private val dir = File(app.cacheDir, "updates")
    @Volatile private var lastCheck = 0L
    private var job: Job? = null

    val enabled: Boolean get() = BuildConfig.SELF_UPDATE && settings.current.checkUpdates

    /** On app start: at most every 6 h (GitHub allows 60 unauthenticated calls an hour per IP). */
    fun checkInBackground() {
        if (!enabled || System.currentTimeMillis() - lastCheck < 6 * 60 * 60 * 1000L) return
        scope.launch { runCatching { check() } }
    }

    /** Returns the newer release, or null if this is the latest. */
    suspend fun check(): Release? {
        lastCheck = System.currentTimeMillis()
        val request = Request.Builder().url(LATEST)
            .header("Accept", "application/vnd.github+json")
            .cacheControl(CacheControl.FORCE_NETWORK)
            .build()
        val latest = http.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw HttpException(response.code, Strings.get(R.string.update_error_github, response.code))
            withContext(Dispatchers.Default) { json.decodeFromString(GithubRelease.serializer(), response.body.string()) }
        }
        val version = latest.tag.removePrefix("v")
        if (!isNewer(version, BuildConfig.VERSION_NAME)) {
            if (_state.value is UpdateState.Available) _state.value = UpdateState.Idle
            withContext(Dispatchers.IO) { dir.deleteRecursively() } // leftovers of an installed update
            return null
        }
        val asset = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi -> latest.assets.firstOrNull { it.name.endsWith("-$abi.apk") } }
            ?: return null
        val release = Release(version, latest.body.orEmpty(), asset.url, asset.size, asset.digest?.removePrefix("sha256:"))
        val busy = _state.value.let { it is UpdateState.Downloading || it is UpdateState.Installing || it is UpdateState.NeedsPermission }
        if (!busy) _state.value = UpdateState.Available(release)
        return release
    }

    fun dismiss(release: Release) {
        settings.update { it.copy(dismissedUpdate = release.version) }
        _state.value = UpdateState.Idle
    }

    fun start(release: Release) {
        if (job?.isActive == true) return
        job = scope.launch {
            runCatching {
                val file = download(release)
                if (app.packageManager.canRequestPackageInstalls()) install(release, file)
                else _state.value = UpdateState.NeedsPermission(release)
            }.onFailure { _state.value = UpdateState.Failed(release, it.message ?: Strings.get(R.string.update_error_download)) }
        }
    }

    /** The system screen where Harbor gets "install unknown apps"; [resume] continues on the way back. */
    fun permissionIntent(): Intent =
        Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Called when the app comes back to the foreground. */
    fun resume() {
        val state = _state.value as? UpdateState.NeedsPermission ?: return
        if (!app.packageManager.canRequestPackageInstalls()) return
        scope.launch {
            runCatching { install(state.release, apkFile(state.release)) }
                .onFailure { _state.value = UpdateState.Failed(state.release, it.message ?: Strings.get(R.string.update_error_install)) }
        }
    }

    private fun apkFile(release: Release) = File(dir, "Harbor-${release.version}.apk")

    private suspend fun download(release: Release): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val target = apkFile(release)
        val partial = File(dir, target.name + ".part")
        _state.value = UpdateState.Downloading(release, 0f)
        val digest = MessageDigest.getInstance("SHA-256")
        http.newCall(Request.Builder().url(release.apkUrl).cacheControl(CacheControl.FORCE_NETWORK).build()).await().use { response ->
            if (!response.isSuccessful) throw IOException(Strings.get(R.string.update_error_download_status, response.code))
            val total = response.body.contentLength().takeIf { it > 0 } ?: release.size
            response.body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = -1
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        val percent = (done * 100 / total).toInt()
                        if (percent != shown) {
                            shown = percent
                            _state.value = UpdateState.Downloading(release, (done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (release.sha256 != null && !hex.equals(release.sha256, ignoreCase = true)) {
            partial.delete()
            throw IOException(Strings.get(R.string.update_error_corrupt))
        }
        target.delete()
        partial.renameTo(target)
        target
    }

    private suspend fun install(release: Release, file: File) = withContext(Dispatchers.IO) {
        if (!file.exists()) throw IOException(Strings.get(R.string.update_error_file_gone))
        _state.value = UpdateState.Installing(release)
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(file.length())
            // Once Harbor installed itself, Android 12+ can update it without asking again.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(app, sessionId, Intent(app, UpdateReceiver::class.java), flags)
            session.commit(callback.intentSender)
        }
    }

    internal fun onInstallStatus(status: Int, message: String?, confirm: Intent?) {
        val release = (_state.value as? UpdateState.Installing)?.release
        when (status) {
            // Android's own "update this app?" dialog; Harbor is in front, since the user just tapped.
            PackageInstaller.STATUS_PENDING_USER_ACTION -> confirm?.let { app.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            PackageInstaller.STATUS_SUCCESS -> _state.value = UpdateState.Idle
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = release?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            else -> _state.value = UpdateState.Failed(release, message ?: Strings.get(R.string.update_error_android))
        }
    }

    companion object {
        private const val LATEST = "https://api.github.com/repos/SynrgStudio/harbor-android/releases/latest"

        /** "0.4.2" > "0.4.1"; suffixes after "-" are ignored. */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate)
            val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}

/** Receives PackageInstaller's answers for the update session. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.graph.updater.onInstallStatus(
            intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE),
            intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java),
        )
    }
}

@Serializable
private data class GithubRelease(
    @SerialName("tag_name") val tag: String,
    val body: String? = null,
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
private data class GithubAsset(
    val name: String,
    val size: Long = 0,
    val digest: String? = null,
    @SerialName("browser_download_url") val url: String,
)
