package io.github.revocx35.frigatemobile.ui.cameras

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import io.github.revocx35.frigatemobile.ui.components.LocalConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.Request

/**
 * A camera's newest frame (`latest.jpg`), fetched again every [intervalMs] while the screen is resumed and the card
 * is on screen. The old frame stays until the new one is decoded (no flicker), and the last frame of each camera
 * is kept for the session so the grid isn't blank when coming back to it. Fetched straight through the session
 * client rather than the image loader, so no cache can serve a stale frame.
 */
@Composable
fun LatestFrame(camera: String, height: Int, contentDescription: String, modifier: Modifier = Modifier, intervalMs: Long = 2_000) {
    val conn = LocalConnection.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    var frame by remember(conn, camera) { mutableStateOf(conn.frames[camera]) }

    LaunchedEffect(conn, camera, height, active) {
        while (active && isActive) {
            try {
                val url = conn.client.urls.latest(camera, height, cacheBust = System.currentTimeMillis())
                val request = Request.Builder().url(url).cacheControl(CacheControl.FORCE_NETWORK).build()
                val bmp = withContext(Dispatchers.IO) {
                    conn.http.newCall(request).execute().use { r ->
                        if (!r.isSuccessful) null
                        else r.body.bytes().let { BitmapFactory.decodeByteArray(it, 0, it.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }) }
                    }
                }
                if (bmp != null) {
                    frame = bmp
                    conn.frames[camera] = bmp
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the last frame; the next round tries again.
            }
            delay(intervalMs)
        }
    }

    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant).testTag("frame_$camera")) {
        frame?.let { Image(it.asImageBitmap(), contentDescription, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}
