package io.github.revocx35.frigatemobile.core

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Against a disposable Frigate (scripts/dev-frigate.sh up). Skipped unless FRIGATE_IT_* are set.
 * The server's self-signed certificate is pinned by FRIGATE_IT_CERT_SHA256, like the app does after the user
 * confirmed it.
 */
class LiveFrigateTest {
    private val url = System.getenv("FRIGATE_IT_URL")
    private val user = System.getenv("FRIGATE_IT_USER")
    private val password = System.getenv("FRIGATE_IT_PASSWORD")
    private val pin = System.getenv("FRIGATE_IT_CERT_SHA256")

    private fun pinned(): Pair<javax.net.ssl.SSLSocketFactory, X509TrustManager> {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) = throw java.security.cert.CertificateException()
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {
                val fp = MessageDigest.getInstance("SHA-256").digest(c[0].encoded).joinToString(":") { "%02X".format(it) }
                if (!fp.equals(pin, true)) throw java.security.cert.CertificateException("pin mismatch")
            }
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
        return ctx.socketFactory to tm
    }

    @Test fun fullFlow() = runTest {
        assumeTrue(url != null && user != null && password != null && pin != null)
        val (sf, tm) = pinned()
        val session = FrigateSession(ServerAddress.parse(url!!)!!, null, Credentials(user!!, password!!))
        val http = OkHttpClient.Builder().sslSocketFactory(sf, tm).hostnameVerifier { _, _ -> true }
            .addInterceptor(session.interceptor).build()
        val client = FrigateClient(session, http)

        try { session.signIn(http, Credentials(user, "wrong-$password")); throw AssertionError() } catch (_: FrigateException.BadCredentials) {}
        session.signIn(http, Credentials(user, password))
        assertTrue(client.version().startsWith("0."))
        assertTrue(client.profile().isAdmin)
        val config = client.config()
        assertTrue(config.cameras.isNotEmpty())
        val cam = config.cameras.first().name
        val stats = client.stats()
        assertTrue(stats.cameras.isNotEmpty())

        // Frigate applies toggles asynchronously: the config reflects them a moment later.
        suspend fun snapshotsBecome(v: Boolean) {
            repeat(50) { if (client.config().camera(cam)!!.snapshots == v) return; Thread.sleep(200) }
            throw AssertionError("snapshots never became $v")
        }
        client.setCameraFeature(cam, CameraFeature.Snapshots, false)
        snapshotsBecome(false)
        client.setCameraFeature(cam, CameraFeature.Snapshots, true)
        snapshotsBecome(true)

        val reviews = client.reviews(limit = 10)
        if (reviews.isNotEmpty()) {
            val r = reviews.first()
            client.markReviewed(listOf(r.id))
            assertTrue(client.reviews(reviewed = true, limit = 50).any { it.id == r.id })
            assertEquals(r.data.detections.toSet(), client.eventsByIds(r.data.detections).map { it.id }.toSet())
            http.newCall(Request.Builder().url(client.urls.reviewThumb(r)!!).build()).execute().use { assertEquals(200, it.code) }
        }
        client.reviewCounts()
        http.newCall(Request.Builder().url(client.urls.latest(cam, 240)).build()).execute().use {
            assertEquals(200, it.code); assertEquals("image/jpeg", it.header("Content-Type"))
        }
        http.newCall(Request.Builder().url(client.urls.mjpeg(cam, 2, 240)).build()).execute().use {
            assertTrue(MjpegReader(it.body.byteStream()).next()!!.size > 1000)
        }
        val days = client.recordingDays(cam, "UTC")
        assertTrue(days.isNotEmpty())

        // Session ended on the server side (token dropped): the stored password signs in again silently.
        val anon = FrigateSession(session.address, SessionToken("frigate_token", "x.y.z"), Credentials(user, password))
        val http2 = http.newBuilder().apply { interceptors().clear() }.addInterceptor(anon.interceptor).build()
        assertTrue(FrigateClient(anon, http2).profile().isAdmin)
        assertTrue(anon.token!!.value != "x.y.z")
    }
}
