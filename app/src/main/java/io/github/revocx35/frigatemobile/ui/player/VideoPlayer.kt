package io.github.revocx35.frigatemobile.ui.player

import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import io.github.revocx35.frigatemobile.ui.components.ErrorBox
import io.github.revocx35.frigatemobile.ui.components.LocalConnection
import io.github.revocx35.frigatemobile.ui.theme.AppIcons

/** An ExoPlayer and its last error. Hoisted so the same playback survives switching to full screen. */
class FrigatePlayer(val exo: ExoPlayer) {
    var error by mutableStateOf<String?>(null)
}

/**
 * Plays a Frigate recording (HLS from /vod/… or an MP4) through the signed-in session's OkHttp client, so the
 * session cookie and the pinned certificate apply to every segment. Pauses when the app goes to the background,
 * released when it leaves the composition. If the hardware video decoder fails mid-stream (some devices and the
 * emulator's decoder reject camera streams), playback restarts once with Android's software decoders, at the same
 * position.
 */
@OptIn(UnstableApi::class)
@Composable
fun rememberFrigatePlayer(url: String): FrigatePlayer {
    val conn = LocalConnection.current
    val context = LocalContext.current
    var software by remember(url) { mutableStateOf(false) }
    var resumeAt by remember(url) { mutableLongStateOf(0L) }
    val player = remember(url, software) {
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .apply { if (software) setMediaCodecSelector(softwareFirstSelector()) }
        val exo = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(conn.http)))
            .build()
        FrigatePlayer(exo).also { p ->
            exo.setMediaItem(
                MediaItem.Builder().setUri(url).apply { if (url.contains(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8) }.build(),
                resumeAt,
            )
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(e: PlaybackException) {
                    val decoderFailed = e.errorCode in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED ||
                        (e is ExoPlaybackException && e.type == ExoPlaybackException.TYPE_RENDERER)
                    if (decoderFailed && !software) {
                        resumeAt = exo.currentPosition
                        software = true
                        return
                    }
                    p.error = when (e.errorCode) {
                        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "No recording available for this time."
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Can't reach the server."
                        else -> "Playback failed: ${e.errorCodeName}"
                    }
                }
            })
            exo.prepare()
            exo.playWhenReady = true
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(player, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.exo.pause() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.exo.release()
        }
    }
    return player
}

/** Android's software decoders before hardware ones. */
@OptIn(UnstableApi::class)
private fun softwareFirstSelector(): MediaCodecSelector = MediaCodecSelector { mime, secure, tunneling ->
    MediaCodecUtil.getDecoderInfos(mime, secure, tunneling).sortedBy { if (it.softwareOnly) 0 else 1 }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(player: FrigatePlayer, modifier: Modifier = Modifier, onFullscreenToggle: ((Boolean) -> Unit)? = null) {
    Box(modifier.background(Color.Black).testTag("player")) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setShowSubtitleButton(false)
                }
            },
            update = { view ->
                view.player = player.exo
                view.setFullscreenButtonClickListener(onFullscreenToggle?.let { cb -> PlayerView.FullscreenButtonClickListener { cb(it) } })
            },
            modifier = Modifier.fillMaxSize(),
        )
        player.error?.let { ErrorBox(it, Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f))) }
    }
}

/** Full-screen landscape playback with system bars hidden; back leaves it. */
@Composable
fun FullscreenEffect(onExit: () -> Unit) {
    val activity = LocalActivity.current
    DisposableEffect(Unit) {
        val window = activity?.window
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = previous ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    BackHandler(onBack = onExit)
}

/** A recording span on its own screen (from the Recordings list). */
@Composable
fun PlayerScreen(title: String, url: String, onBack: () -> Unit) {
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    if (fullscreen) FullscreenEffect { fullscreen = false }
    val player = rememberFrigatePlayer(url)
    // The player stays at the same place in the composition, so toggling full screen doesn't restart playback.
    Scaffold(
        topBar = {
            if (!fullscreen) {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(AppIcons.Back, "Back") } },
                )
            }
        },
    ) { padding ->
        VideoPlayer(player, Modifier.padding(if (fullscreen) PaddingValues() else padding).fillMaxSize()) { fullscreen = it }
    }
}
