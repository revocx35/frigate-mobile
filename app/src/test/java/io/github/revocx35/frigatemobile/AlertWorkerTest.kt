package io.github.revocx35.frigatemobile

import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import io.github.revocx35.frigatemobile.core.ServerAddress
import io.github.revocx35.frigatemobile.data.AlertWorker
import io.github.revocx35.frigatemobile.data.AppGraph
import io.github.revocx35.frigatemobile.data.SignInResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AlertWorkerTest {
    private val frigate = FakeFrigateServer()
    private val app get() = ApplicationProvider.getApplicationContext<TestApp>()
    private val graph: AppGraph get() = app.graph

    @After fun tearDown() = frigate.close()

    private fun signIn() {
        val address = ServerAddress.parse(frigate.url)!!
        graph.trust.serverHost = address.host
        graph.trust.pin(frigate.cert.certificate)
        assertEquals(SignInResult.Ok, runBlocking { graph.signIn(address, "admin", "demo-pass", true) })
    }

    private fun run(): ListenableWorker.Result = runBlocking { TestListenableWorkerBuilder<AlertWorker>(app).build().doWork() }

    @Test fun notifiesNewAlertsOnce() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        signIn()
        graph.settings.update { it.copy(alertNotifications = true) }
        graph.settings.lastNotified = 1_000.0 // older than both fixture items
        assertEquals(ListenableWorker.Result.success(), run())
        val nm = shadowOf(app.getSystemService(NotificationManager::class.java))
        assertEquals(2, nm.allNotifications.size)
        assertTrue(nm.allNotifications.any { it.extras.getString("android.title")!!.startsWith("Car") })
        assertTrue(graph.settings.lastNotified > 1_000.0)
        // Nothing new: no duplicate notifications.
        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(2, nm.allNotifications.size)
    }

    @Test fun firstRunOnlyRemembersNow() {
        signIn()
        graph.settings.update { it.copy(alertNotifications = true) }
        graph.settings.lastNotified = 0.0
        run()
        assertTrue(graph.settings.lastNotified > 0)
        assertEquals(0, shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.size)
    }

    @Test fun offWhenDisabledOrSignedOut() {
        assertEquals(ListenableWorker.Result.success(), run())
        signIn()
        graph.settings.lastNotified = 1_000.0
        run()
        assertEquals(0, shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.size)
    }
}
