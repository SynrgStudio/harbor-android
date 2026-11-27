package com.synrg.harbor.core

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.synrg.harbor.MainActivity
import com.synrg.harbor.R
import com.synrg.harbor.data.Img
import com.synrg.harbor.data.ProgressEntry
import com.synrg.harbor.data.MediaType

/** Where a notification or a launcher shortcut wants the app to go. */
sealed interface AppLink {
    data class Open(val type: MediaType, val id: Int, val season: Int? = null) : AppLink
    data class Resume(val progressKey: String) : AppLink
    data object Downloads : AppLink
}

/** Long-press on the app icon: what is in progress, straight back into the player. */
object Shortcuts {
    private const val MAX = 4

    suspend fun update(context: Context, entries: List<ProgressEntry>) {
        val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceIn(0, MAX)
        val shortcuts = entries.take(max).mapIndexed { rank, e ->
            val season = e.season
            val number = e.episode
            val episode = if (season != null && number != null) " " + Strings.get(R.string.common_episode_code, season, number) else ""
            ShortcutInfoCompat.Builder(context, "resume:${e.key}")
                // Short: the title without its subtitle ("Iron Man - Armored Adventures" -> "Iron Man").
                .setShortLabel(e.title.substringBefore(" - ").substringBefore(": ").take(25))
                .setLongLabel(Strings.get(R.string.app_shortcut_long, e.title + episode).take(45))
                .setIcon(icon(context, e))
                .setIntent(
                    Intent(context, MainActivity::class.java)
                        .setAction(MainActivity.ACTION_RESUME)
                        .putExtra(MainActivity.EXTRA_KEY, e.key),
                )
                .setRank(rank)
                .build()
        }
        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
    }

    /** The poster, cut to a square for the launcher's mask; the app icon when it cannot load. */
    private suspend fun icon(context: Context, entry: ProgressEntry): IconCompat {
        val url = Img.poster(entry.poster, "w185") ?: return IconCompat.createWithResource(context, R.mipmap.ic_launcher)
        val bitmap = runCatching {
            context.imageLoader.execute(ImageRequest.Builder(context).data(url).allowHardware(false).build()).image?.toBitmap()
        }.getOrNull() ?: return IconCompat.createWithResource(context, R.mipmap.ic_launcher)
        val side = minOf(bitmap.width, bitmap.height)
        val square = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 3, side, side)
        return IconCompat.createWithAdaptiveBitmap(Bitmap.createScaledBitmap(square, 216, 216, true))
    }
}
