package com.synrg.harbor.cast

import android.net.wifi.WifiManager
import com.synrg.harbor.core.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException

/** A TV (or any UPnP media renderer) on the network, with the address that takes its commands. */
data class DlnaDevice(val udn: String, val name: String, val avTransport: String)

/**
 * DLNA, the open standard most smart TVs speak (Samsung, LG, Sony, Philips, Hisense…): SSDP to
 * find them on the Wi-Fi, then UPnP's AVTransport over plain HTTP + SOAP to play, pause, seek and
 * read the position. The TV fetches the video from [LanServer].
 */
class Dlna(private val http: OkHttpClient, private val multicastLock: () -> WifiManager.MulticastLock? = { null }) {
    /** Asks the network for media renderers for [timeoutMs] and describes each one that answers. */
    suspend fun discover(timeoutMs: Int = 3_000): List<DlnaDevice> = coroutineScope {
        val locations = withContext(Dispatchers.IO) { search(timeoutMs) }
        locations.map { async { runCatching { describe(it) }.getOrNull() } }.awaitAll().filterNotNull().distinctBy { it.udn }
    }

    private fun search(timeoutMs: Int): Set<String> {
        // Without it, some phones drop the answers of the multicast search.
        val lock = multicastLock()?.apply { setReferenceCounted(false); acquire() }
        val found = LinkedHashSet<String>()
        try {
            MulticastSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.bind(null)
                socket.soTimeout = 500
                val group = InetAddress.getByName("239.255.255.250")
                listOf("urn:schemas-upnp-org:device:MediaRenderer:1", "urn:schemas-upnp-org:service:AVTransport:1").forEach { st ->
                    val message = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n".toByteArray()
                    repeat(2) { socket.send(DatagramPacket(message, message.size, group, 1900)) }
                }
                val buffer = ByteArray(2048)
                val until = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < until) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val text = String(packet.data, 0, packet.length, Charsets.ISO_8859_1)
                    LOCATION.find(text)?.groupValues?.get(1)?.trim()?.let(found::add)
                }
            }
        } finally {
            runCatching { lock?.release() }
        }
        return found
    }

    /** Reads the device description: its name, id and the AVTransport control address. */
    internal suspend fun describe(location: String): DlnaDevice? {
        val xml = http.newCall(Request.Builder().url(location).build()).await().use { if (it.isSuccessful) it.body.string() else return null }
        val name = tag(xml, "friendlyName") ?: return null
        val udn = tag(xml, "UDN") ?: location
        val base = tag(xml, "URLBase")?.toHttpUrlOrNull() ?: location.toHttpUrlOrNull() ?: return null
        val service = SERVICE.findAll(xml).map { it.value }.firstOrNull { "AVTransport" in (tag(it, "serviceType") ?: "") } ?: return null
        val control = tag(service, "controlURL") ?: return null
        val url = base.resolve(control)?.toString() ?: return null
        return DlnaDevice(udn, unescape(name), url)
    }

    // --- Control ------------------------------------------------------------------------------

    suspend fun load(device: DlnaDevice, url: String, title: String, mime: String, subtitleUrl: String?) {
        val res = buildString {
            append("<res protocolInfo=\"http-get:*:").append(mime)
            append(":DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\">")
            append(escape(url)).append("</res>")
            // Samsung's way of taking an external subtitle; other TVs ignore it.
            if (subtitleUrl != null) append("<sec:CaptionInfoEx sec:type=\"srt\">").append(escape(subtitleUrl)).append("</sec:CaptionInfoEx>")
        }
        val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" xmlns:sec=\"http://www.sec.co.kr/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>${escape(title)}</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class>$res</item></DIDL-Lite>"
        runCatching { action(device, "Stop", "") }
        action(device, "SetAVTransportURI", "<CurrentURI>${escape(url)}</CurrentURI><CurrentURIMetaData>${escape(didl)}</CurrentURIMetaData>")
    }

    suspend fun play(device: DlnaDevice) = action(device, "Play", "<Speed>1</Speed>")

    suspend fun pause(device: DlnaDevice) = action(device, "Pause", "")

    suspend fun stop(device: DlnaDevice) = action(device, "Stop", "")

    suspend fun seek(device: DlnaDevice, ms: Long) = action(device, "Seek", "<Unit>REL_TIME</Unit><Target>${clock(ms)}</Target>")

    /** Position and duration in ms (null when the TV doesn't say). */
    suspend fun position(device: DlnaDevice): Pair<Long, Long>? {
        val xml = action(device, "GetPositionInfo", "")
        val pos = tag(xml, "RelTime")?.let(::millis) ?: return null
        return pos to (tag(xml, "TrackDuration")?.let(::millis) ?: 0L)
    }

    /** PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING or NO_MEDIA_PRESENT. */
    suspend fun state(device: DlnaDevice): String? = tag(action(device, "GetTransportInfo", ""), "CurrentTransportState")

    private suspend fun action(device: DlnaDevice, name: String, args: String): String {
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$name xmlns:u=\"$AV_TRANSPORT\"><InstanceID>0</InstanceID>$args</u:$name></s:Body></s:Envelope>"
        val request = Request.Builder().url(device.avTransport)
            .header("SOAPACTION", "\"$AV_TRANSPORT#$name\"")
            .post(body.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
            .build()
        return withTimeoutOrNull(8_000) {
            http.newCall(request).await().use { res ->
                val text = res.body.string()
                if (!res.isSuccessful) throw IOException("$name: HTTP ${res.code}")
                text
            }
        } ?: throw IOException("$name: no answer")
    }

    companion object {
        private const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
        private val LOCATION = Regex("""(?im)^location:\s*(\S+)""")
        private val SERVICE = Regex("""<service>.*?</service>""", RegexOption.DOT_MATCHES_ALL)

        private fun tag(xml: String, name: String): String? =
            Regex("""<(?:\w+:)?$name(?:\s[^>]*)?>(.*?)</(?:\w+:)?$name>""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()

        private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        private fun unescape(text: String) = text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")

        /** "1:02:03" for UPnP. */
        fun clock(ms: Long): String {
            val s = ms / 1000
            return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        }

        /** "1:02:03" or "1:02:03.500" in ms; NOT_IMPLEMENTED gives null. */
        fun millis(text: String): Long? {
            val parts = text.substringBefore('.').split(':').mapNotNull { it.toLongOrNull() }
            if (parts.size != 3) return null
            return (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
        }
    }
}
