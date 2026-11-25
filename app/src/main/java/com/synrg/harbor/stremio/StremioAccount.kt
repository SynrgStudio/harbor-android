package com.synrg.harbor.stremio

import com.synrg.harbor.core.await
import com.synrg.harbor.data.AddonEntry
import com.synrg.harbor.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Stremio account: the same login Harbor desktop links, used here to bring over your addons. */
class StremioAccount(
    private val http: OkHttpClient,
    private val json: Json,
    private val settings: SettingsRepository,
    private val addons: AddonRepository,
) {
    private val api = "https://api.strem.io/api"

    private suspend fun call(method: String, body: JsonObject): JsonObject {
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
        return obj["result"]?.jsonObject ?: throw IOException("Respuesta inesperada de Stremio")
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
        val authKey = result["authKey"]?.jsonPrimitive?.contentOrNull ?: throw IOException("Stremio no devolvió sesión")
        settings.update { it.copy(stremioAuthKey = authKey, stremioEmail = email.trim()) }
        syncAddons()
    }

    /** Replaces the addon list with the account's collection, keeping local on/off switches. */
    suspend fun syncAddons(): Int {
        val authKey = settings.current.stremioAuthKey ?: throw IOException("No hay sesión de Stremio")
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
