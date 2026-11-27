package com.synrg.harbor.stremio

import com.synrg.harbor.core.CoreStrings
import com.synrg.harbor.core.CoreText
import com.synrg.harbor.core.await
import com.synrg.harbor.data.AddonEntry
import com.synrg.harbor.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Stremio account: the same login Harbor desktop links. Brings over your addons and holds the library calls. */
class StremioAccount(
    private val http: OkHttpClient,
    private val json: Json,
    private val settings: SettingsRepository,
    private val addons: AddonRepository,
) {
    private val api = "https://api.strem.io/api"

    private suspend fun call(method: String, body: JsonObject): JsonObject =
        callRaw(method, body) as? JsonObject ?: throw IOException(CoreStrings.get(CoreText.StremioUnexpectedResponse))

    private suspend fun callRaw(method: String, body: JsonObject): JsonElement {
        val request = Request.Builder()
            .url("$api/$method")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val text = http.newCall(request).await().use { withContext(Dispatchers.IO) { it.body.string() } }
        val obj = json.parseToJsonElement(text).jsonObject
        obj["error"]?.let { err ->
            val message = (err as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: err.toString()
            throw IOException(message)
        }
        return obj["result"] ?: throw IOException(CoreStrings.get(CoreText.StremioUnexpectedResponse))
    }

    private fun requireAuth(): String = settings.current.stremioAuthKey ?: throw IOException(CoreStrings.get(CoreText.StremioNotSignedIn))

    /** Library items ("libraryItem" collection): all of them, or just [ids]. */
    suspend fun libraryGet(ids: List<String> = emptyList()): List<JsonObject> {
        val result = callRaw(
            "datastoreGet",
            buildJsonObject {
                put("authKey", requireAuth())
                put("collection", "libraryItem")
                putJsonArray("ids") { ids.forEach { add(it) } }
                put("all", ids.isEmpty())
            },
        )
        return (result as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    }

    /** Writes whole library items (the server replaces each one by its _id). */
    suspend fun libraryPut(items: List<JsonObject>) {
        if (items.isEmpty()) return
        callRaw(
            "datastorePut",
            buildJsonObject {
                put("authKey", requireAuth())
                put("collection", "libraryItem")
                put("changes", JsonArray(items))
            },
        )
    }

    suspend fun login(email: String, password: String) {
        val result = call(
            "login",
            buildJsonObject {
                put("type", "Login")
                put("email", email.trim())
                put("password", password)
                put("facebook", false)
            },
        )
        val authKey = result["authKey"]?.jsonPrimitive?.contentOrNull ?: throw IOException(CoreStrings.get(CoreText.StremioNoSession))
        settings.update { it.copy(stremioAuthKey = authKey, stremioEmail = email.trim()) }
        syncAddons()
    }

    /** Replaces the addon list with the account's collection, keeping local on/off switches. */
    suspend fun syncAddons(): Int {
        val authKey = requireAuth()
        val result = call(
            "addonCollectionGet",
            buildJsonObject {
                put("type", "AddonCollectionGet")
                put("authKey", authKey)
                put("update", true)
            },
        )
        val remote: List<AddonEntry> = result["addons"]?.jsonArray.orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val url = o["transportUrl"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val manifest = o["manifest"] as? JsonObject ?: return@mapNotNull null
            addons.parseManifest(url, manifest)
        }.filter { a -> a.resources.any { it.name == "stream" || it.name == "subtitles" } }
        settings.update { s ->
            val enabledById = s.addons.associate { it.id to it.enabled }
            val merged = remote.map { it.copy(enabled = enabledById[it.id] ?: true) }
            val localOnly = s.addons.filter { l -> remote.none { it.id == l.id } }
            s.copy(addons = merged + localOnly, addonsSeeded = true)
        }
        return remote.size
    }

    fun logout() = settings.update { it.copy(stremioAuthKey = null, stremioEmail = null) }
}
