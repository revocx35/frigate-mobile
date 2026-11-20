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
        val videoOnly = MseStream.codecs(hevc = false, audio = false)
        assertTrue(videoOnly.contains("avc1") && !videoOnly.contains("mp4a") && !videoOnly.contains("flac"))
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

    @Test fun webrtcSignalingExchangesOfferAnswerAndCandidates() = runBlocking {
        val received = LinkedBlockingQueue<String>()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                received += text
                val msg = FrigateJson.parseToJsonElement(text) as JsonObject
                if (msg["type"]!!.jsonPrimitive.content == "webrtc/offer") {
                    webSocket.send("""{"type":"webrtc/answer","value":"v=0 answer"}""")
                    webSocket.send("""{"type":"webrtc/candidate","value":"candidate:1 1 udp 2130706431 192.168.1.5 8555 typ host"}""")
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        }).build())

        val url = client.urls.webrtc("door_1")
        assertEquals("/live/webrtc/api/ws", url.encodedPath)
        assertEquals("door_1", url.queryParameter("src"))
        val signaling = WebRtcSignaling.open(http, url, "v=0 offer")
        assertEquals("v=0 answer", signaling.answer)
        val candidates = LinkedBlockingQueue<String>()
        signaling.onCandidate { candidates += it } // the candidate came before the sink: it was kept
        assertEquals("candidate:1 1 udp 2130706431 192.168.1.5 8555 typ host", candidates.poll(5, TimeUnit.SECONDS))
        assertTrue(signaling.sendCandidate("candidate:2 1 udp 1 10.0.0.2 4000 typ host"))

        val offer = FrigateJson.parseToJsonElement(received.poll(5, TimeUnit.SECONDS)!!) as JsonObject
        assertEquals("webrtc/offer", offer["type"]!!.jsonPrimitive.content)
        assertEquals("v=0 offer", offer["value"]!!.jsonPrimitive.content)
        val local = FrigateJson.parseToJsonElement(received.poll(5, TimeUnit.SECONDS)!!) as JsonObject
        assertEquals("webrtc/candidate", local["type"]!!.jsonPrimitive.content)
        assertEquals("candidate:2 1 udp 1 10.0.0.2 4000 typ host", local["value"]!!.jsonPrimitive.content)
        assertEquals("frigate_token=$jwt", server.takeRequest().headers["Cookie"])
        signaling.close()
    }

    @Test fun webrtcRefusedStreamIsForbidden() = runBlocking {
        server.enqueue(MockResponse.Builder().code(403).build())
        try {
            WebRtcSignaling.open(http, client.urls.webrtc("nope"), "v=0")
            fail("expected Forbidden")
        } catch (_: FrigateException.Forbidden) {
        }
    }

    @Test fun webrtcGo2rtcErrorIsReported() = runBlocking {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.send("""{"type":"error","value":"webrtc: codecs not matched"}""")
            }
        }).build())
        try {
            WebRtcSignaling.open(http, client.urls.webrtc("cam"), "v=0")
            fail("expected error")
        } catch (e: FrigateException.Protocol) {
            assertTrue(e.message!!.contains("codecs not matched"))
        }
    }

    @Test fun go2rtcStreamInfoDetectsTalkback() = runBlocking {
        // Shape of Frigate 0.18.3's answer (producer URLs, which carry camera passwords, replaced).
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body("""
            {"producers":[
              {"id":1,"format_name":"rtsp","url":"rtsp://cam/stream1","medias":["video, recvonly, H264","audio, recvonly, PCMA/8000"]},
              {"id":2,"format_name":"tapo","url":"tapo://cam","medias":["video, recvonly, H264","audio, recvonly, PCMA/8000","audio, sendonly, PCMA/8000"]}
            ],"consumers":null}
        """.trimIndent()).build())
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
            .body("""{"producers":[{"url":"rtsp://cam/stream2","medias":["video, recvonly, H264"]},{"url":"x"}]}""").build())
        val talk = client.go2rtcStream("door_1")
        assertTrue(talk.twoWayAudio)
        assertEquals(3 + 2, talk.medias.size)
        assertEquals(false, client.go2rtcStream("door_2").twoWayAudio)
        assertEquals("/api/go2rtc/streams/door_1", server.takeRequest().url.encodedPath)
    }
}
