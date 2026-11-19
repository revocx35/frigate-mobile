package io.github.revocx35.frigatemobile.ui.cameras

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import io.github.revocx35.frigatemobile.core.FrigateException
import io.github.revocx35.frigatemobile.core.MjpegReader
import io.github.revocx35.frigatemobile.core.toUserMessage
import io.github.revocx35.frigatemobile.ui.components.LocalConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Plays Frigate's MJPEG live feed. Streams only while the screen is resumed (no data use in the background) and
 * reconnects with a growing pause after errors.
 */
@Composable
fun MjpegView(url: String, contentDescription: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit) {
    val conn = LocalConnection.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    var frame by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    var error by remember(url) { mutableStateOf<String?>(null) }

    LaunchedEffect(url, active) {
        if (!active) return@LaunchedEffect
        // Long read timeout: frames arrive continuously, but a stalled camera may pause the stream.
        val client = conn.http.newBuilder().readTimeout(20, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
        var backoff = 1000L
        while (isActive) {
            try {
                val call = client.newCall(Request.Builder().url(url).build())
                withContext(Dispatchers.IO) {
                    suspendCancellableCoroutine<Unit> { cont ->
                        cont.invokeOnCancellation { call.cancel() }
                        try {
                            call.execute().use { r ->
                                if (r.code == 401) throw FrigateException.Unauthorized()
                                if (r.code == 403) throw FrigateException.Forbidden()
                                if (!r.isSuccessful) throw FrigateException.Http(r.code, "Live view failed (HTTP ${r.code}).")
                                val reader = MjpegReader(r.body.byteStream())
                                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                                while (cont.isActive) {
                                    val bytes = reader.next() ?: break
                                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: continue
                                    frame = bmp.asImageBitmap()
                                    error = null
                                    backoff = 1000L
                                }
                            }
                            if (cont.isActive) cont.resumeWith(Result.success(Unit))
                        } catch (e: Throwable) {
                            if (cont.isActive) cont.resumeWith(Result.failure(e))
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.toUserMessage()
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(15_000L)
        }
    }

    Box(modifier.background(Color.Black).testTag("live"), contentAlignment = Alignment.Center) {
        frame?.let { Image(it, contentDescription, contentScale = contentScale, modifier = Modifier.fillMaxSize()) }
        if (frame == null || error != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                if (error == null) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it + "\nReconnecting…", color = Color.White, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                }
            }
        }
    }
}
