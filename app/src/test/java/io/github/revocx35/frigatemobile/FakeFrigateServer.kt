package io.github.revocx35.frigatemobile

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import java.awt.Color
import java.awt.GradientPaint
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Collections
import javax.imageio.ImageIO

/**
 * A Frigate 0.18 stand-in over HTTPS with a self-signed certificate (like Frigate's port 8971), serving responses
 * recorded from a real Frigate (src/test/resources/fixtures). Password "demo-pass"; rate limits are not modelled.
 */
class FakeFrigateServer {
    val cert: HeldCertificate = HeldCertificate.Builder().commonName("frigate").addSubjectAlternativeName("localhost").build()
    private val server = MockWebServer()
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    /** Messages received on Frigate's websocket (/ws), e.g. PTZ commands. */
    val wsMessages: MutableList<String> = Collections.synchronizedList(mutableListOf())
    /** go2rtc MSE streams: refused (403) like a camera without a go2rtc stream. */
    @Volatile var mseAvailable = false
    @Volatile var reviewed = mutableSetOf<String>()
    /** `go2rtc.streams` in the served config (the fixture has none), e.g. a talk-only `tapo://` stream. */
    @Volatile var go2rtcStreamsJson: String? = null
    /** False = the server rejects the issued session (expired/revoked), as after `auth.session_length`. */
    @Volatile var acceptSession = true
    @Volatile private var token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhZG1pbiJ9.sig1" // gitleaks:allow (fake test JWT)

    /** Invalidates the issued session (like a restart with a new JWT secret); the next login gets a new one. */
    fun rotateSession() { token = token.dropLast(1) + (token.last() + 1) }

    val url: String get() = server.url("/").toString().replace("127.0.0.1", "localhost").removeSuffix("/")

    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()

    private val jpeg: ByteArray by lazy {
        val img = BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.paint = GradientPaint(0f, 0f, Color(0x2C, 0x55, 0xC2), 320f, 180f, Color(0x73, 0x54, 0x71))
        g.fillRect(0, 0, 320, 180)
        g.color = Color.WHITE
        g.fillOval(140, 60, 40, 60)
        g.dispose()
        ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
    }

    init {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                requests += "${request.method} $path"
                if (path == "/api/login") {
                    val body = request.body?.utf8().orEmpty()
                    return if (body.contains("\"password\":\"demo-pass\"")) {
                        MockResponse.Builder().code(200).addHeader("Set-Cookie", "frigate_token=$token; HttpOnly; Path=/").build()
                    } else {
                        json("""{"message":"Login failed"}""", 401)
                    }
                }
                if (!acceptSession || request.headers["Cookie"] != "frigate_token=$token") return json("{}", 401)
                return when {
                    path == "/ws" -> MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) { wsMessages += text }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
                    }).build()
                    path == "/live/mse/api/ws" && !mseAvailable -> MockResponse.Builder().code(403).build()
                    // "front" has a camera speaker (two-way audio), like a Tapo behind go2rtc's tapo:// source.
                    path == "/api/go2rtc/streams/front" -> json(
                        """{"producers":[{"url":"rtsp://cam/1","medias":["video, recvonly, H264","audio, recvonly, PCMA/8000"]},""" +
                            """{"url":"tapo://cam","medias":["audio, sendonly, PCMA/8000"]}],"consumers":null}""",
                    )
                    path.startsWith("/api/go2rtc/streams/") -> json("""{"producers":[{"url":"rtsp://cam/1","medias":["video, recvonly, H264"]}]}""")
                    path == "/api/front/ptz/info" -> json("""{"name":"front","features":["pt","zoom"],"presets":["door"],"profiles":[]}""")
                    path.endsWith("/ptz/info") -> json("{}")
                    path == "/api/version" -> MockResponse.Builder().code(200).body("0.18.3-a19e23a").build()
                    path == "/api/profile" -> json(fixture("profile.json"))
                    path == "/api/config" -> json(fixture("config.json").let { c ->
                        go2rtcStreamsJson?.let { c.replace("\"go2rtc\": {}", "\"go2rtc\": {\"streams\": $it}") } ?: c
                    })
                    path == "/api/stats" -> json(fixture("stats.json"))
                    path == "/api/review/summary" -> json(fixture("review_summary.json"))
                    path == "/api/review" -> json(reviewList(request.url.queryParameter("reviewed")))
                    path == "/api/review/activity/motion" -> json(motionActivity())
                    path.startsWith("/api/review/") -> json(fixture("review.json").let { all ->
                        val id = path.removePrefix("/api/review/")
                        Regex("""\{\s*"id": "${Regex.escape(id)}".*?"has_been_reviewed": (true|false)\s*\}""", RegexOption.DOT_MATCHES_ALL).find(all)!!.value
                    })
                    path == "/api/reviews/viewed" -> {
                        Regex("\"([0-9.]+-[a-z0-9]+)\"").findAll(request.body?.utf8().orEmpty()).forEach { reviewed += it.groupValues[1] }
                        json("""{"success":true}""")
                    }
                    path == "/api/event_ids" -> json(fixture("events.json"))
                    path == "/api/events" -> json(recentEvents(request.url.queryParameter("after")?.toDoubleOrNull()))
                    path.endsWith("/recordings") -> json(recordingSegments())
                    path.endsWith("/recordings/summary") -> json(fixture("front_recordings_summary.json"))
                    path.endsWith(".jpg") || path.endsWith(".webp") ->
                        MockResponse.Builder().code(200).addHeader("Content-Type", "image/jpeg").body(Buffer().write(jpeg)).build()
                    else -> json("""{"detail":"Not Found"}""", 404)
                }
            }
        }
        server.start()
    }

    private fun reviewList(reviewedParam: String?): String {
        val all = fixture("review.json")
        if (reviewedParam != "0" || reviewed.isEmpty()) return all
        // Drop items marked reviewed (enough for the tests: the fixture has two items).
        return reviewed.fold(all) { acc, id -> acc.replace(Regex("""\{\s*"id": "${Regex.escape(id)}".*?"has_been_reviewed": (true|false)\s*\},?""", RegexOption.DOT_MATCHES_ALL), "") }
            .replace(Regex(""",\s*]"""), "]")
    }

    /** Motion every minute of the last two hours, in Frigate 0.18's shape (sign flipped like its multi-camera graph). */
    private fun motionActivity(): String {
        val now = System.currentTimeMillis() / 1000 / 60 * 60
        return (0 until 120).joinToString(",", "[", "]") { i ->
            """{"start_time": ${now - i * 60}.0, "motion": ${if (i % 3 == 0) -(i % 40) else 0}, "audio": 0, "camera": "front"}"""
        }
    }

    /** 60 s recording segments for the last two hours (`GET /api/{camera}/recordings`). */
    private fun recordingSegments(): String {
        val now = System.currentTimeMillis() / 1000 / 60 * 60
        return (1..120).joinToString(",", "[", "]") { i ->
            """{"id": "r$i", "start_time": ${now - i * 60}.0, "end_time": ${now - i * 60 + 60}.0, "duration": 60, "motion": 0, "objects": 0}"""
        }
    }

    /** A person 20 minutes ago and a dog 50 minutes ago (relative to now, so the timeline shows them). */
    private fun recentEvents(after: Double?): String {
        val now = System.currentTimeMillis() / 1000.0
        if (after != null && after > now - 1200) return "[]"
        return """[{"id": "p1", "camera": "front", "label": "person", "start_time": ${now - 1200}, "end_time": ${now - 1140}, "data": {}},""" +
            """{"id": "d1", "camera": "front", "label": "dog", "start_time": ${now - 3000}, "end_time": ${now - 2900}, "data": {}}]"""
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    fun close() = server.close()
}
