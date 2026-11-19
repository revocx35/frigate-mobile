package io.github.revocx35.frigatemobile

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.revocx35.frigatemobile.data.AppGraph
import io.github.revocx35.frigatemobile.data.ServerTrust
import io.github.revocx35.frigatemobile.ui.AppRoot
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Drives the real UI (Robolectric) against [FakeFrigateServer] over HTTPS with a self-signed certificate. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class AppUiTest {
    @get:Rule val compose = createComposeRule()
    private val frigate = FakeFrigateServer()
    private val graph: AppGraph get() = ApplicationProvider.getApplicationContext<TestApp>().graph

    @After fun tearDown() = frigate.close()

    private fun start() = compose.setContent { AppRoot(graph, MutableStateFlow(null)) }

    private fun signInAndTrust(password: String = "demo-pass") {
        compose.signIn(frigate.url, "admin", password)
        compose.waitForTag("fingerprint")
        // The dialog shows the certificate's real fingerprint, to compare with the server's.
        compose.onNodeWithText(ServerTrust.sha256(frigate.cert.certificate)).assertExists()
        compose.onNodeWithTag("trust_cert").performClick()
    }

    @Test fun signInPinsCertificateAndShowsCameras() {
        start()
        signInAndTrust()
        compose.waitForTag("camera_front")
        compose.onNodeWithTag("camera_garage").assertExists()
        val account = graph.accounts.account.value!!
        assertEquals("admin", account.user)
        assertTrue(account.isAdmin)
        assertTrue(account.remembersPassword)
        assertEquals("demo-pass", graph.accounts.credentials(account)!!.password)
        assertFalse("token must be stored encrypted", account.tokenEnc!!.contains("eyJ"))
        assertEquals(1, graph.trust.pinned().size)
        assertTrue(frigate.requests.contains("POST /api/login"))
    }

    @Test fun wrongPassword() {
        start()
        signInAndTrust("nope")
        compose.waitForText("Wrong username or password")
        assertNull(graph.accounts.account.value)
    }

    @Test fun cleartextRefused() {
        start()
        compose.signIn("http://192.168.1.10:5000", "admin", "x")
        compose.waitForText("Use HTTPS")
        assertTrue(frigate.requests.isEmpty())
    }

    @Test fun certificateRejectedWhenNotTrusted() {
        start()
        compose.signIn(frigate.url, "admin", "demo-pass")
        compose.waitForTag("fingerprint")
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForText("was not trusted")
        assertTrue("no request may reach the server before the certificate is trusted", frigate.requests.isEmpty())
    }

    @Test fun reviewMarkAllAndDetail() {
        start()
        signInAndTrust()
        compose.waitForTag("camera_front")
        compose.onNodeWithTag("tab_review").performClick()
        compose.waitForTag("review_1795126902.662355-03axi9")
        compose.onNodeWithText("Car").assertExists()
        // Opening a finished item plays it and marks it reviewed.
        compose.onNodeWithTag("review_1795126902.662355-03axi9").performClick()
        compose.waitForTag("player")
        compose.waitForTag("event_1795126902.662355-1n73eo")
        compose.waitUntil(5_000) { "1795126902.662355-03axi9" in frigate.reviewed }
    }

    @Test fun cameraScreenShowsLiveAndSwitchesForAdmin() {
        start()
        signInAndTrust()
        compose.waitForTag("camera_front")
        compose.onNodeWithTag("camera_front").performClick()
        compose.waitForTag("live")
        compose.waitForTag("switch_detect")
        compose.onNodeWithTag("recordings").performClick()
        compose.waitForTag("recording_days")
    }

    @Test fun signOutForgetsSecrets() {
        start()
        signInAndTrust()
        compose.waitForTag("camera_front")
        compose.onNodeWithTag("tab_system").performClick()
        compose.waitForText("Frigate 0.18.3")
        compose.onNodeWithTag("sign_out").performScrollTo().performClick()
        compose.waitForTag("confirm_sign_out")
        compose.onNodeWithTag("confirm_sign_out").performClick()
        compose.waitForTag("sign_in")
        assertNull(graph.accounts.account.value)
        assertNull(graph.connection.value)
        assertEquals(frigate.url, graph.accounts.lastServer)
    }

    @Test fun expiredSessionWithoutPasswordSignsOut() {
        start()
        compose.waitForTag("remember")
        compose.onNodeWithTag("remember").performClick() // don't keep the password
        compose.signIn(frigate.url, "admin", "demo-pass")
        compose.waitForTag("trust_cert")
        compose.onNodeWithTag("trust_cert").performClick()
        compose.waitForTag("camera_front")
        assertFalse(graph.accounts.account.value!!.remembersPassword)
        frigate.acceptSession = false
        compose.onNodeWithTag("tab_review").performClick()
        compose.waitForText("session has ended")
        assertNull(graph.accounts.account.value)
    }

    @Test fun expiredSessionRenewedWithStoredPassword() {
        start()
        signInAndTrust()
        compose.waitForTag("camera_front")
        val logins = frigate.requests.count { it == "POST /api/login" }
        // The server forgets the session; the stored password signs in again without the user noticing.
        frigate.rotateSession()
        compose.onNodeWithTag("tab_review").performClick()
        try {
            compose.waitForTag("review_1795126902.662355-03axi9")
        } catch (e: Throwable) {
            println("REQUESTS: " + frigate.requests.takeLast(30) + " account=" + graph.accounts.account.value + " reason=" + graph.signedOutReason.value)
            throw e
        }
        assertNotNull(graph.accounts.account.value)
        assertEquals(logins + 1, frigate.requests.count { it == "POST /api/login" })
    }
}
