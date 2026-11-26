package com.synrg.harbor.data

import com.synrg.harbor.core.JsonStore
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
data class AddonResource(
    val name: String,
    val types: List<String>? = null,
    val idPrefixes: List<String>? = null,
)

/** An installed Stremio addon. [transportUrl] is the manifest URL. */
@Serializable
data class AddonEntry(
    val transportUrl: String,
    val id: String,
    val name: String,
    val logo: String? = null,
    val resources: List<AddonResource> = emptyList(),
    val types: List<String> = emptyList(),
    val idPrefixes: List<String>? = null,
    val enabled: Boolean = true,
) {
    val baseUrl: String get() = transportUrl.substringBefore("?").removeSuffix("/manifest.json").removeSuffix("/")

    fun supports(resource: String, type: String, id: String): Boolean {
        val res = resources.firstOrNull { it.name == resource } ?: return false
        val resTypes = res.types ?: types
        if (type !in resTypes) return false
        val prefixes = res.idPrefixes ?: idPrefixes
        return prefixes.isNullOrEmpty() || prefixes.any { id.startsWith(it) }
    }
}

@Serializable
data class Settings(
    /** Optional personal TMDB key; without one, metadata comes from the keyless TMDB addon. */
    val tmdbKey: String = "",
    val language: String = "es-MX",
    val region: String = "AR",
    val stremioAuthKey: String? = null,
    val stremioEmail: String? = null,
    val addons: List<AddonEntry> = emptyList(),
    val addonsSeeded: Boolean = false,
    /** "Ahora no" on the home card that offers to link the Stremio account. */
    val stremioPromptDismissed: Boolean = false,
    /** Look for new versions on GitHub when the app opens. */
    val checkUpdates: Boolean = true,
    /** The version whose offer got "Ahora no": Home stays quiet until a newer one. */
    val dismissedUpdate: String? = null,
    /** ISO 639-1 code, or "" for the file's default track. */
    val audioLanguage: String = "",
    /** ISO 639-1 code, or "" for no subtitles. */
    val subtitleLanguage: String = "es",
    val subtitleScale: Float = 1f,
    val autoplayNext: Boolean = true,
) {}

class SettingsRepository(private val store: JsonStore<Settings>) {
    val state: StateFlow<Settings> = store.state
    val current: Settings get() = store.value
    fun update(transform: (Settings) -> Settings) = store.update(transform)
}
