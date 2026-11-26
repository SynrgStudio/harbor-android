package com.synrg.harbor

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.synrg.harbor.core.AppGraph
import com.synrg.harbor.core.Shortcuts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath

class HarborApp : Application(), SingletonImageLoader.Factory {
    val graph: AppGraph by lazy { AppGraph(this) }

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        // Warm the stores off the main thread so the first frame never waits on disk.
        graph.scope.launch(Dispatchers.IO) {
            graph.settings
            graph.progress
            graph.watchlist
        }
        // Launcher shortcuts follow "Seguir viendo" (only when the titles in it change).
        graph.scope.launch {
            graph.progress.inProgress
                .map { it.take(4) }
                .distinctUntilChanged { a, b -> a.map { it.key } == b.map { it.key } }
                .debounce(2_000)
                .collectLatest { runCatching { Shortcuts.update(this@HarborApp, it) } }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { graph.imageHttp })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("images").toOkioPath())
                    .maxSizeBytes(400L * 1024 * 1024)
                    .build()
            }
            .crossfade(160)
            .build()
}

val Context.graph: AppGraph get() = (applicationContext as HarborApp).graph
