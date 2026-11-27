package com.synrg.harbor.data

import com.synrg.harbor.core.JsonStore
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.util.Locale

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
    /** TMDB metadata language. The defaults below follow the phone on first run; saved settings keep their value. */
    val language: String = defaultMetadataLanguage(),
    val region: String = defaultRegion(),
    val stremioAuthKey: String? = null,
    val stremioEmail: String? = null,
    val addons: List<AddonEntry> = emptyList(),
    val addonsSeeded: Boolean = false,
    /** "Not now" on the Home card that offers to link the Stremio account. */
    val stremioPromptDismissed: Boolean = false,
    /** Look for new versions on GitHub when the app opens. */
    val checkUpdates: Boolean = true,
    /** The version whose offer got "Not now": Home stays quiet until a newer one. */
    val dismissedUpdate: String? = null,
    /** ISO 639-1 code, or "" for the file's default track. */
    val audioLanguage: String = "",
    /** ISO 639-1 code, or "" for no subtitles. */
    val subtitleLanguage: String = defaultSubtitleLanguage(),
    val subtitleScale: Float = 1f,
    /** Subtitle look (player/SubtitleStyle.kt): text color, edge, font and height on screen. */
    val subtitleColor: String = "white",
    val subtitleEdge: String = "outline",
    val subtitleFont: String = "medium",
    val subtitlePosition: String = "normal",
    /** Sound in the player (player/SoundEffects.kt): loud and quiet parts evened out, voices lifted. */
    val nightMode: Boolean = false,
    val dialogueBoost: Boolean = false,
    /** Volume past the phone's maximum, in percent: 100 (off), 150 or 200. */
    val volumeBoost: Int = 100,
    val autoplayNext: Boolean = true,
    /** Ask before streaming on mobile data or another metered connection. */
    val askOnMobileData: Boolean = true,
    /** Notify when a followed series has a new episode. */
    val episodeAlerts: Boolean = true,
    /** Android's notification permission was already asked for once. */
    val notificationPromptShown: Boolean = false,
    /** Downloads wait for Wi-Fi (or another unmetered network). */
    val downloadWifiOnly: Boolean = true,
    /** "Watch now" plays the best stream by the rules below instead of opening the list (stremio/StreamRules.kt). */
    val autoPlayBest: Boolean = false,
    /** Highest quality the automatic choices take: "4K", "1080p" or "720p". */
    val autoMaxQuality: String = "1080p",
    /** Largest file the automatic choices take, in GB; 0 is any size. */
    val autoMaxSizeGb: Int = 0,
    /** An episode watched from a download, once finished, is deleted and the next one downloads. */
    val smartDownloads: Boolean = false,
    /** Where new downloads go: "internal", or "sd" for a removable card when there is one. */
    val downloadStorage: String = "internal",
)

/**
 * Spanish phones get Latin American Spanish metadata (Spain's own in Spain), Portuguese phones
 * Brazilian Portuguese (Portugal's own in Portugal); everyone else, English.
 */
private fun defaultMetadataLanguage(): String {
    val locale = Locale.getDefault()
    return when (locale.language) {
        "es" -> if (locale.country == "ES") "es-ES" else "es-MX"
        "pt" -> if (locale.country == "PT") "pt-PT" else "pt-BR"
        else -> "en-US"
    }
}

private fun defaultRegion(): String = Locale.getDefault().country.ifEmpty { "US" }

/** Spanish and Portuguese speakers get subtitles in their language by default; others start with them off. */
private fun defaultSubtitleLanguage(): String = Locale.getDefault().language.takeIf { it == "es" || it == "pt" } ?: ""

class SettingsRepository(private val store: JsonStore<Settings>) {
    val state: StateFlow<Settings> = store.state
    val current: Settings get() = store.value
    fun update(transform: (Settings) -> Settings) = store.update(transform)
}
