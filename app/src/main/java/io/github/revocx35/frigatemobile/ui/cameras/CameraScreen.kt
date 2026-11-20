package io.github.revocx35.frigatemobile.ui.cameras

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.revocx35.frigatemobile.core.CameraFeature
import io.github.revocx35.frigatemobile.core.CameraInfo
import io.github.revocx35.frigatemobile.core.PtzInfo
import io.github.revocx35.frigatemobile.core.toUserMessage
import io.github.revocx35.frigatemobile.data.AppSettings
import io.github.revocx35.frigatemobile.data.SettingsStore
import io.github.revocx35.frigatemobile.ui.components.EmptyBox
import io.github.revocx35.frigatemobile.ui.components.LoadContent
import io.github.revocx35.frigatemobile.ui.components.LocalConnection
import io.github.revocx35.frigatemobile.ui.components.SectionTitle
import io.github.revocx35.frigatemobile.ui.components.rememberLoader
import io.github.revocx35.frigatemobile.ui.player.FullscreenEffect
import io.github.revocx35.frigatemobile.ui.review.ReviewRow
import io.github.revocx35.frigatemobile.ui.theme.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One camera: live view (stream choice, sound), PTZ, its switches (admins), recent activity, recordings. */
@Composable
fun CameraScreen(
    camera: String,
    settings: AppSettings,
    store: SettingsStore,
    onBack: () -> Unit,
    onOpenReview: (String) -> Unit,
    onOpenRecordings: (String) -> Unit,
) {
    val conn = LocalConnection.current
    val loader = rememberLoader(conn, camera) {
        coroutineScope {
            val reviews = async { conn.client.reviews(cameras = listOf(camera), limit = 15) }
            val cam = conn.client.config().camera(camera)
            cam to reviews.await()
        }
    }
    val cam = loader.value?.first
    // ONVIF answers can take seconds (Frigate connects to the camera on first use): load apart from the screen.
    var ptz by remember(camera) { mutableStateOf<PtzInfo?>(null) }
    LaunchedEffect(conn, camera, cam?.onvifConfigured) {
        if (cam?.onvifConfigured == true && ptz == null) {
            ptz = try { conn.client.ptzInfo(camera) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        }
    }
    val socket = if (ptz?.available == true) rememberFrigateSocket() else null

    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var muted by rememberSaveable { mutableStateOf(true) }
    val streams = cam?.liveStreams.orEmpty()
    var chosen by remember(camera) { mutableStateOf(store.liveStream(camera)) }
    val streamLabel = chosen?.takeIf { it in streams } ?: streams.keys.firstOrNull()
    val go2rtcStream = if (settings.liveQuality.full) streamLabel?.let { streams[it] } else null
    val mjpegUrl = conn.client.urls.mjpeg(camera, settings.liveQuality.fps, settings.liveQuality.height, settings.showBoxes).toString()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val onPtzError: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }
    // Two-way audio: the first of the camera's go2rtc streams whose camera has a talkback channel (Frigate's
    // `/api/go2rtc/streams/<name>` lists an "audio, sendonly" media then). Probed apart from the screen: a Tapo
    // source logs in to the camera for it.
    var talkStream by remember(camera) { mutableStateOf<String?>(null) }
    LaunchedEffect(conn, camera, cam?.liveStreams) {
        talkStream = cam?.liveStreams?.values?.distinct().orEmpty().firstOrNull { s ->
            try { conn.client.go2rtcStream(s).twoWayAudio } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
        }
    }
    val talk = rememberTalkController(talkStream, onPtzError)

    if (fullscreen) {
        FullscreenEffect { fullscreen = false }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            LiveView(go2rtcStream, mjpegUrl, "Live view of ${cam?.title ?: camera}", muted, Modifier.fillMaxSize())
            Row(Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp)) {
                talk?.let { TalkButton(it, overlay = true) }
                if (go2rtcStream != null) {
                    IconButton(onClick = { muted = !muted }, colors = overlayButtonColors()) {
                        Icon(if (muted) AppIcons.VolumeOff else AppIcons.VolumeUp, if (muted) "Unmute" else "Mute")
                    }
                }
                IconButton(onClick = { fullscreen = false }, colors = overlayButtonColors()) { Icon(AppIcons.FullscreenExit, "Exit full screen") }
            }
            val info = ptz
            if (socket != null && info != null) {
                PtzControls(camera, info, socket, onPtzError, Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(16.dp), overlay = true)
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(cam?.title ?: camera) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(AppIcons.Back, "Back") } },
                actions = {
                    talk?.let { TalkButton(it) }
                    if (go2rtcStream != null) {
                        IconButton(onClick = { muted = !muted }, modifier = Modifier.testTag("mute")) {
                            Icon(if (muted) AppIcons.VolumeOff else AppIcons.VolumeUp, if (muted) "Unmute" else "Mute")
                        }
                    }
                    IconButton(onClick = { fullscreen = true }) { Icon(AppIcons.Fullscreen, "Full screen") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LoadContent(loader, Modifier.padding(padding)) { (cam, reviews) ->
            if (cam == null) {
                EmptyBox(AppIcons.Videocam, "Camera not found", Modifier.padding(padding), text = "\"$camera\" is no longer in Frigate's configuration.")
                return@LoadContent
            }
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                item {
                    LiveView(go2rtcStream, mjpegUrl, "Live view of ${cam.title}", muted, Modifier.fillMaxWidth().aspectRatio(cam.aspectRatio))
                }
                if (settings.liveQuality.full && streams.size > 1) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(AppIcons.Stream, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            streams.keys.forEach { label ->
                                FilterChip(
                                    selected = label == streamLabel,
                                    onClick = { chosen = label; store.setLiveStream(camera, label) },
                                    label = { Text(label) },
                                    modifier = Modifier.testTag("stream_$label"),
                                )
                            }
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        FilledTonalButton(onClick = { onOpenRecordings(camera) }, modifier = Modifier.weight(1f).testTag("recordings")) {
                            Icon(AppIcons.Movie, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Recordings")
                        }
                    }
                }
                val info = ptz
                if (socket != null && info != null) {
                    item { SectionTitle("Pan, tilt and zoom") }
                    item { PtzControls(camera, info, socket, onPtzError) }
                }
                if (conn.account.isAdmin) {
                    item { SectionTitle("Camera settings") }
                    item { FeatureSwitches(cam) { msg -> snackbar.showSnackbar(msg) } }
                }
                item { SectionTitle("Recent activity") }
                if (reviews.isEmpty()) {
                    item { Text("Nothing recorded yet.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                items(reviews, key = { it.id }) { r -> ReviewRow(r, cameraTitle = null) { onOpenReview(r.id) } }
            }
        }
    }
}

@Composable
private fun overlayButtonColors() =
    IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f), contentColor = Color.White)

/** Runtime switches (Frigate 0.17+ API). Shown optimistically; Frigate applies them within a second. */
@Composable
private fun FeatureSwitches(cam: CameraInfo, onMessage: suspend (String) -> Unit) {
    val conn = LocalConnection.current
    val scope = rememberCoroutineScope()
    val overrides = remember(cam) { mutableStateMapOf<CameraFeature, Boolean>() }
    val busy = remember { mutableStateMapOf<CameraFeature, Boolean>() }
    val features = buildList {
        add(CameraFeature.Detect to cam.detect)
        if (cam.recordConfigured) add(CameraFeature.Recordings to cam.record)
        add(CameraFeature.Snapshots to cam.snapshots)
        if (cam.audioConfigured) add(CameraFeature.Audio to cam.audio)
    }
    features.forEach { (feature, current) ->
        val checked = overrides[feature] ?: current
        ListItem(
            headlineContent = { Text(feature.label) },
            trailingContent = {
                Switch(
                    checked = checked,
                    enabled = busy[feature] != true,
                    onCheckedChange = { on ->
                        overrides[feature] = on
                        busy[feature] = true
                        scope.launch(Dispatchers.Main.immediate) {
                            try {
                                conn.client.setCameraFeature(cam.name, feature, on)
                                delay(300)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                overrides[feature] = !on
                                onMessage(e.toUserMessage())
                            } finally {
                                busy[feature] = false
                            }
                        }
                    },
                    modifier = Modifier.testTag("switch_${feature.api}"),
                )
            },
        )
    }
}
