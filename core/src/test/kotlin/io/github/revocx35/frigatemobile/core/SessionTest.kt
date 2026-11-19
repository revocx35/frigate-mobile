package io.github.revocx35.frigatemobile.core

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

class SessionTest {
    private lateinit var server: MockWebServer
    private lateinit var other: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var session: FrigateSession
    private lateinit var client: FrigateClient
    private val saved = mutableListOf<SessionToken?>()

    private val jwt1 = "aaa.bbb.c1"
    private val jwt2 = "aaa.bbb.c2"

    @Before fun setUp() {
        val localhost = InetAddress.getByName("localhost").canonicalHostName
        val cert = HeldCertificate.Builder().addSubjectAlternativeName(localhost).addSubjectAlternativeName("localhost").build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server = MockWebServer().apply { useHttps(serverCerts.sslSocketFactory()); start() }
        other = MockWebServer().apply { useHttps(serverCerts.sslSocketFactory()); start() }
        val address = ServerAddress.parse(server.url("/").toString())!!
        session = FrigateSession(address, SessionToken("frigate_token", jwt1), Credentials("admin", "pw")) { saved += it }
        http = OkHttpClient.Builder().sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
            .addInterceptor(session.interceptor).build()
        client = FrigateClient(session, http)
    }

    @After fun tearDown() { server.close(); other.close() }

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    @Test fun cookieOnlyForOwnServer() = runTest {
        server.enqueue(json("""{"username":"admin","role":"admin"}"""))
        client.profile()
        assertEquals("frigate_token=$jwt1", server.takeRequest().headers["Cookie"])

        other.enqueue(json("{}"))
        http.newCall(Request.Builder().url(other.url("/api/x")).build()).execute().close()
        assertNull("session must not leak to another host", other.takeRequest().headers["Cookie"])
    }

    @Test fun csrfHeaderOnWrites() = runTest {
        server.enqueue(json("""{"success":true}"""))
        client.markReviewed(listOf("r1", "r2"))
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/reviews/viewed", req.target)
        assertEquals("1", req.headers["X-CSRF-TOKEN"])
        assertEquals("""{"ids":["r1","r2"],"reviewed":true}""", req.body?.utf8())
    }

    @Test fun reloginOn401AndRetry() = runTest {
        server.enqueue(json("""{"message":"expired"}""", 401))
        server.enqueue(MockResponse.Builder().code(200).addHeader("Set-Cookie", "frigate_token=$jwt2; HttpOnly; Path=/").build())
        server.enqueue(json("""{"username":"admin","role":"viewer"}"""))
        assertEquals("viewer", client.profile().role)
        server.takeRequest()
        val login = server.takeRequest()
        assertEquals("/api/login", login.target)
        assertEquals("""{"user":"admin","password":"pw"}""", login.body?.utf8())
        assertEquals("frigate_token=$jwt2", server.takeRequest().headers["Cookie"])
        assertEquals(SessionToken("frigate_token", jwt2), saved.last())
    }

    @Test fun wrongStoredPasswordEndsSession() = runTest {
        server.enqueue(json("{}", 401))
        server.enqueue(json("""{"message":"Login failed"}""", 401))
        try {
            client.profile(); fail()
        } catch (_: FrigateException.Unauthorized) {}
        assertNull(session.token)
        assertNull(saved.last())
    }

    @Test fun no401LoopWithoutPassword() = runTest {
        session.credentials = null
        server.enqueue(json("{}", 401))
        try {
            client.stats(); fail()
        } catch (_: FrigateException.Unauthorized) {}
        assertEquals(1, server.requestCount)
    }

    @Test fun picksUpRefreshedCookie() = runTest {
        server.enqueue(json("""{"username":"a"}""").newBuilder().addHeader("Set-Cookie", "frigate_token=$jwt2; Max-Age=86400").build())
        client.profile()
        assertEquals(jwt2, session.token!!.value)
        assertEquals(jwt2, saved.last()!!.value)
    }

    @Test fun signInErrors() {
        server.enqueue(json("""{"message":"Login failed"}""", 401))
        try { session.signIn(http, Credentials("a", "b")); fail() } catch (_: FrigateException.BadCredentials) {}
        server.enqueue(json("""{"message":"Too many"}""", 429))
        try { session.signIn(http, Credentials("a", "b")); fail() } catch (_: FrigateException.RateLimited) {}
        server.enqueue(json("""{"message":"Authentication is disabled"}""", 404))
        try { session.signIn(http, Credentials("a", "b")); fail() } catch (_: FrigateException.AuthDisabled) {}
        server.enqueue(MockResponse.Builder().code(200).addHeader("Set-Cookie", "frigate_token=$jwt2; Path=/").build())
        assertEquals(jwt2, session.signIn(http, Credentials("a", "b")).value)
    }

    @Test fun customCookieName() {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Set-Cookie", "nvr_session=$jwt2; Path=/").build())
        val t = session.signIn(http, Credentials("a", "b"))
        assertEquals("nvr_session", t.cookieName)
        assertTrue(t.toString().contains("***"))
    }

    @Test fun cameraToggleOnOldFrigate() = runTest {
        server.enqueue(json("""{"detail":"Not Found"}""", 404))
        try { client.setCameraFeature("front", CameraFeature.Detect, true); fail() } catch (_: FrigateException.Unsupported) {}
        server.enqueue(json("""{"success":true}"""))
        client.setCameraFeature("front", CameraFeature.Recordings, false)
        server.takeRequest()
        val r = server.takeRequest()
        assertEquals("PUT /api/camera/front/set/recordings", "${r.method} ${r.target}")
        assertEquals("""{"value":"OFF"}""", r.body?.utf8())
    }
}
