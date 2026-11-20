package io.github.revocx35.frigatemobile.ui.cameras

import android.media.MediaCodecList
import android.net.Uri
import androidx.annotation.OptIn
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.revocx35.frigatemobile.core.FrigateException
import io.github.revocx35.frigatemobile.core.MseStream
import io.github.revocx35.frigatemobile.core.toUserMessage
import io.github.revocx35.frigatemobile.ui.components.LocalConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.math.min

/**
 * A camera's live view. With a go2rtc stream ([go2rtcStream]) it plays the camera's own stream (full resolution
 * and frame rate) over go2rtc's MSE websocket; without one, or when that stream can't be played, it shows Frigate's
 * MJPEG feed ([mjpegUrl]) and says why.
 */
@Composable
fun LiveView(
    go2rtcStream: String?,
    mjpegUrl: String,
    contentDescription: String,
    muted: Boolean,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    /** Shown behind the spinner until the first frame is decoded (e.g. the last still frame). */
    placeholder: (@Composable () -> Unit)? = null,
    /** Replaces the MJPEG fallback (and its explanation), e.g. still frames in the camera grid. */
    fallbackContent: (@Composable () -> Unit)? = null,
) {
    var fallback by remember(go2rtcStream) { mutableStateOf<String?>(null) }
    if (fallbackContent != null && (go2rtcStream == null || fallback != null)) {
        Box(modifier) { fallbackContent() }
    } else if (go2rtcStream == null || fallback != null) {
        Box(modifier) {
            MjpegView(mjpegUrl, contentDescription, Modifier.fillMaxSize(), contentScale)
            fallback?.let { reason ->
                Surface(
                    color = Color.Black.copy(alpha = 0.6f),
                    contentColor = Color.White,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).testTag("live_fallback"),
                ) {
                    Text("MJPEG · full stream unavailable: $reason", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                }
            }
        }
    } else {
        MseView(go2rtcStream, contentDescription, muted, modifier, contentScale, placeholder) { fallback = it }
    }
}

/**
 * ExoPlayer fed from [MseStream]: one websocket per playback, opened only while the screen is resumed. The stream is
 * opened before the player exists, so a refused stream (no go2rtc stream, role without access) falls back at once.
 * Network drops reconnect with a growing pause; a hardware decoder failure retries once with software decoders.
 * Live latency is kept low by playing slightly faster whenever more than ~1.5 s is buffered.
 */
@OptIn(UnstableApi::class)
@Composable
private fun MseView(
    stream: String,
    contentDescription: String,
    muted: Boolean,
    modifier: Modifier,
    contentScale: ContentScale,
    placeholder: (@Composable () -> Unit)?,
    onUnavailable: (String) -> Unit,
) {
    val conn = LocalConnection.current
    val context = LocalContext.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    // generation restarts playback (new socket and player); failures counts failed tries since the last frame.
    var generation by remember(stream) { mutableIntStateOf(0) }
    var failures by remember(stream) { mutableIntStateOf(0) }
    var software by remember(stream) { mutableStateOf(false) }
    var everPlayed by remember(stream) { mutableStateOf(false) }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var showingVideo by remember(stream) { mutableStateOf(false) }
    var status by remember(stream) { mutableStateOf<String?>(null) }
    // Sound is best effort: if the audio decoder fails or the audio clock stalls, play video only.
    var noSound by remember(stream) { mutableStateOf(false) }
    val withAudio = !muted && !noSound
    val hevc = remember { hasDecoder("video/hevc") }

    LaunchedEffect(stream, active, generation, software, withAudio) {
        if (!active) return@LaunchedEffect
        showingVideo = false
        if (failures > 0) delay(min(15_000L, 1_000L shl failures.coerceAtMost(4)))
        val url = conn.client.urls.mse(stream)
        val mse = try {
            MseStream.open(conn.http, url, MseStream.codecs(hevc, audio = withAudio))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Refused or not negotiable: the camera can't be played this way, show MJPEG instead.
            if (!everPlayed && (e is FrigateException.Forbidden || e is FrigateException.NotFound || e is FrigateException.Protocol || failures >= 2)) {
                onUnavailable(e.toUserMessage())
            } else {
                status = e.toUserMessage()
                failures++
                generation++
            }
            return@LaunchedEffect
        }
        val renderers = LiveRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .apply { if (software) setMediaCodecSelector(softwareFirst()) }
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(500, 4_000, 250, 250).build()
        val extractors = ExtractorsFactory { arrayOf<Extractor>(FragmentedMp4Extractor(SubtitleParser.Factory.UNSUPPORTED, 0)) }
        val source = ProgressiveMediaSource.Factory({ MseDataSource(mse) }, extractors)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0)) // reconnect ourselves, with a new socket
            .createMediaSource(MediaItem.fromUri(url.toString()))
        val exo = ExoPlayer.Builder(context, renderers).setLoadControl(load).build()
        exo.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                everPlayed = true
                showingVideo = true
                status = null
                failures = 0
            }

            override fun onPlayerError(e: PlaybackException) {
                showingVideo = false
                val audioFailed = e is ExoPlaybackException && e.type == ExoPlaybackException.TYPE_RENDERER &&
                    e.rendererFormat?.sampleMimeType?.startsWith("audio/") == true
                if (audioFailed) {
                    noSound = true // reconnects without audio (withAudio is a key)
                    return
                }
                val decoder = e.errorCode in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED ||
                    (e is ExoPlaybackException && e.type == ExoPlaybackException.TYPE_RENDERER)
                when {
                    decoder && !software -> software = true
                    decoder -> onUnavailable("this phone can't decode the camera's video")
                    !everPlayed && failures >= 2 -> onUnavailable((e.cause as? Exception)?.toUserMessage() ?: e.errorCodeName)
                    else -> {
                        status = (e.cause as? Exception)?.toUserMessage() ?: e.errorCodeName
                        failures++
                        generation++
                    }
                }
            }
        })
        exo.setMediaSource(source)
        exo.prepare()
        exo.playWhenReady = true
        player = exo
        try {
            // Keep the delay to the camera short: a stalled network leaves frames buffered, play them off quickly.
            launch {
                var lastPosition = -1L
                var stuck = 0
                var waited = 0
                while (isActive) {
                    delay(1_000)
                    if (!showingVideo) {
                        // No picture yet. With sound, the audio track is the usual suspect (it gates playback start):
                        // retry without it. Without sound, reconnect.
                        if (++waited >= FIRST_FRAME_TIMEOUT_S) {
                            if (withAudio) noSound = true
                            else { status = "No picture from the camera"; failures++; generation++ }
                        }
                        continue
                    }
                    // Audio clock stalled (video waits for audio): drop the sound.
                    if (withAudio && exo.playWhenReady && exo.currentPosition == lastPosition) {
                        if (++stuck >= 3) noSound = true
                    } else {
                        stuck = 0
                    }
                    lastPosition = exo.currentPosition
                    val lag = exo.bufferedPosition - exo.currentPosition
                    val speed = when {
                        lag > 4_000 -> 1.5f
                        lag > 1_500 -> 1.15f
                        else -> 1f
                    }
                    if (exo.playbackParameters.speed != speed) exo.setPlaybackSpeed(speed)
                }
            }
            awaitCancellation()
        } finally {
            player = null
            exo.release()
            mse.close()
        }
    }

    Box(modifier.background(Color.Black).testTag("live").semantics { this.contentDescription = contentDescription }, contentAlignment = Alignment.Center) {
        player?.let { exo ->
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update = { view ->
                    view.player = exo
                    view.resizeMode = if (contentScale == ContentScale.Crop) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (showingVideo) {
            Box(Modifier.fillMaxSize().testTag("live_video"))
            if (noSound && !muted) {
                Surface(
                    color = Color.Black.copy(alpha = 0.6f),
                    contentColor = Color.White,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).testTag("no_sound"),
                ) {
                    Text("No sound for this camera", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                }
            }
        } else {
            placeholder?.let { Box(Modifier.fillMaxSize()) { it() } }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
                status?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it + "\nReconnecting…", color = Color.White, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** Reads the fMP4 bytes of one [MseStream]. Single use: a reconnect makes a new stream and player. */
@OptIn(UnstableApi::class)
private class MseDataSource(private val stream: MseStream) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var opened = false
    private var used = false
    private var chunk: ByteArray? = null
    private var pos = 0

    override fun open(dataSpec: DataSpec): Long {
        if (used) throw IOException("The live stream can't be restarted")
        used = true
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        var c = chunk
        while (c == null || pos >= c.size) {
            c = stream.next(STALL_MS) ?: return C.RESULT_END_OF_INPUT
            chunk = c
            pos = 0
        }
        val n = min(length, c.size - pos)
        System.arraycopy(c, pos, buffer, offset, n)
        pos += n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        stream.close()
    }

    companion object {
        /** No video for this long counts as a dropped stream (cameras send a keyframe every few seconds). */
        const val STALL_MS = 15_000L
    }
}

/** Android's software decoders before hardware ones. */
@OptIn(UnstableApi::class)
private fun softwareFirst(): MediaCodecSelector = MediaCodecSelector { mime, secure, tunneling ->
    MediaCodecUtil.getDecoderInfos(mime, secure, tunneling).sortedBy { if (it.softwareOnly) 0 else 1 }
}

private fun hasDecoder(mime: String): Boolean = runCatching {
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
}.getOrDefault(false)

/** Seconds to wait for the first decoded frame before giving up on this try. */
private const val FIRST_FRAME_TIMEOUT_S = 10
