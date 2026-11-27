package com.synrg.harbor.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.synrg.harbor.MainActivity
import com.synrg.harbor.R
import com.synrg.harbor.core.Strings
import com.synrg.harbor.data.Img
import com.synrg.harbor.data.ProgressEntry
import com.synrg.harbor.graph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Home screen widget with up to three titles from Continue watching; a tap goes straight back
 * into the player, like the launcher shortcuts. Plain RemoteViews: no widget library in the APK.
 * It refreshes when what is in progress changes (HarborApp) and when Android asks.
 */
class ContinueWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        context.graph.scope.launch {
            try {
                render(context, manager, ids)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val rows = listOf(
            Row(R.id.row0, R.id.still0, R.id.title0, R.id.sub0, R.id.progress0),
            Row(R.id.row1, R.id.still1, R.id.title1, R.id.sub1, R.id.progress1),
            Row(R.id.row2, R.id.still2, R.id.title2, R.id.sub2, R.id.progress2),
        )

        private class Row(val root: Int, val still: Int, val title: Int, val sub: Int, val progress: Int)

        /** Redraws every Harbor widget on the home screen (none placed: nothing to do). */
        suspend fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ContinueWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
        }

        private suspend fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val entries = context.graph.progress.inProgress.first().take(rows.size)
            val views = RemoteViews(context.packageName, R.layout.widget_continue)
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.header, open)
            views.setViewVisibility(R.id.empty, if (entries.isEmpty()) View.VISIBLE else View.GONE)
            rows.forEachIndexed { i, row ->
                val entry = entries.getOrNull(i)
                if (entry == null) {
                    views.setViewVisibility(row.root, View.GONE)
                    return@forEachIndexed
                }
                views.setViewVisibility(row.root, View.VISIBLE)
                views.setTextViewText(row.title, entry.title)
                views.setTextViewText(row.sub, subtitle(entry))
                views.setProgressBar(row.progress, 100, (entry.fraction * 100).toInt(), false)
                image(context, entry)?.let { views.setImageViewBitmap(row.still, it) }
                    ?: views.setImageViewResource(row.still, R.drawable.widget_row_bg)
                val resume = Intent(context, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_RESUME)
                    .putExtra(MainActivity.EXTRA_KEY, entry.key)
                    // One PendingIntent per title: the data makes each one distinct.
                    .setData(Uri.parse("harbor://resume/" + Uri.encode(entry.key)))
                views.setOnClickPendingIntent(
                    row.root,
                    PendingIntent.getActivity(context, i + 1, resume, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                )
            }
            manager.updateAppWidget(ids, views)
        }

        private fun subtitle(entry: ProgressEntry): String {
            val left = if (entry.remainingMin > 0) Strings.get(R.string.continue_remaining, entry.remainingMin) else Strings.get(R.string.continue_almost_done)
            val season = entry.season
            val episode = entry.episode
            return if (season != null && episode != null) {
                Strings.get(R.string.common_episode_code, season, episode) + "  ·  " + left
            } else left
        }

        /** The still (or backdrop), small and with rounded corners: widgets get a limited amount of memory. */
        private suspend fun image(context: Context, entry: ProgressEntry): Bitmap? {
            val url = Img.still(entry.still, "w300") ?: Img.backdrop(entry.backdrop, "w300") ?: return null
            val source = withTimeoutOrNull(8_000) {
                runCatching {
                    context.imageLoader.execute(ImageRequest.Builder(context).data(url).allowHardware(false).build()).image?.toBitmap()
                }.getOrNull()
            } ?: return null
            val width = 264
            val height = 150
            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val scale = maxOf(width.toFloat() / source.width, height.toFloat() / source.height)
            val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(
                    android.graphics.Matrix().apply {
                        setScale(scale, scale)
                        postTranslate((width - source.width * scale) / 2, (height - source.height * scale) / 2)
                    },
                )
            }
            Canvas(out).drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 18f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
            return out
        }
    }
}
