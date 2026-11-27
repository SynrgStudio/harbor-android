package com.synrg.harbor.stremio

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
data class StreamsResponse(val streams: List<StremioStream> = emptyList())

@Serializable
data class StremioStream(
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val externalUrl: String? = null,
    val ytId: String? = null,
    val sources: List<String>? = null,
    val behaviorHints: BehaviorHints? = null,
    val subtitles: List<StremioSubtitle>? = null,
)

@Serializable
data class BehaviorHints(
    val bingeGroup: String? = null,
    val notWebReady: Boolean? = null,
    val filename: String? = null,
    val videoSize: Long? = null,
    val proxyHeaders: ProxyHeaders? = null,
)

@Serializable
data class ProxyHeaders(val request: Map<String, String>? = null)

@Serializable
data class SubtitlesResponse(val subtitles: List<StremioSubtitle> = emptyList())

@Immutable
@Serializable
data class StremioSubtitle(val id: String? = null, val url: String, val lang: String? = null)
