package com.synrg.harbor.cast

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * A small HTTP server on the phone's Wi-Fi address, so a TV can fetch what the phone plays: the
 * torrent engine's stream (only reachable on 127.0.0.1), a downloaded file, a link that needs
 * the addon's headers, and subtitles converted for the TV. Only what is published here is
 * served, each item under a random token; nothing else on the phone is reachable.
 */
class LanServer(private val http: OkHttpClient) {
    sealed interface Item {
        val mime: String
        class Upstream(val url: String, val headers: Map<String, String>, override val mime: String) : Item
        class LocalFile(val file: File, override val mime: String) : Item
        class Bytes(val data: ByteArray, override val mime: String) : Item
    }

    private var server: ServerSocket? = null

    /** Tests serve on the loopback address instead of Wi-Fi. */
    internal var hostOverride: String? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "harbor-lan").apply { isDaemon = true } }
    private val items = ConcurrentHashMap<String, Item>()
    private val random = SecureRandom()

    @Synchronized
    private fun ensureStarted(): Int {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(0))
        }
        server = socket
        pool.execute {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                // A TV closing the connection mid-video is normal (it seeks by reconnecting).
                pool.execute { runCatching { handle(client) } }
            }
        }
        return socket.localPort
    }

    /** Serves [item] and returns its address on the Wi-Fi network, or null without one. */
    fun publish(item: Item, extension: String): String? {
        val host = hostOverride ?: wifiAddress() ?: return null
        val port = ensureStarted()
        val token = ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        items[token] = item
        return "http://$host:$port/$token.$extension"
    }

    /** Stops serving everything (casting ended). */
    @Synchronized
    fun stop() {
        items.clear()
        runCatching { server?.close() }
        server = null
    }

    private fun handle(socket: Socket) = socket.use { s ->
        s.soTimeout = 60_000
        val input = BufferedInputStream(s.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0) ?: return
        val token = parts.getOrNull(1)?.substringAfterLast('/')?.substringBefore('.')?.substringBefore('?') ?: ""
        val out = s.getOutputStream()
        if (method == "OPTIONS") {
            respond(out, 204, "No Content", emptyMap())
            return
        }
        val item = items[token]
        if (item == null || (method != "GET" && method != "HEAD")) {
            respond(out, 404, "Not Found", mapOf("Content-Length" to "0"))
            return
        }
        val range = headers["range"]
        val head = method == "HEAD"
        when (item) {
            is Item.Bytes -> serveBytes(out, item, head)
            is Item.LocalFile -> serveFile(out, item, range, head)
            is Item.Upstream -> serveUpstream(out, item, range, head)
        }
    }

    private fun serveBytes(out: OutputStream, item: Item.Bytes, head: Boolean) {
        respond(out, 200, "OK", mapOf("Content-Type" to item.mime, "Content-Length" to item.data.size.toString()))
        if (!head) out.write(item.data)
        out.flush()
    }

    private fun serveFile(out: OutputStream, item: Item.LocalFile, range: String?, head: Boolean) {
        val length = item.file.length()
        val (start, end) = parseRange(range, length) ?: (0L to length - 1)
        val partial = range != null
        val headers = mediaHeaders(item.mime) + mapOf("Content-Length" to (end - start + 1).toString()) +
            (if (partial) mapOf("Content-Range" to "bytes $start-$end/$length") else emptyMap())
        respond(out, if (partial) 206 else 200, if (partial) "Partial Content" else "OK", headers)
        if (head) return
        RandomAccessFile(item.file, "r").use { f ->
            f.seek(start)
            copy(f::read, out, end - start + 1)
        }
        out.flush()
    }

    private fun serveUpstream(out: OutputStream, item: Item.Upstream, range: String?, head: Boolean) {
        val request = Request.Builder().url(item.url).apply {
            item.headers.forEach { (k, v) -> header(k, v) }
            if (range != null) header("Range", range)
            if (head) head()
        }.build()
        http.newCall(request).execute().use { res ->
            val headers = mediaHeaders(item.mime).toMutableMap()
            res.header("Content-Length")?.let { headers["Content-Length"] = it }
            res.header("Content-Range")?.let { headers["Content-Range"] = it }
            respond(out, res.code, res.message.ifEmpty { "OK" }, headers)
            if (head) return
            val body = res.body.byteStream()
            copy(body::read, out, Long.MAX_VALUE)
            out.flush()
        }
    }

    private fun copy(read: (ByteArray, Int, Int) -> Int, out: OutputStream, limit: Long) {
        val buffer = ByteArray(64 * 1024)
        var left = limit
        while (left > 0) {
            val n = read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buffer, 0, n)
            left -= n
        }
    }

    /** What TVs expect with a video: byte ranges, and DLNA's "this can be streamed and seeked" flags. */
    private fun mediaHeaders(mime: String) = mapOf(
        "Content-Type" to mime,
        "Accept-Ranges" to "bytes",
        "transferMode.dlna.org" to "Streaming",
        "contentFeatures.dlna.org" to "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000",
    )

    private fun respond(out: OutputStream, code: Int, message: String, headers: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 $code $message\r\n")
        // The Chromecast receiver is a web page: subtitles need CORS.
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Headers: *\r\n")
        sb.append("Connection: close\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }

    companion object {
        /** "bytes=100-" or "bytes=100-200" within [length]. */
        fun parseRange(header: String?, length: Long): Pair<Long, Long>? {
            val spec = header?.removePrefix("bytes=")?.takeIf { it != header } ?: return null
            val first = spec.substringBefore(',')
            val a = first.substringBefore('-').toLongOrNull()
            val b = first.substringAfter('-').toLongOrNull()
            return when {
                a != null -> a.coerceIn(0, length - 1) to (b ?: (length - 1)).coerceIn(a, length - 1)
                b != null -> (length - b).coerceAtLeast(0) to length - 1
                else -> null
            }
        }

        /** The phone's IPv4 address on Wi-Fi (or Ethernet), which the TV can reach. */
        fun wifiAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()
    }
}
