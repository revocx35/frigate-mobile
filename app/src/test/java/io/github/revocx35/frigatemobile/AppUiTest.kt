package io.github.revocx35.frigatemobile

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.revocx35.frigatemobile.data.AppGraph
import io.github.revocx35.frigatemobile.data.IconChoice
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

    @Test fun ptzHoldMovesAndReleaseStops() {
        start()
        signInAndTrust()
        compose.waitForTag("camera_front")
        compose.onNodeWithTag("camera_front").performClick()
        // The fake Frigate refuses the go2rtc stream (403), so the live view falls back to MJPEG and says so.
        compose.waitForTag("live_fallback")
        compose.waitForTag("ptz_up")
        compose.onNodeWithTag("ptz_up").performTouchInput { down(center) }
        compose.waitUntil(5_000) { frigate.wsMessages.any { "MOVE_UP" in it } }
        compose.onNodeWithTag("ptz_up").performTouchInput { up() }
        compose.waitUntil(5_000) { frigate.wsMessages.any { "STOP" in it } }
        // A quick tap still moves for a while before STOP (cameras ignore shorter moves).
        frigate.wsMessages.clear()
        compose.onNodeWithTag("ptz_left").performTouchInput { down(center); up() }
        compose.waitUntil(5_000) { frigate.wsMessages.any { "STOP" in it } }
        val sent = frigate.wsMessages.toList()
        assertTrue(sent.first().contains("MOVE_LEFT"))
        compose.onNodeWithTag("ptz_preset_door").performScrollTo().performClick()
        compose.waitUntil(5_000) { frigate.wsMessages.any { "preset_door" in it } }
        assertTrue(frigate.wsMessages.all { "\"topic\":\"front/ptz\"" in it })
    }

    @Test fun talkButtonOnlyForCamerasWithASpeaker() {
        start()
        signInAndTrust()
        compose.waitForTag("camera_front")
        compose.onNodeWithTag("camera_front").performClick()
        compose.waitForTag("talk")
        assertTrue(frigate.requests.contains("GET /api/go2rtc/streams/front"))
        compose.onNodeWithTag("talk").assertContentDescriptionEquals("Talk")
    }

    /** A Tapo's talk channel in its own go2rtc stream (same camera address): known from the config, no probe. */
    @Test fun talkButtonFromConfigWithoutProbing() {
        frigate.go2rtcStreamsJson = """{"garage":["rtsp://user:pw@cam-garage.invalid:554/stream1","ffmpeg:garage#audio=opus"],""" +
            """"garage_talk":["tapo://cloudpw@cam-garage.invalid"]}"""
        start()
        signInAndTrust()
        compose.waitForTag("camera_garage")
        compose.onNodeWithTag("camera_garage").performScrollTo().performClick()
        compose.waitForTag("talk")
        compose.onNodeWithTag("talk").assertContentDescriptionEquals("Talk")
        assertFalse(frigate.requests.any { it.startsWith("GET /api/go2rtc/streams/") })
    }

    @Test fun cameraWithoutOnvifHasNoPtz() {
        start()
        signInAndTrust()
        compose.waitForTag("camera_garage")
        compose.onNodeWithTag("camera_garage").performScrollTo().performClick()
        compose.waitForTag("live")
        compose.waitForTag("switch_detect")
        assertTrue(compose.onAllNodesWithTag("ptz").fetchSemanticsNodes().isEmpty())
        assertFalse(frigate.requests.any { it.endsWith("/ptz/info") })
        // No speaker on this camera: no talk button.
        compose.waitUntil(5_000) { frigate.requests.contains("GET /api/go2rtc/streams/garage") }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithTag("talk").fetchSemanticsNodes().isEmpty())
    }

    @Test fun stillGridWhenLiveGridOff() {
        graph.settings.update { it.copy(liveGrid = false) }
        start()
        signInAndTrust()
        compose.waitForTag("camera_front")
        compose.waitUntil(15_000) { frigate.requests.count { it == "GET /api/front/latest.jpg" } >= 2 }
        assertFalse(frigate.requests.any { it.startsWith("GET /live/mse") })
    }

    /** Live grid on (default): the fake refuses the go2rtc stream, so cards fall back to refreshing still frames. */
    @Test fun cameraGridKeepsRefreshingFrames() {
        start()
        signInAndTrust()
        compose.waitForTag("camera_front")
        compose.waitUntil(15_000) { frigate.requests.count { it == "GET /api/front/latest.jpg" } >= 3 }
        assertTrue(frigate.requests.any { it == "GET /live/mse/api/ws" })
    }

    @Test fun birdIconSwitchesLauncherEntry() {
        start()
        signInAndTrust()
        compose.waitForTag("camera_front")
        compose.onNodeWithTag("tab_system").performClick()
        compose.waitForTag("icon_Bird")
        compose.onNodeWithTag("icon_Bird").performScrollTo().performClick()
        compose.waitUntil(5_000) { graph.settings.settings.value.icon == IconChoice.Bird }
        assertEquals(".LauncherBird", graph.appIcon.enabledAlias())
        compose.onNodeWithTag("icon_Default").performScrollTo().performClick()
        compose.waitUntil(5_000) { graph.appIcon.enabledAlias() == ".LauncherDefault" }
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
