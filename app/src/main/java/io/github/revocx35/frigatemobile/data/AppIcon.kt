package io.github.revocx35.frigatemobile.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.revocx35.frigatemobile.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.min

/** The app's icon: one of the icons shipped in the APK, or an image the user picked. */
enum class IconChoice(val label: String, val alias: String?) {
    Default("Camera (default)", ".LauncherDefault"),
    Bird("Frigate bird", ".LauncherBird"),
    /** Launcher keeps the default icon; the picked image is used in the app and for a home-screen shortcut. */
    Custom("Custom image", ".LauncherDefault"),
}

/**
 * App icon handling.
 *
 * Built-in icons switch the real launcher icon by enabling one `<activity-alias>` and disabling the others
 * (the only way Android lets an app change its own icon). A user-picked image can't become the launcher icon, so
 * it is stored privately and offered as a pinned home-screen shortcut (and shown inside the app).
 */
class AppIcon(private val context: Context, private val maxInputBytes: Long = MAX_INPUT_BYTES) {
    private val file: File get() = File(context.filesDir, "logo/custom.png")

    private val _custom = MutableStateFlow(loadBitmap())
    /** The stored custom image (square PNG), or null. */
    val custom: StateFlow<Bitmap?> = _custom.asStateFlow()

    private fun loadBitmap(): Bitmap? = file.takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }

    /** Enables the launcher entry for [choice] and disables the others. */
    fun apply(choice: IconChoice) {
        val pm = context.packageManager
        val wanted = choice.alias ?: return
        // Enable the new entry before disabling the old one, so there is never a moment without a launcher icon.
        setEnabled(pm, wanted, true)
        IconChoice.entries.mapNotNull { it.alias }.distinct().filter { it != wanted }.forEach { setEnabled(pm, it, false) }
    }

    fun enabledAlias(): String? = IconChoice.entries.mapNotNull { it.alias }.distinct().firstOrNull { alias ->
        val state = context.packageManager.getComponentEnabledSetting(component(alias))
        state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && alias == ".LauncherDefault")
    }

    private fun component(alias: String) = ComponentName(context.packageName, context.packageName + alias)

    private fun setEnabled(pm: PackageManager, alias: String, enabled: Boolean) {
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (pm.getComponentEnabledSetting(component(alias)) != state) {
            pm.setComponentEnabledSetting(component(alias), state, PackageManager.DONT_KILL_APP)
        }
    }

    /**
     * Reads a picked image (size-limited), crops it to a centered square, scales it to [SIZE] px and stores it as
     * PNG in app-private storage. Throws [IOException] with a user-facing message if the image can't be used.
     */
    suspend fun setCustom(uri: Uri) = withContext(Dispatchers.IO) {
        val bytes = readLimited(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("This file isn't an image Android can read.")
        var sample = 1
        while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("This file isn't an image Android can read.")
        val side = min(decoded.width, decoded.height)
        val src = Rect((decoded.width - side) / 2, (decoded.height - side) / 2, (decoded.width + side) / 2, (decoded.height + side) / 2)
        val square = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        Canvas(square).drawBitmap(decoded, src, Rect(0, 0, SIZE, SIZE), Paint(Paint.FILTER_BITMAP_FLAG))
        decoded.recycle()
        val png = ByteArrayOutputStream().also { square.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "custom.png.tmp")
        tmp.writeBytes(png)
        if (!tmp.renameTo(file)) throw IOException("Couldn't save the image.")
        _custom.value = square
        // A shortcut already on the home screen gets the new image too.
        runCatching { ShortcutManagerCompat.updateShortcuts(context, listOf(shortcut(square))) }
    }

    fun clearCustom() {
        file.delete()
        _custom.value = null
        runCatching { ShortcutManagerCompat.disableShortcuts(context, listOf(SHORTCUT_ID), "The custom app icon was removed") }
    }

    val canPinShortcut: Boolean get() = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** Asks the launcher to add a home-screen shortcut with the custom image. False if there's no image or no support. */
    fun pinCustomShortcut(): Boolean {
        val bmp = _custom.value ?: return false
        if (!canPinShortcut) return false
        runCatching { ShortcutManagerCompat.enableShortcuts(context, listOf(shortcut(bmp))) }
        return ShortcutManagerCompat.requestPinShortcut(context, shortcut(bmp), null)
    }

    private fun shortcut(bmp: Bitmap): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
            .setShortLabel("Frigate")
            .setLongLabel("Frigate Mobile")
            .setIcon(IconCompat.createWithAdaptiveBitmap(adaptive(bmp)))
            .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
            .build()

    /**
     * Places the image inside the adaptive-icon safe zone (the launcher masks the outer third away), on a
     * background in the image's corner color so logos with a plain background look seamless.
     */
    private fun adaptive(bmp: Bitmap): Bitmap {
        val size = 432
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(bmp.getPixel(0, 0).let { if (android.graphics.Color.alpha(it) < 255) android.graphics.Color.WHITE else it })
        val inset = (size * (1 - 66f / 108f) / 2).toInt()
        c.drawBitmap(bmp, null, Rect(inset, inset, size - inset, size - inset), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun readLimited(uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Couldn't open the image.")
        input.use { s ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                total += n
                if (total > maxInputBytes) throw IOException("The image is too large (max ${maxInputBytes / 1024 / 1024} MB).")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    companion object {
        const val SIZE = 432
        const val SHORTCUT_ID = "custom_icon"
        const val MAX_INPUT_BYTES = 20L * 1024 * 1024
    }
}
