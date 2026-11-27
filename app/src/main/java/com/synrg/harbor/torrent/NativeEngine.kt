package com.synrg.harbor.torrent

/** JNI surface of the Rust side (native/): it only starts librqbit and its HTTP API. */
object NativeEngine {
    val loaded: Boolean by lazy { runCatching { System.loadLibrary("harbor_torrent") }.isSuccess }

    /** Starts the session (idempotent). Returns the HTTP API port on 127.0.0.1, or -1. */
    @JvmStatic external fun start(outputDir: String, persistenceDir: String): Int

    @JvmStatic external fun lastError(): String?
}

/** The torrent engine on Android: librqbit built into the app (scripts/build-native.ps1). */
object AndroidTorrentBackend : TorrentBackend {
    override val available: Boolean get() = NativeEngine.loaded

    override fun start(outputDir: String, persistenceDir: String): Int = NativeEngine.start(outputDir, persistenceDir)

    override fun lastError(): String? = NativeEngine.lastError()
}
