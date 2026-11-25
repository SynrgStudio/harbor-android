package com.synrg.harbor.stremio

import com.synrg.harbor.core.HttpException
import com.synrg.harbor.core.await
import com.synrg.harbor.data.AddonEntry
import com.synrg.harbor.data.AddonResource
import com.synrg.harbor.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Debrid-cached first, CAM last, then quality, then seeders. Addons without seed counts keep
 * their own order within a quality (sortedWith is stable).
 */
private val STREAM_ORDER = compareByDescending<StreamOption> { it.cached }
    .thenBy { "CAM" in it.tags }
    .thenByDescending { it.quality.rank }
    .thenByDescending { it.seeders ?: -1 }

sealed interface AddonStreams {
    val addon: AddonEntry
    data class Loading(override val addon: AddonEntry) : AddonStreams
    data class Loaded(override val addon: AddonEntry, val streams: List<StreamOption>) : AddonStreams
    data class Failed(override val addon: AddonEntry, val message: String) : AddonStreams
}

class AddonRepository(
    private val http: OkHttpClient,
    private val json: Json,
    private val settings: SettingsRepository,
) {
    companion object {
        const val TORRENTIO = "https://torrentio.strem.fun/manifest.json"
        const val OPENSUBTITLES = "https://opensubtitles-v3.strem.io/manifest.json"
    }

    /** Accepts https://…/manifest.json, stremio://…/manifest.json or a bare addon base URL. */
    fun normalizeUrl(raw: String): String {
        var url = raw.trim()
        if (url.startsWith("stremio://")) url = "https://" + url.removePrefix("stremio://")
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        if (!url.substringBefore("?").endsWith("/manifest.json")) url = url.trimEnd('/') + "/manifest.json"
        return url
    }

    private suspend fun getText(url: String): String {
        val response = http.newCall(Request.Builder().url(url).build()).await()
        return response.use {
            if (!it.isSuccessful) throw HttpException(it.code, "El addon respondió ${it.code}")
            withContext(Dispatchers.IO) { it.body.string() }
        }
    }

    suspend fun fetchManifest(rawUrl: String): AddonEntry {
        val url = normalizeUrl(rawUrl)
        val manifest = json.parseToJsonElement(getText(url)).jsonObject
        return parseManifest(url, manifest)
    }

    fun parseManifest(transportUrl: String, manifest: JsonObject): AddonEntry {
        fun JsonElement?.strings(): List<String>? =
            (this as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val types = manifest["types"].strings().orEmpty()
        val prefixes = manifest["idPrefixes"].strings()
        val resources = (manifest["resources"] as? JsonArray).orEmpty().mapNotNull { r ->
            when (r) {
                is JsonPrimitive -> r.contentOrNull?.let { AddonResource(it) }
                is JsonObject -> r["name"]?.jsonPrimitive?.contentOrNull?.let {
                    AddonResource(it, r["types"].strings(), r["idPrefixes"].strings())
                }
                else -> null
            }
        }
        return AddonEntry(
            transportUrl = transportUrl,
            id = manifest["id"]?.jsonPrimitive?.contentOrNull ?: transportUrl,
            name = manifest["name"]?.jsonPrimitive?.contentOrNull ?: "Addon",
            logo = manifest["logo"]?.jsonPrimitive?.contentOrNull,
            resources = resources,
            types = types,
            idPrefixes = prefixes,
        )
    }

    /** First launch with no Stremio account: Torrentio + OpenSubtitles, like a fresh Stremio. */
    suspend fun seedDefaultsIfNeeded() {
        if (settings.current.addonsSeeded) return
        val seeded = coroutineScope {
            listOf(TORRENTIO, OPENSUBTITLES).map { url ->
                async { runCatching { withTimeout(15_000) { fetchManifest(url) } }.getOrNull() }
            }.awaitAll().filterNotNull()
        }
        if (seeded.isEmpty()) return
        settings.update { s ->
            if (s.addonsSeeded) s else s.copy(addons = s.addons + seeded.filter { n -> s.addons.none { it.id == n.id } }, addonsSeeded = true)
        }
    }

    /** Queries every enabled addon in parallel; each one reports as soon as it answers. */
    fun streams(type: String, id: String): Flow<AddonStreams> = channelFlow {
        val addons = settings.current.addons.filter { it.enabled && it.supports("stream", type, id) }
        addons.forEach { send(AddonStreams.Loading(it)) }
        addons.forEach { addon ->
            launch {
                val result = runCatching {
                    withTimeout(25_000) {
                        val body = getText("${addon.baseUrl}/stream/$type/$id.json")
                        val parsed = withContext(Dispatchers.Default) {
                            json.decodeFromString(StreamsResponse.serializer(), body).streams
                                .mapIndexedNotNull { i, s -> StreamParser.parse(addon.name, i, s) }
                                .sortedWith(STREAM_ORDER)
                        }
                        parsed
                    }
                }
                send(
                    result.fold(
                        onSuccess = { AddonStreams.Loaded(addon, it) },
                        onFailure = { AddonStreams.Failed(addon, it.message ?: "Sin respuesta") },
                    ),
                )
            }
        }
    }

    suspend fun subtitles(type: String, id: String, extra: String? = null): List<StremioSubtitle> = coroutineScope {
        settings.current.addons.filter { it.enabled && it.supports("subtitles", type, id) }.map { addon ->
            async {
                withTimeoutOrNull(12_000) {
                    runCatching {
                        val path = if (extra != null) "${addon.baseUrl}/subtitles/$type/$id/$extra.json" else "${addon.baseUrl}/subtitles/$type/$id.json"
                        json.decodeFromString(SubtitlesResponse.serializer(), getText(path)).subtitles
                    }.getOrNull()
                }.orEmpty()
            }
        }.awaitAll().flatten()
    }
}
