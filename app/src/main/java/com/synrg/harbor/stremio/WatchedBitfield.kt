package com.synrg.harbor.stremio

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Which episodes of a series are watched, the way Stremio stores it in a library item's
 * `state.watched`: `{anchorVideoId}:{anchorLength}:{base64(zlib(bits))}`, one bit per video in
 * [videoIds] order (least significant bit first). The anchor is the last watched video and its
 * position + 1, so a list that grew (new episodes, specials) can be lined up again.
 *
 * [videoIds] must be ordered like Stremio does it: by season, episode, then release date.
 */
class WatchedBitfield private constructor(val videoIds: List<String>, private var bytes: ByteArray) {
    private val positions = HashMap<String, Int>().apply { videoIds.forEachIndexed { i, id -> putIfAbsent(id, i) } }

    operator fun get(videoId: String): Boolean = positions[videoId]?.let(::bit) ?: false

    operator fun set(videoId: String, watched: Boolean) {
        positions[videoId]?.let { setBit(it, watched) }
    }

    val watched: Set<String> get() = videoIds.filterIndexedTo(HashSet()) { i, _ -> bit(i) }

    private fun bit(i: Int): Boolean {
        val index = i / 8
        return index < bytes.size && (bytes[index].toInt() shr (i % 8)) and 1 != 0
    }

    private fun setBit(i: Int, value: Boolean) {
        val index = i / 8
        if (index >= bytes.size) bytes = bytes.copyOf(index + 1)
        val mask = 1 shl (i % 8)
        bytes[index] = (if (value) bytes[index].toInt() or mask else bytes[index].toInt() and mask.inv()).toByte()
    }

    fun serialize(): String {
        val last = (maxOf(videoIds.size, bytes.size * 8) - 1 downTo 0).firstOrNull(::bit) ?: 0
        return "${videoIds.getOrNull(last) ?: "undefined"}:${last + 1}:${pack(bytes)}"
    }

    override fun toString(): String = serialize()

    companion object {
        fun empty(videoIds: List<String>) = WatchedBitfield(videoIds, ByteArray((videoIds.size + 7) / 8))

        /** Reads Stremio's value against the current list of videos; anything unreadable is "nothing watched". */
        fun parse(serialized: String?, videoIds: List<String>): WatchedBitfield {
            if (serialized.isNullOrBlank()) return empty(videoIds)
            // The anchor id may contain ':' itself, so take the last two parts off the end.
            val parts = serialized.split(':')
            if (parts.size < 3) return empty(videoIds)
            val anchorLength = parts[parts.size - 2].toIntOrNull() ?: return empty(videoIds)
            val anchorId = parts.subList(0, parts.size - 2).joinToString(":")
            val old = runCatching { unpack(parts.last()) }.getOrNull() ?: return empty(videoIds)
            val anchorIndex = videoIds.indexOf(anchorId)
            if (anchorIndex < 0) return empty(videoIds) // like Stremio: without the anchor nothing lines up
            val offset = anchorLength - anchorIndex - 1
            val previous = WatchedBitfield(videoIds, old.copyOf(maxOf(old.size, (videoIds.size + 7) / 8)))
            if (offset == 0) return previous
            val shifted = empty(videoIds)
            for (i in videoIds.indices) {
                val before = i + offset
                if (before in videoIds.indices && previous.bit(before)) shifted.setBit(i, true)
            }
            return shifted
        }

        private fun pack(bytes: ByteArray): String {
            val deflater = Deflater(6)
            deflater.setInput(bytes)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(256)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            deflater.end()
            return Base64.getEncoder().encodeToString(out.toByteArray())
        }

        private fun unpack(text: String): ByteArray {
            val inflater = Inflater()
            inflater.setInput(Base64.getDecoder().decode(text))
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(256)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buffer, 0, n)
            }
            inflater.end()
            return out.toByteArray()
        }
    }
}
