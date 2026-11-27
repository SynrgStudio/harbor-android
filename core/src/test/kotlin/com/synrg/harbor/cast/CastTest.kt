package com.synrg.harbor.cast

import com.synrg.harbor.player.SubtitleCue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.File
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

class CastTest {
    private val http = OkHttpClient()
    private val servers = mutableListOf<LanServer>()

    private fun server() = LanServer(http).apply { hostOverride = "127.0.0.1" }.also(servers::add)

    @After
    fun stop() = servers.forEach { it.stop() }

    private val video = File.createTempFile("cast", ".mp4").apply {
        deleteOnExit()
        writeBytes(ByteArray(1000) { it.toByte() })
    }

    @Test
    fun servesRangesOfAFile() {
        val url = server().publish(LanServer.Item.LocalFile(video, "video/mp4"), "mp4")!!
        http.newCall(Request.Builder().url(url).header("Range", "bytes=100-199").build()).execute().use {
            assertEquals(206, it.code)
            assertEquals("bytes 100-199/1000", it.header("Content-Range"))
            assertEquals("Streaming", it.header("transferMode.dlna.org"))
            val body = it.body.bytes()
            assertEquals(100, body.size)
            assertEquals(100.toByte(), body[0])
        }
        http.newCall(Request.Builder().url(url).build()).execute().use {
            assertEquals(200, it.code)
            assertEquals(1000, it.body.bytes().size)
        }
    }

    @Test
    fun servesNothingElse() {
        val url = server().publish(LanServer.Item.LocalFile(video, "video/mp4"), "mp4")!!
        val other = url.substringBeforeLast('/') + "/0123456789abcdef.mp4"
        http.newCall(Request.Builder().url(other).build()).execute().use { assertEquals(404, it.code) }
    }

    @Test
    fun proxiesAnotherServer() {
        val upstream = server().publish(LanServer.Item.LocalFile(video, "video/mp4"), "mp4")!!
        val url = server().publish(LanServer.Item.Upstream(upstream, mapOf("User-Agent" to "test"), "video/x-matroska"), "mkv")!!
        http.newCall(Request.Builder().url(url).header("Range", "bytes=990-").build()).execute().use {
            assertEquals(206, it.code)
            assertEquals("video/x-matroska", it.header("Content-Type"))
            assertEquals(10, it.body.bytes().size)
        }
    }

    @Test
    fun rangeHeaders() {
        assertEquals(0L to 999L, LanServer.parseRange("bytes=0-", 1000))
        assertEquals(500L to 999L, LanServer.parseRange("bytes=-500", 1000))
        assertEquals(10L to 20L, LanServer.parseRange("bytes=10-20", 1000))
        assertEquals(null, LanServer.parseRange(null, 1000))
    }

    @Test
    fun subtitlesForTvs() {
        val cues = listOf(SubtitleCue(1_500, 3_250, "Hi"))
        assertEquals("WEBVTT\n\n00:00:01.500 --> 00:00:03.250\nHi\n\n", SubtitleText.vtt(cues))
        assertEquals("1\n00:00:01,500 --> 00:00:03,250\nHi\n\n", SubtitleText.srt(cues))
    }

    @Test
    fun clockForUpnp() {
        assertEquals("1:02:03", Dlna.clock(3_723_000))
        assertEquals(3_723_000L, Dlna.millis("1:02:03.250"))
        assertEquals(null, Dlna.millis("NOT_IMPLEMENTED"))
    }

    /** A TV that answers like a real one: a description, then SOAP on its control address. */
    private class FakeRenderer {
        val socket = ServerSocket(0)
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val base = "http://127.0.0.1:${socket.localPort}"

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    client.use { c ->
                        val input = BufferedInputStream(c.getInputStream())
                        val head = StringBuilder()
                        while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
                        val length = Regex("(?i)content-length: (\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
                        val body = String(ByteArray(length).also { var n = 0; while (n < length) n += input.read(it, n, length - n) })
                        requests += head.toString() + body
                        val reply = when {
                            head.startsWith("GET /desc.xml") -> DESCRIPTION
                            "GetPositionInfo" in head -> "<s:Envelope><s:Body><u:GetPositionInfoResponse><RelTime>0:01:05</RelTime><TrackDuration>1:30:00</TrackDuration></u:GetPositionInfoResponse></s:Body></s:Envelope>"
                            "GetTransportInfo" in head -> "<s:Envelope><s:Body><u:GetTransportInfoResponse><CurrentTransportState>PLAYING</CurrentTransportState></u:GetTransportInfoResponse></s:Body></s:Envelope>"
                            else -> "<s:Envelope><s:Body/></s:Envelope>"
                        }.toByteArray()
                        c.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: ${reply.size}\r\nConnection: close\r\n\r\n".toByteArray() + reply)
                    }
                }
            }
        }

        companion object {
            val DESCRIPTION = """
                <?xml version="1.0"?>
                <root xmlns="urn:schemas-upnp-org:device-1-0"><device>
                  <friendlyName>Living room TV &amp; sound</friendlyName>
                  <UDN>uuid:1234</UDN>
                  <serviceList>
                    <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/rc</controlURL></service>
                    <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/ctl</controlURL></service>
                  </serviceList>
                </device></root>
            """.trimIndent()
        }
    }

    @Test
    fun controlsADlnaTv() = runBlocking {
        val tv = FakeRenderer()
        val dlna = Dlna(http)
        val device = dlna.describe(tv.base + "/desc.xml")
        assertNotNull(device)
        assertEquals("Living room TV & sound", device!!.name)
        assertEquals("uuid:1234", device.udn)
        assertEquals(tv.base + "/ctl", device.avTransport)

        dlna.load(device, "http://192.168.1.5:4000/abc.mp4?x=1&y=2", "Tears & Steel", "video/mp4", "http://192.168.1.5:4000/s.srt")
        val set = tv.requests.first { "SetAVTransportURI" in it }
        assertTrue(set, "<CurrentURI>http://192.168.1.5:4000/abc.mp4?x=1&amp;y=2</CurrentURI>" in set)
        assertTrue(set, "&lt;dc:title&gt;Tears &amp;amp; Steel&lt;/dc:title&gt;" in set)
        assertTrue(set, "sec:CaptionInfoEx" in set)
        assertTrue(set, "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"" in set)

        assertEquals(65_000L to 5_400_000L, dlna.position(device))
        assertEquals("PLAYING", dlna.state(device))
        tv.socket.close()
    }
}
