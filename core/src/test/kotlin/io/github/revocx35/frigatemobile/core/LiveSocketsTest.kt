package io.github.revocx35.frigatemobile.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class LiveSocketsTest {
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var client: FrigateClient
    private val jwt = "aaa.bbb.ccc"

    @Before fun setUp() {
        val localhost = InetAddress.getByName("localhost").canonicalHostName
        val cert = HeldCertificate.Builder().addSubjectAlternativeName(localhost).addSubjectAlternativeName("localhost").build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server = MockWebServer().apply { useHttps(serverCerts.sslSocketFactory()); start() }
        val session = FrigateSession(ServerAddress.parse(server.url("/").toString())!!, SessionToken("frigate_token", jwt), null)
        http = OkHttpClient.Builder().sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
            .addInterceptor(session.interceptor).build()
        client = FrigateClient(session, http)
    }

    @After fun tearDown() = server.close()

    @Test fun mseNegotiatesCodecsThenStreamsBinary() = runBlocking {
        val received = LinkedBlockingQueue<String>()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                received += text
                webSocket.send("""{"type":"mse","value":"video/mp4; codecs=\"avc1.640029\""}""")
                webSocket.send(byteArrayOf(1, 2, 3).toByteString())
                webSocket.send(byteArrayOf(4, 5).toByteString())
            }
        }).build())

        val url = client.urls.mse("ptzcam_main")
        assertEquals("/live/mse/api/ws", url.encodedPath)
        assertEquals("ptzcam_main", url.queryParameter("src"))
        val stream = MseStream.open(http, url, MseStream.codecs(hevc = false))
        assertEquals("video/mp4; codecs=\"avc1.640029\"", stream.mimeType)
        assertArrayEquals(byteArrayOf(1, 2, 3), stream.next(5_000))
        assertArrayEquals(byteArrayOf(4, 5), stream.next(5_000))
        stream.close()

        val request = server.takeRequest()
        assertEquals("frigate_token=$jwt", request.headers["Cookie"])
        val hello = FrigateJson.parseToJsonElement(received.poll(5, TimeUnit.SECONDS)!!) as JsonObject
        assertEquals("mse", hello["type"]!!.jsonPrimitive.content)
        val codecs = hello["value"]!!.jsonPrimitive.content
        assertTrue(codecs.contains("avc1.640029") && !codecs.contains("hvc1"))
        assertTrue(MseStream.codecs(hevc = true).contains("hvc1"))
    }

    @Test fun mseRefusedStreamIsForbidden() = runBlocking {
        // Frigate's nginx answers 403 for a src that isn't an existing go2rtc stream.
        server.enqueue(MockResponse.Builder().code(403).build())
        try {
            MseStream.open(http, client.urls.mse("nope"), MseStream.codecs(false))
            fail("expected Forbidden")
        } catch (_: FrigateException.Forbidden) {
        }
    }

    @Test fun mseGo2rtcErrorIsReported() = runBlocking {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.send("""{"type":"error","value":"codecs not matched"}""")
            }
        }).build())
        try {
            MseStream.open(http, client.urls.mse("cam"), MseStream.codecs(false))
            fail("expected error")
        } catch (e: FrigateException.Protocol) {
            assertTrue(e.message!!.contains("codecs not matched"))
        }
    }

    @Test fun ptzCommandsGoToTheCameraTopic() {
        val received = LinkedBlockingQueue<String>()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"topic":"stats","payload":"{}"}""") // Frigate's own traffic is ignored
            }
            override fun onMessage(webSocket: WebSocket, text: String) { received += text }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        }).build())

        val socket = FrigateSocket(http, client.urls.socket())
        assertTrue(socket.ptz("ptzcam", PtzCommand.Up))
        assertTrue(socket.ptz("ptzcam", PtzCommand.Stop))
        assertTrue(socket.ptzPreset("ptzcam", "door"))
        val msgs = List(3) { FrigateJson.parseToJsonElement(received.poll(5, TimeUnit.SECONDS)!!) as JsonObject }
        assertEquals(listOf("ptzcam/ptz"), msgs.map { it["topic"]!!.jsonPrimitive.content }.distinct())
        assertEquals(listOf("MOVE_UP", "STOP", "preset_door"), msgs.map { it["payload"]!!.jsonPrimitive.content })
        val request = server.takeRequest()
        assertEquals("/ws", request.url.encodedPath)
        assertEquals("frigate_token=$jwt", request.headers["Cookie"])
        socket.close()
    }

    @Test fun ptzInfoParsesFeaturesAndEmptyAnswer() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
            .body("""{"name":"ptzcam","features":["pt","zoom","pt-r"],"presets":["door","yard"],"profiles":[]}""").build())
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body("{}").build())
        val info = client.ptzInfo("ptzcam")
        assertTrue(info.canMove && info.canZoom && !info.canFocus && info.available)
        assertEquals(listOf("door", "yard"), info.presets)
        assertEquals(false, client.ptzInfo("garage").available)
        assertEquals("/api/ptzcam/ptz/info", server.takeRequest().url.encodedPath)
    }
}
