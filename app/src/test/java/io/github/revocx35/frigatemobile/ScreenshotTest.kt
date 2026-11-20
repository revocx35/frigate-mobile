package io.github.revocx35.frigatemobile

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.revocx35.frigatemobile.core.ServerAddress
import io.github.revocx35.frigatemobile.data.AppGraph
import io.github.revocx35.frigatemobile.data.ThemeMode
import io.github.revocx35.frigatemobile.ui.AppRoot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the main screens with sample data to build/screenshots/ (`./gradlew :app:recordRoborazziDebug
 * --tests '*ScreenshotTest*'`), to review the UI without a device. Live video doesn't render here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi", sdk = [30])
class ScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val frigate = FakeFrigateServer()
    private val graph: AppGraph get() = ApplicationProvider.getApplicationContext<TestApp>().graph

    @After fun tearDown() = frigate.close()

    /**
     * Only in record mode (`recordRoborazziDebug`): these tests produce images to look at, they don't assert. A plain
     * test run would compare against old images, which crashes Robolectric's native image decoder.
     */
    @Before fun onlyWhenRecording() = assumeTrue(System.getProperty("roborazzi.test.record") == "true")

    private fun shot(name: String) {
        Thread.sleep(500)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    private fun signedIn(theme: ThemeMode) {
        graph.settings.update { it.copy(theme = theme) }
        val address = ServerAddress.parse(frigate.url)!!
        graph.trust.serverHost = address.host
        graph.trust.pin(frigate.cert.certificate)
        runBlocking { graph.signIn(address, "admin", "demo-pass", true) }
        compose.setContent { AppRoot(graph, MutableStateFlow(null)) }
        compose.waitForTag("camera_front")
    }

    @Test fun login() {
        compose.setContent { AppRoot(graph, MutableStateFlow(null)) }
        compose.waitForTag("sign_in")
        shot("01_login")
    }

    @Test fun appIconSettings() {
        signedIn(ThemeMode.Light)
        graph.settings.update { it.copy(icon = io.github.revocx35.frigatemobile.data.IconChoice.Bird) }
        compose.onNodeWithTag("tab_system").performClick()
        compose.waitForTag("icon_Bird")
        compose.onNodeWithTag("icon_Bird").performScrollTo()
        shot("07_app_icon")
    }

    @Test fun walkthroughDark() = walkthrough(ThemeMode.Dark, "dark")

    @Test fun walkthroughLight() = walkthrough(ThemeMode.Light, "light")

    private fun walkthrough(theme: ThemeMode, suffix: String) {
        signedIn(theme)
        shot("02_cameras_$suffix")
        compose.onNodeWithTag("tab_review").performClick()
        compose.waitForTag("review_list")
        shot("03_review_$suffix")
        compose.onNodeWithTag("tab_system").performClick()
        compose.waitForText("Frigate 0.18.3")
        shot("04_system_$suffix")
        compose.onNodeWithTag("tab_cameras").performClick()
        compose.waitForTag("camera_front")
        compose.onNodeWithTag("camera_front").performClick()
        compose.waitForTag("switch_detect")
        shot("05_camera_$suffix")
        compose.onNodeWithTag("recordings").performClick()
        compose.waitForTag("recording_days")
        shot("06_recordings_$suffix")
    }
}
