package com.synrg.harbor.core

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.Response
import com.synrg.harbor.tmdb.TmdbAddon
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HttpException(val code: Int, message: String) : IOException(message)

/** Cancellable await: cancelling the coroutine cancels the socket too. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, _, _ -> response.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

/**
 * TMDB, catalogs and addon manifests rarely change: let OkHttp serve repeats from disk for a while.
 * Addon metadata pages keep the server's own caching (days for movies, a day for running series).
 */
object ApiCacheInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (request.method != "GET" || !response.isSuccessful) return response
        val maxAge = when {
            request.url.host == "api.themoviedb.org" -> 60 * 60
            request.url.host == TmdbAddon.HOST && "/catalog/" in request.url.encodedPath -> 3 * 60 * 60
            request.url.encodedPath.endsWith("/manifest.json") -> 6 * 60 * 60
            // Sagas and filmographies: they change on the scale of months.
            request.url.host == "query.wikidata.org" -> 7 * 24 * 60 * 60
            else -> return response
        }
        return response.newBuilder()
            .header("Cache-Control", "public, max-age=$maxAge")
            .removeHeader("Pragma")
            .build()
    }
}
