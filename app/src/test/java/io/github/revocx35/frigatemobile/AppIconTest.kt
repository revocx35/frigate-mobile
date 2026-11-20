package io.github.revocx35.frigatemobile

import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.github.revocx35.frigatemobile.data.AppIcon
import io.github.revocx35.frigatemobile.data.IconChoice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30])
class AppIconTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<TestApp>()

    private fun state(alias: String) =
        ctx.packageManager.getComponentEnabledSetting(ComponentName(ctx.packageName, ctx.packageName + alias))

    private fun pngFile(w: Int, h: Int): Uri {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val f = File(ctx.cacheDir, "pick-$w-$h.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(f)
    }

    @Test fun switchesLauncherAlias() {
        val icon = AppIcon(ctx)
        icon.apply(IconChoice.Bird)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, state(".LauncherBird"))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state(".LauncherDefault"))
        assertEquals(".LauncherBird", icon.enabledAlias())
        // A custom image keeps the default launcher icon (Android can't use an arbitrary image there).
        icon.apply(IconChoice.Custom)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, state(".LauncherDefault"))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state(".LauncherBird"))
    }

    @Test fun customImageIsCroppedSquareAndStoredPrivately() {
        val icon = AppIcon(ctx)
        runBlocking { icon.setCustom(pngFile(1600, 900)) }
        val bmp = icon.custom.value!!
        assertEquals(AppIcon.SIZE, bmp.width)
        assertEquals(AppIcon.SIZE, bmp.height)
        assertTrue(File(ctx.filesDir, "logo/custom.png").isFile)
        // Survives a restart.
        assertNotNull(AppIcon(ctx).custom.value)
        icon.clearCustom()
        assertNull(icon.custom.value)
        assertNull(AppIcon(ctx).custom.value)
    }

    @Test fun rejectsNonImagesAndHugeFiles() {
        val junk = File(ctx.cacheDir, "junk.png").apply { writeText("not an image") }
        try { runBlocking { AppIcon(ctx).setCustom(Uri.fromFile(junk)) }; fail() } catch (e: IOException) {
            assertTrue(e.message!!.contains("isn't an image"))
        }
        try { runBlocking { AppIcon(ctx, maxInputBytes = 100).setCustom(pngFile(400, 400)) }; fail() } catch (e: IOException) {
            assertTrue(e.message!!.contains("too large"))
        }
        assertNull(AppIcon(ctx).custom.value)
    }
}
