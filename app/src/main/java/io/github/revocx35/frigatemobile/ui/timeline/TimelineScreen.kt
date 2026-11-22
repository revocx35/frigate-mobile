package io.github.revocx35.frigatemobile.ui.timeline

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import io.github.revocx35.frigatemobile.core.DetectionKind
import io.github.revocx35.frigatemobile.core.FrigateException
import io.github.revocx35.frigatemobile.core.MotionSample
import io.github.revocx35.frigatemobile.core.RecordingMap
import io.github.revocx35.frigatemobile.core.Timeline
import io.github.revocx35.frigatemobile.core.TimelineScale
import io.github.revocx35.frigatemobile.core.TimelineSpan
import io.github.revocx35.frigatemobile.core.toUserMessage
import io.github.revocx35.frigatemobile.data.Connection
import io.github.revocx35.frigatemobile.ui.components.ErrorBox
import io.github.revocx35.frigatemobile.ui.components.LocalConnection
import io.github.revocx35.frigatemobile.ui.components.epochToLocal
import io.github.revocx35.frigatemobile.ui.components.formatDay
import io.github.revocx35.frigatemobile.ui.components.formatTime
import io.github.revocx35.frigatemobile.ui.components.labelTitle
import io.github.revocx35.frigatemobile.ui.player.FullscreenEffect
import io.github.revocx35.frigatemobile.ui.player.VideoPlayer
import io.github.revocx35.frigatemobile.ui.player.rememberFrigatePlayer
import io.github.revocx35.frigatemobile.ui.theme.AppIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Motion record step asked from Frigate, page size, how far back the timeline goes. */
private const val MOTION_S = 30
private const val PAGE_S = 6 * 3600
private const val MAX_AGE_S = 31 * 24 * 3600
private const val HOUR = 3600.0

/** Timeline colors: the user asked for people in red, animals in yellow and a yellow "now viewing" line. */
object TimelineColors {
    val person = Color(0xFFE53935)
    val animal = Color(0xFFFFB300)
    val other = Color(0xFF42A5F5)
    val playhead = Color(0xFFFFEB3B)
    fun of(kind: DetectionKind?, motion: Color): Color = when (kind) {
        DetectionKind.PERSON -> person
        DetectionKind.ANIMAL -> animal
        DetectionKind.OTHER -> other
        null -> motion
    }
}

private fun now() = System.currentTimeMillis() / 1000.0

/** What the timeline has loaded: activity in [after, top). [scale] is the busiest motion value loaded. */
private data class TimelineData(
    val top: Double,
    val after: Double,
    val motion: List<MotionSample>,
    val spans: List<TimelineSpan>,
    val exhausted: Boolean,
) {
    val scale: Double = motion.maxOfOrNull { it.motion } ?: 0.0
}

/** Loads the camera's activity page by page (newest first). */
@Stable
private class TimelineState(private val conn: Connection, private val camera: String) {
    var data by mutableStateOf<TimelineData?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    private val mutex = Mutex()
    private var emptyPages = 0

    /** Motion (empty on Frigate < 0.14, which lacks the endpoint) and the tracked objects that started in range. */
    private suspend fun fetch(after: Double, before: Double): Pair<List<MotionSample>, List<TimelineSpan>> = coroutineScope {
        val motion = async {
            try {
                conn.client.motionActivity(camera, after, before, MOTION_S)
            } catch (_: FrigateException.NotFound) {
                emptyList()
            }
        }
        // Objects that started up to an hour earlier can still be on screen in this range.
        val events = conn.client.events(camera, limit = 1000, before = before, after = after - 3600)
        motion.await() to Timeline.spans(events)
    }

    private fun build(top: Double, after: Double, motion: List<MotionSample>, spans: List<TimelineSpan>, exhausted: Boolean) =
        TimelineData(top, after, motion.distinctBy { it.time }.sortedBy { it.time }, spans.associateBy { it.id }.values.toList(), exhausted)

    private suspend fun guarded(block: suspend () -> Unit) {
        if (!mutex.tryLock()) return
        loading = true
        try {
            block()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.toUserMessage()
        } finally {
            loading = false
            mutex.unlock()
        }
    }

    suspend fun reload() = guarded {
        val top = ceil(now() / 60) * 60
        val after = top - PAGE_S
        val (m, s) = fetch(after, top)
        emptyPages = 0
        data = build(top, after, m, s, exhausted = false)
    }

    suspend fun loadOlder() = guarded {
        val d = data ?: return@guarded
        if (d.exhausted) return@guarded
        val after = d.after - PAGE_S
        val (m, s) = fetch(after, d.after)
        emptyPages = if (m.none { it.motion > 0 } && s.none { it.start >= after }) emptyPages + 1 else 0
        data = build(d.top, after, m + d.motion, s + d.spans, emptyPages >= 4 || d.top - after >= MAX_AGE_S)
    }

    /** Keeps the newest hour current while the screen is open. */
    suspend fun refreshNewest() = guarded {
        val d = data ?: return@guarded
        val top = ceil(now() / 60) * 60
        val from = d.top - 3600
        val (m, s) = fetch(from, top)
        val ids = s.map { it.id }.toSet()
        data = build(top, d.after, d.motion.filter { it.time < from } + m, d.spans.filter { it.id !in ids } + s, d.exhausted)
    }
}

/** The recording the player has loaded: one clock hour (up to now) of the camera, as an HLS VOD. */
private data class PlayWindow(val url: String, val start: Double, val end: Double, val map: RecordingMap, val startMs: Long, val generation: Int)

@Stable
private class TimelinePlayback(private val conn: Connection, private val camera: String) {
    var window by mutableStateOf<PlayWindow?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    private var generation = 0

    /** Loads the hour holding [t], positioned at [t] (or the next recorded moment). Returns the time it starts at. */
    suspend fun load(t: Double): Double? {
        loading = true
        try {
            var at = min(t, now())
            var backward = false
            repeat(4) {
                val start = floor(at / HOUR) * HOUR
                val end = min(start + HOUR, now())
                val map = RecordingMap(start, end, if (end > start) conn.client.recordings(camera, start, end) else emptyList())
                val live = end < start + HOUR
                // Forward to the next recorded moment; at the live edge (or going back), the newest one.
                val pos = (if (backward) null else map.positionOf(at))
                    ?: if (live || backward) map.lastTime?.let { map.positionOf(max(start, it - 3)) } else null
                if (pos != null) {
                    message = null
                    window = PlayWindow(conn.client.urls.vod(camera, start, end).toString(), start, end, map, (pos * 1000).toLong(), ++generation)
                    return map.timeAt(pos)
                }
                if (live || backward) {
                    backward = true
                    at = start - 1 // nothing recorded yet this hour: the hour before
                } else {
                    at = end // nothing left in this hour: the next one
                }
            }
            message = "No recording at this time."
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = e.toUserMessage()
            return null
        } finally {
            loading = false
        }
    }
}

/** What part of time the timeline shows: [top] is the time at the canvas's top edge, [spp] seconds per pixel. */
@Stable
private class TimelineView {
    var top by mutableDoubleStateOf(0.0)
    var spp by mutableDoubleStateOf(0.0)
    var height by mutableIntStateOf(0)
    /** Pixels of 40 dp, set on first layout: the zoom limits depend on it. */
    var unit = 1.0
    fun y(t: Double) = ((top - t) / spp).toFloat()
    fun timeAt(y: Float) = top - y * spp
    val bottom: Double get() = top - height * spp
}

/**
 * A camera's activity as a vertical timeline under a recording player, newest at the top (like Frigate's own
 * review timeline). Bars show motion, colored by what was detected (people red, animals yellow, other objects blue).
 * The yellow line marks what the player shows: it follows playback; drag it (or tap the timeline) to look at another
 * moment. Pinch or the +/- buttons zoom; swipe scrolls into the past (loaded as you go).
 */
@Composable
fun TimelineScreen(camera: String, onBack: () -> Unit) {
    val conn = LocalConnection.current
    val state = remember(conn, camera) { TimelineState(conn, camera) }
    val playback = remember(conn, camera) { TimelinePlayback(conn, camera) }
    val view = remember(camera) { TimelineView() }
    var title by remember(camera) { mutableStateOf(camera) }
    var playhead by remember(camera) { mutableDoubleStateOf(now() - 60) }
    var dragging by remember { mutableStateOf(false) }
    var lastTouch by remember { mutableDoubleStateOf(0.0) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val window = playback.window
    val player = window?.let { rememberFrigatePlayer(it.url, it.startMs, it.generation) }

    /** Moves the playhead to [t]; seeks within the loaded hour, or loads another hour when [load] is set. */
    fun seek(t: Double, load: Boolean) {
        val target = min(t, now())
        playhead = target
        val w = playback.window
        val pos = w?.map?.positionOf(target)
        if (w != null && player != null && target >= w.start && target < w.end && pos != null) {
            player.error = null
            player.exo.seekTo((pos * 1000).toLong())
            if (load) player.exo.play()
        } else if (load) {
            scope.launch(Dispatchers.Main.immediate) { playback.load(target)?.let { playhead = it } }
        }
    }

    LaunchedEffect(state) {
        launch(Dispatchers.Main.immediate) { runCatching { conn.client.config().camera(camera)?.title }.getOrNull()?.let { title = it } }
        launch(Dispatchers.Main.immediate) { playback.load(playhead)?.let { playhead = it } }
        launch(Dispatchers.Main.immediate) {
            state.reload()
            while (true) {
                delay(60_000)
                if (view.bottom < now() && view.top > now() - 4 * HOUR) state.refreshNewest()
            }
        }
    }
    // The playhead follows playback; the timeline scrolls along when it would leave the screen.
    LaunchedEffect(player) {
        val p = player ?: return@LaunchedEffect
        while (true) {
            delay(200)
            val w = playback.window ?: continue
            if (dragging || playback.loading || p.exo.playbackState == Player.STATE_IDLE) continue
            if (!p.exo.isPlaying && p.exo.playbackState != Player.STATE_ENDED) continue
            playhead = w.map.timeAt(p.exo.currentPosition / 1000.0)
            if (view.height > 0 && now() - lastTouch > 3 && (view.y(playhead) < 0 || view.y(playhead) > view.height)) {
                view.top = playhead + view.height * 0.3 * view.spp
            }
        }
    }
    // At the end of an hour, carry on with the next one (unless that's the live edge).
    DisposableEffect(player) {
        val p = player ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) {
                val w = playback.window ?: return
                if (s == Player.STATE_ENDED && w.end < now() - 30) {
                    scope.launch(Dispatchers.Main.immediate) { playback.load(w.end)?.let { playhead = it } }
                }
            }
        }
        p.exo.addListener(listener)
        onDispose { p.exo.removeListener(listener) }
    }
    LaunchedEffect(state) {
        snapshotFlow { val d = state.data; d != null && !d.exhausted && !state.loading && view.height > 0 && view.bottom - view.height * view.spp < d.after }
            .distinctUntilChanged().filter { it }.collect { launch(Dispatchers.Main.immediate) { state.loadOlder() } }
    }

    val playerBox: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            when {
                player != null -> VideoPlayer(player, Modifier.fillMaxSize()) { fullscreen = it }
                playback.loading -> CircularProgressIndicator()
            }
            playback.message?.let { msg ->
                Text(msg, color = Color.White, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(8.dp)).padding(12.dp).testTag("player_message"))
            }
        }
    }
    if (fullscreen) {
        FullscreenEffect { fullscreen = false }
        playerBox(Modifier.fillMaxSize())
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Timeline · $title") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(AppIcons.Back, "Back") } },
                actions = {
                    IconButton(onClick = { scope.launch(Dispatchers.Main.immediate) { state.reload() } }) { Icon(AppIcons.Refresh, "Refresh") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            playerBox(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Legend(Modifier.weight(1f))
                IconButton(onClick = { zoom(view, 1.6, view.y(playhead)) }, modifier = Modifier.testTag("zoom_in")) { Icon(AppIcons.ZoomIn, "Zoom in") }
                IconButton(onClick = { zoom(view, 1 / 1.6, view.y(playhead)) }, modifier = Modifier.testTag("zoom_out")) { Icon(AppIcons.ZoomOut, "Zoom out") }
            }
            HorizontalDivider()
            val data = state.data
            when {
                data == null && state.error != null -> ErrorBox(state.error!!, Modifier.fillMaxSize()) {
                    scope.launch(Dispatchers.Main.immediate) { state.reload() }
                }
                data == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> TimelineCanvas(
                    data = data,
                    view = view,
                    playhead = playhead,
                    loadingOlder = state.loading,
                    onTouch = { lastTouch = now() },
                    onDragPlayhead = { t, done ->
                        dragging = !done
                        lastTouch = now()
                        seek(t.coerceIn(data.after, now()), load = done)
                    },
                )
            }
        }
    }
}

/** Zooms by [factor] (> 1 = closer) keeping the time at [focusY] in place. */
private fun zoom(view: TimelineView, factor: Double, focusY: Float) {
    if (view.height <= 0) return
    val y = focusY.coerceIn(0f, view.height.toFloat())
    val t = view.timeAt(y)
    view.spp = (view.spp / factor).coerceIn(minSpp(view), maxSpp(view))
    view.top = t + y * view.spp
}

/** Closest: one 30 s motion record per 40 dp. Farthest: two days on screen. */
private fun minSpp(view: TimelineView) = 30.0 / view.unit
private fun maxSpp(view: TimelineView) = 2 * 86400.0 / max(1, view.height)

@Composable
private fun Legend(modifier: Modifier = Modifier) {
    val motion = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    Row(
        modifier.padding(vertical = 8.dp).testTag("timeline_legend"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendItem(TimelineColors.person, "Person")
        LegendItem(TimelineColors.animal, "Animal")
        LegendItem(TimelineColors.other, "Other")
        LegendItem(motion, "Motion")
    }
}

@Composable
private fun LegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

private val secondsFmt: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)
private fun formatSeconds(t: Double): String = epochToLocal(t).format(secondsFmt)

@Composable
private fun TimelineCanvas(
    data: TimelineData,
    view: TimelineView,
    playhead: Double,
    loadingOlder: Boolean,
    onTouch: () -> Unit,
    onDragPlayhead: (t: Double, done: Boolean) -> Unit,
) {
    val density = LocalDensity.current
    // The gesture handlers outlive recompositions: read the current values through these.
    val currentData by rememberUpdatedState(data)
    val currentPlayhead by rememberUpdatedState(playhead)
    val dragTo by rememberUpdatedState(onDragPlayhead)
    val touched by rememberUpdatedState(onTouch)
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val scope = rememberCoroutineScope()
    var fling by remember { mutableStateOf<Job?>(null) }
    val scheme = MaterialTheme.colorScheme
    val motionColor = scheme.onSurfaceVariant.copy(alpha = 0.5f)
    val tickColor = scheme.outline
    val lineColor = scheme.outlineVariant
    val labelColor = scheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall
    val timeColumn = with(density) { 76.dp.toPx() }
    val handleSlop = with(density) { 28.dp.toPx() }

    fun clampTop() {
        val h = view.height
        if (h <= 0) return
        val maxTop = now() + with(density) { 40.dp.toPx() } * view.spp
        val d = currentData
        val minTop = d.after + h * view.spp * (if (d.exhausted) 1.0 else 0.6)
        view.top = if (minTop > maxTop) maxTop else view.top.coerceIn(minTop, maxTop)
    }
    fun scrollBy(dy: Float) {
        view.top += dy * view.spp
        clampTop()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heightPx = constraints.maxHeight
        LaunchedEffect(heightPx) {
            view.unit = with(density) { 40.dp.toPx() }.toDouble()
            if (view.spp == 0.0) {
                view.height = heightPx
                view.spp = 900.0 / with(density) { 48.dp.toPx() } // 15 minutes per 48 dp
                view.top = now() + with(density) { 40.dp.toPx() } * view.spp
            } else {
                view.height = heightPx
            }
        }
        val visible = data.spans.filter { it.start < view.top && (it.end ?: now()) > view.bottom }
        val description = buildString {
            append("Timeline. Viewing ").append(formatSeconds(playhead)).append('.')
            if (visible.isNotEmpty()) {
                append(" Detections: ")
                append(visible.sortedByDescending { it.start }.joinToString { "${labelTitle(it.label)} ${formatTime(it.start)}" })
            }
        }
        Canvas(
            Modifier.fillMaxSize().testTag("timeline").semantics { contentDescription = description }.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fling?.cancel()
                    touched()
                    val onLine = abs(down.position.y - view.y(currentPlayhead)) < handleSlop
                    val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
                    var moved = 0f
                    var dragged = false
                    var pinched = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            pinched = true
                            val c = event.calculateCentroid(useCurrent = true)
                            zoom(view, event.calculateZoom().toDouble(), c.y)
                            clampTop()
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        val ch = pressed.first()
                        val dy = ch.position.y - ch.previousPosition.y
                        moved += abs(dy)
                        tracker.addPosition(ch.uptimeMillis, ch.position)
                        if (!dragged && moved > touchSlop) dragged = true
                        if (dragged && !pinched) {
                            if (onLine) dragTo(view.timeAt(ch.position.y.coerceIn(0f, view.height.toFloat())), false)
                            else scrollBy(dy)
                            ch.consume()
                        }
                    }
                    touched()
                    when {
                        pinched -> Unit
                        !dragged -> dragTo(view.timeAt(down.position.y), true) // tap: look at that moment
                        onLine -> dragTo(currentPlayhead, true)
                        else -> {
                            val v = tracker.calculateVelocity().y
                            fling = scope.launch {
                                var last = 0f
                                AnimationState(0f, v).animateDecay(exponentialDecay()) {
                                    scrollBy(value - last)
                                    last = value
                                }
                            }
                        }
                    }
                }
            },
        ) {
            val h = size.height
            val nowT = now()
            val top = view.top
            val bottom = view.timeAt(h)
            val spp = view.spp
            if (spp <= 0) return@Canvas
            val barLeft = timeColumn
            val barArea = (size.width - timeColumn) * 0.58f
            val cx = barLeft + barArea / 2f
            val maxHalf = barArea / 2f - 6.dp.toPx()
            fun y(t: Double) = ((top - t) / spp).toFloat()

            // Not loaded yet / before the oldest activity.
            if (bottom < data.after) {
                val ya = y(data.after)
                drawRect(lineColor.copy(alpha = 0.25f), Offset(barLeft, ya), Size(size.width - barLeft, h - ya))
                val msg = textMeasurer.measure(if (data.exhausted) "No older activity" else if (loadingOlder) "Loading…" else "", labelStyle.copy(color = labelColor))
                drawText(msg, topLeft = Offset(cx - msg.size.width / 2f, ya + 8.dp.toPx()))
            }

            // Detected objects: a band behind their bars, like the review highlights in Frigate's web UI.
            val spans = data.spans.filter { it.start < top && (it.end ?: nowT) > bottom }
            for (s in spans) {
                val yt = y(min(s.end ?: nowT, top)).coerceAtLeast(0f)
                val yb = y(max(s.start, bottom)).coerceAtMost(h)
                drawRect(TimelineColors.of(s.kind, motionColor).copy(alpha = 0.16f), Offset(barLeft, yt), Size(barArea, max(2.dp.toPx(), yb - yt)))
            }

            // Ticks and time labels at a step that fits the zoom.
            val (major, minor) = TimelineScale.ticks(56.dp.toPx() * spp)
            var tick = floor(top / minor) * minor
            while (tick > bottom - minor) {
                val yt = y(tick)
                val isMajor = (tick.toLong() + epochToLocal(tick).offset.totalSeconds) % major == 0L
                drawLine(tickColor, Offset(barLeft, yt), Offset(barLeft + (if (isMajor) 10 else 5).dp.toPx(), yt), 1.dp.toPx())
                if (isMajor) {
                    val local = epochToLocal(tick)
                    val midnight = local.hour == 0 && local.minute == 0
                    val text = textMeasurer.measure(
                        if (midnight) formatDay(local.toLocalDate()) else formatTime(tick),
                        labelStyle.copy(color = labelColor, fontWeight = if (midnight) FontWeight.Bold else FontWeight.Normal),
                    )
                    drawText(text, topLeft = Offset(12.dp.toPx(), yt - text.size.height / 2f))
                }
                tick -= minor
            }
            drawLine(lineColor, Offset(cx, 0f), Offset(cx, h), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))

            // Motion bars, aggregated to a step that leaves room between them.
            val step = TimelineScale.bar(4.dp.toPx() * spp)
            val from = floor(max(bottom, data.after) / step) * step
            val to = ceil(min(top, data.top) / step) * step
            if (to > from) {
                val lo = data.motion.binarySearchBy(from) { it.time }.let { if (it < 0) -it - 1 else it }
                val hi = data.motion.binarySearchBy(to) { it.time }.let { if (it < 0) -it - 1 else it }
                val buckets = Timeline.buckets(from, to, step, data.motion.subList(lo, max(lo, hi)), spans, nowT, data.scale)
                val thick = min(step / spp * 0.55, 3.dp.toPx().toDouble()).toFloat().coerceAtLeast(1f)
                for (b in buckets) {
                    if (b.motion <= 0f && b.kind == null) continue
                    val half = max(if (b.kind != null) 6.dp.toPx() else 2.dp.toPx(), b.motion * maxHalf)
                    val yb = (y(b.start + step) + y(b.start)) / 2f
                    drawRect(TimelineColors.of(b.kind, motionColor), Offset(cx - half, yb - thick / 2f), Size(half * 2f, thick))
                }
            }

            // Now marker, then object labels next to the bars, newest on top, never overlapping.
            val labelX = barLeft + barArea + 4.dp.toPx()
            var nextTop = Float.NEGATIVE_INFINITY
            if (nowT in bottom..top) {
                val yn = y(nowT)
                drawLine(TimelineColors.person, Offset(barLeft, yn), Offset(barLeft + barArea, yn), 2.dp.toPx())
                val nowText = textMeasurer.measure("Now", TextStyle(color = TimelineColors.person, fontSize = 10.sp, fontWeight = FontWeight.Bold))
                val ny = yn - nowText.size.height / 2f
                drawText(nowText, topLeft = Offset(labelX, ny))
                nextTop = ny + nowText.size.height
            }
            spans.filter { it.start >= bottom }.groupBy { it.label to floor(it.start / (step * 4)) }
                .map { (key, s) -> Triple(s.minOf { it.start }, if (s.size > 1) "${labelTitle(key.first)} ×${s.size}" else labelTitle(key.first), s.first().kind) }
                .sortedByDescending { it.first }
                .forEach { (t, text, kind) ->
                    val layout = textMeasurer.measure(text, labelStyle.copy(color = TimelineColors.of(kind, motionColor), fontWeight = FontWeight.Medium))
                    val ly = max(nextTop, y(t) - layout.size.height / 2f)
                    if (ly < h) {
                        drawText(layout, topLeft = Offset(labelX, ly))
                        nextTop = ly + layout.size.height
                    }
                }

            // The yellow "now viewing" line.
            val yp = y(playhead)
            if (yp in 0f..h) {
                drawLine(Color.Black.copy(alpha = 0.35f), Offset(barLeft, yp + 1.dp.toPx()), Offset(size.width, yp + 1.dp.toPx()), 2.dp.toPx())
                drawLine(TimelineColors.playhead, Offset(barLeft, yp), Offset(size.width, yp), 2.dp.toPx())
            }
        }

        // The line's handle: the time being viewed, dragged like the line itself.
        val yp = view.y(playhead)
        if (view.spp > 0 && yp in 0f..heightPx.toFloat()) {
            val handleH = with(density) { 24.dp.toPx() }
            Box(
                Modifier
                    .offset { IntOffset(with(density) { 4.dp.roundToPx() }, (yp - handleH / 2f).toInt()) }
                    .background(TimelineColors.playhead, RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
                    .testTag("timeline_playhead")
                    .pointerInput(Unit) {
                        var y = 0f
                        detectVerticalDragGestures(
                            onDragStart = { fling?.cancel(); touched(); y = view.y(currentPlayhead) },
                            onDragEnd = { dragTo(view.timeAt(y), true) },
                            onDragCancel = { dragTo(view.timeAt(y), true) },
                        ) { change, dy ->
                            change.consume()
                            y = (y + dy).coerceIn(0f, view.height.toFloat())
                            dragTo(view.timeAt(y), false)
                        }
                    },
            ) {
                Text(formatSeconds(playhead), color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}
