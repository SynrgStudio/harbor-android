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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath

class HarborApp : Application(), SingletonImageLoader.Factory {
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        // Warm the stores off the main thread so the first frame never waits on disk.
        graph.scope.launch(Dispatchers.IO) {
            graph.settings
            graph.progress
            graph.watchlist
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
