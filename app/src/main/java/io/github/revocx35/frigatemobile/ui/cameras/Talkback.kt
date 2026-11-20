package io.github.revocx35.frigatemobile.ui.cameras

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.revocx35.frigatemobile.core.toUserMessage
import io.github.revocx35.frigatemobile.ui.components.LocalConnection
import io.github.revocx35.frigatemobile.ui.theme.AppIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.concurrent.thread
import io.github.revocx35.frigatemobile.core.FrigateException
import io.github.revocx35.frigatemobile.core.WebRtcSignaling
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.Closeable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Two-way audio: the phone's microphone to the camera's speaker, the way Frigate's web UI does it. A WebRTC peer
 * connection with one send-only audio track, signaled over go2rtc's websocket ([WebRtcSignaling], through the
 * session client: cookie + pinning). go2rtc picks a codec the camera takes (PCMA/PCMU for Tapo, XMeye/DVRIP, ONVIF
 * cameras; libwebrtc has G.711 built in) and forwards the RTP to the camera's backchannel without transcoding.
 * Media is DTLS-SRTP straight to go2rtc's WebRTC port (8555); no STUN/TURN servers of any third party are used.
 */
class TalkSession private constructor(
    private val audio: JavaAudioDeviceModule,
    private val factory: PeerConnectionFactory,
    private val source: org.webrtc.AudioSource,
) : Closeable {
    private var pc: PeerConnection? = null
    private var signaling: WebRtcSignaling? = null
    private val connected = CompletableDeferred<Unit>()
    @Volatile private var closed = false

    /** Called once when an established connection drops (not after [close]). */
    @Volatile var onLost: ((String) -> Unit)? = null

    private val observer = object : PeerConnection.Observer {
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> connected.complete(Unit)
                PeerConnection.PeerConnectionState.FAILED, PeerConnection.PeerConnectionState.DISCONNECTED -> {
                    val wasUp = connected.isCompleted && !connected.isCancelled
                    connected.completeExceptionally(FrigateException.Protocol(UNREACHABLE))
                    if (wasUp && !closed) onLost?.invoke("The talk connection to the camera dropped.")
                }
                else -> Unit
            }
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            // The offer already carries the candidates gathered so far; late ones are trickled.
            signaling?.sendCandidate(candidate.sdp)
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit)
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
    }
    private val gathered = CompletableDeferred<Unit>()

    private val lock = Any()
    private var busy = false
    private var disposed = false

    /**
     * Connects to [url] (`MediaUrls.webrtc`) and returns once audio flows. Throws with a user-facing message when the
     * camera or port 8555 can't be reached. [close] may be called meanwhile from another thread: it then takes effect
     * when this returns, so nothing native is freed under a running call.
     */
    suspend fun connect(http: OkHttpClient, url: HttpUrl) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            check(!busy && pc == null) { "connect() is single-use" }
            if (closed) throw CancellationException("closed")
            busy = true
        }
        try {
            establish(http, url)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw FrigateException.Protocol(UNREACHABLE)
        } finally {
            synchronized(lock) {
                busy = false
                if (closed) dispose()
            }
        }
    }

    private suspend fun establish(http: OkHttpClient, url: HttpUrl) {
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        val pc = factory.createPeerConnection(config, observer) ?: throw FrigateException.Protocol("WebRTC is not available on this phone.")
        this.pc = pc
        val track = factory.createAudioTrack("talk", source)
        pc.addTransceiver(track, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY))

        val offer = pc.awaitSdp { pc.createOffer(it, MediaConstraints()) }
        pc.awaitSet { pc.setLocalDescription(it, offer) }
        // Host candidates take milliseconds on a LAN; send what we have after 2 s and trickle the rest.
        withTimeoutOrNull(2_000) { gathered.await() }
        val sdp = pc.localDescription?.description ?: offer.description

        val signaling = WebRtcSignaling.open(http, url, sdp)
        this.signaling = signaling
        pc.awaitSet { pc.setRemoteDescription(it, SessionDescription(SessionDescription.Type.ANSWER, signaling.answer)) }
        val mid = pc.transceivers.firstOrNull()?.mid ?: "0"
        signaling.onCandidate { line ->
            synchronized(lock) { if (!disposed) pc.addIceCandidate(IceCandidate(mid, 0, line.removePrefix("a="))) }
        }
        withTimeout(CONNECT_TIMEOUT_MS) { connected.await() }
    }

    /** Stops talking and frees the microphone. Blocking (native teardown): call it off the main thread. */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            if (!busy) dispose()
        }
    }

    private fun dispose() {
        if (disposed) return
        disposed = true
        connected.cancel()
        signaling?.close()
        pc?.dispose()
        source.dispose()
        factory.dispose()
        audio.release()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000L
        const val UNREACHABLE = "Couldn't reach the camera's talk channel. Frigate's WebRTC port (8555) must be " +
            "reachable from this phone and listed in go2rtc's webrtc candidates."

        @Volatile private var initialized = false

        private fun init(context: Context) {
            if (initialized) return
            synchronized(this) {
                if (initialized) return
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions(),
                )
                initialized = true
            }
        }

        /** Prepares the microphone and WebRTC (no network yet). The caller must hold RECORD_AUDIO; then [connect]. */
        fun create(context: Context, onMicError: (String) -> Unit): TalkSession {
            init(context)
            val micError = { msg: String -> onMicError("The microphone failed: $msg") }
            val audio = JavaAudioDeviceModule.builder(context.applicationContext)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                    override fun onWebRtcAudioRecordInitError(message: String) = micError(message)
                    override fun onWebRtcAudioRecordStartError(
                        code: JavaAudioDeviceModule.AudioRecordStartErrorCode,
                        message: String,
                    ) = micError(message)
                    override fun onWebRtcAudioRecordError(message: String) = micError(message)
                })
                .createAudioDeviceModule()
            val factory = PeerConnectionFactory.builder().setAudioDeviceModule(audio).createPeerConnectionFactory()
            val source = factory.createAudioSource(MediaConstraints())
            return TalkSession(audio, factory, source)
        }
    }
}

private suspend fun PeerConnection.awaitSdp(block: (SdpObserver) -> Unit): SessionDescription =
    suspendCancellableCoroutine { c ->
        block(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription) = c.resume(d)
            override fun onCreateFailure(error: String?) = c.resumeWithException(FrigateException.Protocol("WebRTC: ${error ?: "offer failed"}"))
            override fun onSetSuccess() = Unit
            override fun onSetFailure(error: String?) = Unit
        })
    }

private suspend fun PeerConnection.awaitSet(block: (SdpObserver) -> Unit): Unit =
    suspendCancellableCoroutine { c ->
        block(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription) = Unit
            override fun onCreateFailure(error: String?) = Unit
            override fun onSetSuccess() = c.resume(Unit)
            override fun onSetFailure(error: String?) = c.resumeWithException(FrigateException.Protocol("WebRTC: ${error ?: "the camera's answer was refused"}"))
        })
    }

enum class TalkState { Idle, Connecting, Talking }

/** Talk button state for one camera screen: asks for the microphone, connects, stops on leave/background. */
class TalkController internal constructor(
    private val context: Context,
    private val http: OkHttpClient,
    private val url: HttpUrl,
    private val scope: CoroutineScope,
    private val onMessage: (String) -> Unit,
) {
    var state by mutableStateOf(TalkState.Idle)
        private set
    private var session: TalkSession? = null
    private var job: Job? = null
    internal var requestPermission: () -> Unit = {}

    fun toggle() {
        if (state != TalkState.Idle) return stop()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
        else requestPermission()
    }

    internal fun start() {
        if (state != TalkState.Idle) return
        state = TalkState.Connecting
        val failed = { msg: String -> scope.launch(Dispatchers.Main.immediate) { if (session != null) { stop(); onMessage(msg) } } }
        val s = try {
            TalkSession.create(context) { msg -> failed(msg) }
        } catch (e: LinkageError) { // no libwebrtc for this CPU (the APK ships ARM and x86_64 only)
            state = TalkState.Idle
            return onMessage("Talking isn't supported on this phone.")
        }
        session = s
        s.onLost = { msg -> failed(msg) }
        job = scope.launch(Dispatchers.Main.immediate) {
            try {
                s.connect(http, url)
                if (session === s) state = TalkState.Talking
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (session === s) {
                    stop()
                    onMessage(e.toUserMessage())
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        session?.let { s -> thread(name = "talk-close") { s.close() } }
        session = null
        state = TalkState.Idle
    }
}

/**
 * A [TalkController] for go2rtc [stream] (one whose camera has a talkback channel), or null without one. Talking stops
 * when the screen goes to the background or is left.
 */
@Composable
fun rememberTalkController(stream: String?, onMessage: (String) -> Unit): TalkController? {
    if (stream == null) return null
    val conn = LocalConnection.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val message by rememberUpdatedState(onMessage)
    val controller = remember(conn, stream) {
        TalkController(context.applicationContext, conn.http, conn.client.urls.webrtc(stream), scope) { message(it) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) controller.start() else message("Allow the microphone to talk through the camera.")
    }
    controller.requestPermission = { permission.launch(Manifest.permission.RECORD_AUDIO) }
    DisposableEffect(controller) { onDispose { controller.stop() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { controller.stop() }
    return controller
}

/** Mic button: tap to talk through the camera's speaker, tap again to stop. */
@Composable
fun TalkButton(controller: TalkController, overlay: Boolean = false) {
    val state = controller.state
    val active = state != TalkState.Idle
    val colors = when {
        state == TalkState.Talking -> IconButtonDefaults.iconButtonColors(
            containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError,
        )
        overlay -> IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f), contentColor = Color.White)
        else -> IconButtonDefaults.iconButtonColors()
    }
    IconButton(onClick = controller::toggle, colors = colors, modifier = Modifier.testTag("talk")) {
        if (state == TalkState.Connecting) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
        } else {
            Icon(AppIcons.Mic, if (active) "Stop talking" else "Talk")
        }
    }
}
