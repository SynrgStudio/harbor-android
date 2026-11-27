package com.synrg.harbor.downloads

import android.app.Application
import android.content.Intent
import android.os.Environment
import androidx.core.content.ContextCompat
import java.io.File

/** Downloads on Android: app storage, the SD card's app folder, and [DownloadService] running the queue. */
class AndroidDownloadHost(private val app: Application) : DownloadHost {
    override val internalDir: File get() = File(app.filesDir, "downloads")

    /** The app's own folder on a removable card: no permission needed, and Android deletes it with the app. */
    override fun removableDir(): File? = app.getExternalFilesDirs(null).drop(1).firstOrNull { folder ->
        folder != null && runCatching {
            Environment.isExternalStorageRemovable(folder) && Environment.getExternalStorageState(folder) == Environment.MEDIA_MOUNTED
        }.getOrDefault(false)
    }?.let { File(it, "downloads").apply { mkdirs() } }

    override fun startQueue() {
        ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
    }
}
