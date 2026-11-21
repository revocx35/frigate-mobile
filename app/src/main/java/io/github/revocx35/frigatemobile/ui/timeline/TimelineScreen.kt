package io.github.revocx35.frigatemobile.ui.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.revocx35.frigatemobile.core.DetectionKind
import io.github.revocx35.frigatemobile.core.FrigateException
import io.github.revocx35.frigatemobile.core.MotionSample
import io.github.revocx35.frigatemobile.core.Timeline
import io.github.revocx35.frigatemobile.core.TimelineBucket
import io.github.revocx35.frigatemobile.core.TimelineSpan
import io.github.revocx35.frigatemobile.core.toUserMessage
import io.github.revocx35.frigatemobile.data.Connection
import io.github.revocx35.frigatemobile.ui.components.ErrorBox
import io.github.revocx35.frigatemobile.ui.components.LocalConnection
import io.github.revocx35.frigatemobile.ui.components.epochToLocal
import io.github.revocx35.frigatemobile.ui.components.formatDay
import io.github.revocx35.frigatemobile.ui.components.formatTime
import io.github.revocx35.frigatemobile.ui.components.labelTitle
import io.github.revocx35.frigatemobile.ui.theme.AppIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.ceil
import kotlin.math.max

/** Seconds per row, per bar, per page loaded. */
private const val ROW_S = 900
private const val BUCKET_S = 60
private const val PAGE_S = 6 * 3600
private const val MAX_AGE_S = 31 * 24 * 3600
private val ROW_HEIGHT = 48.dp

/** Timeline colors: the user asked for people in red and animals in yellow. */
object TimelineColors {
    val person = Color(0xFFE53935)
    val animal = Color(0xFFFFC107)
    val other = Color(0xFF42A5F5)
    fun of(kind: DetectionKind?, motion: Color): Color = when (kind) {
        DetectionKind.PERSON -> person
        DetectionKind.ANIMAL -> animal
        DetectionKind.OTHER -> other
        null -> motion
    }
}

/** What the timeline has loaded: [after, top). [buckets] are oldest first. */
private data class TimelineData(
    val top: Double,
    val after: Double,
    val motion: List<MotionSample>,
    val spans: List<TimelineSpan>,
    val buckets: List<TimelineBucket>,
    val exhausted: Boolean,
) {
    val rows: Int get() = ((top - after) / ROW_S).toInt()
    fun rowEnd(i: Int) = top - i.toDouble() * ROW_S
}

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

    private fun now() = System.currentTimeMillis() / 1000.0
    private fun ceilRow(t: Double) = ceil(t / ROW_S) * ROW_S

    /** Motion (empty on Frigate < 0.14, which lacks the endpoint) and the tracked objects that started in range. */
    private suspend fun fetch(after: Double, before: Double): Pair<List<MotionSample>, List<TimelineSpan>> = coroutineScope {
        val motion = async {
            try {
                conn.client.motionActivity(camera, after, before, BUCKET_S)
            } catch (_: FrigateException.NotFound) {
                emptyList()
            }
        }
        // Objects that started up to an hour earlier can still be on screen in this range.
        val events = conn.client.events(camera, limit = 1000, before = before, after = after - 3600)
        motion.await() to Timeline.spans(events)
    }

    private fun build(top: Double, after: Double, motion: List<MotionSample>, spans: List<TimelineSpan>, exhausted: Boolean): TimelineData {
        val m = motion.distinctBy { it.time }.sortedBy { it.time }
        val s = spans.associateBy { it.id }.values.toList()
        return TimelineData(top, after, m, s, Timeline.buckets(after, top, BUCKET_S, m, s, now()), exhausted)
    }

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

    /** First page, or everything again (refresh button). */
    suspend fun reload() = guarded {
        val top = ceilRow(now())
        val after = top - PAGE_S
        val (m, s) = fetch(after, top)
        emptyPages = 0
        data = build(top, after, m, s, exhausted = false)
    }

    /** The next older page, when the list nears its end. */
    suspend fun loadOlder() = guarded {
        val d = data ?: return@guarded
        if (d.exhausted) return@guarded
        val before = d.after
        val after = before - PAGE_S
        val (m, s) = fetch(after, before)
        emptyPages = if (m.none { it.motion > 0 } && s.none { it.start >= after }) emptyPages + 1 else 0
        val exhausted = emptyPages >= 4 || d.top - after >= MAX_AGE_S
        data = build(d.top, after, m + d.motion, s + d.spans, exhausted)
    }

    /** Keeps the newest hour current while the screen is open. */
    suspend fun refreshNewest() = guarded {
        val d = data ?: return@guarded
        val top = ceilRow(now())
        val from = d.top - 3600
        val (m, s) = fetch(from, top)
        val ids = s.map { it.id }.toSet()
        data = build(top, d.after, d.motion.filter { it.time < from } + m, d.spans.filter { it.id !in ids } + s, d.exhausted)
    }
}

/**
 * A camera's activity as a vertical timeline, newest at the top (like Frigate's own review timeline): bars show
 * motion, colored by what was detected (people red, animals yellow, other objects blue). Tapping plays from there.
 */
@Composable
fun TimelineScreen(camera: String, onBack: () -> Unit, onPlay: (title: String, start: Double, end: Double) -> Unit) {
    val conn = LocalConnection.current
    val state = remember(conn, camera) { TimelineState(conn, camera) }
    var title by remember(camera) { mutableStateOf(camera) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()

    LaunchedEffect(state) {
        launch(Dispatchers.Main.immediate) { runCatching { conn.client.config().camera(camera)?.title }.getOrNull()?.let { title = it } }
        launch(Dispatchers.Main.immediate) {
            state.reload()
            while (true) {
                delay(60_000)
                if (list.firstVisibleItemIndex <= 4) state.refreshNewest()
            }
        }
    }
    val nearEnd by remember {
        derivedStateOf {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 12
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { nearEnd && state.data != null && !state.loading }.distinctUntilChanged().filter { it }.collect {
            launch(Dispatchers.Main.immediate) { state.loadOlder() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Timeline · $title") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(AppIcons.Back, "Back") } },
                actions = {
                    IconButton(onClick = { scope.launch(Dispatchers.Main.immediate) { state.reload(); list.scrollToItem(0) } }) {
                        Icon(AppIcons.Refresh, "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        val data = state.data
        Column(Modifier.fillMaxSize().padding(padding)) {
            Legend()
            HorizontalDivider()
            when {
                data == null && state.error != null -> ErrorBox(state.error!!, Modifier.fillMaxSize()) {
                    scope.launch(Dispatchers.Main.immediate) { state.reload() }
                }
                data == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> {
                    val maxLevel = remember(data) { data.buckets.maxOfOrNull { it.motion } ?: 0f }
                    LazyColumn(Modifier.fillMaxSize().testTag("timeline"), state = list) {
                        items((0 until data.rows).toList(), key = { data.rowEnd(it).toLong() }) { i ->
                            TimelineRow(data, i, now = System.currentTimeMillis() / 1000.0) { t ->
                                val now = System.currentTimeMillis() / 1000.0
                                val start = (t - 5).coerceAtLeast(data.after)
                                onPlay("$title · ${formatDay(epochToLocal(start).toLocalDate())} ${formatTime(start)}", start, minOf(now, start + 1800))
                            }
                        }
                        item(key = "footer") {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                when {
                                    state.loading -> CircularProgressIndicator(Modifier.size(24.dp))
                                    state.error != null -> TextButton(onClick = { scope.launch(Dispatchers.Main.immediate) { state.loadOlder() } }) {
                                        Text("Couldn't load older activity. Retry")
                                    }
                                    data.exhausted -> Text(
                                        if (maxLevel == 0f && data.spans.isEmpty()) "No activity recorded for this camera" else "No older activity",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Legend() {
    val motion = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("timeline_legend"),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
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
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

/** One quarter hour: time label on the left, ticks, bars around a dotted center line, object labels on the right. */
@Composable
private fun TimelineRow(data: TimelineData, index: Int, now: Double, onTap: (Double) -> Unit) {
    val end = data.rowEnd(index)
    val start = end - ROW_S
    val local = epochToLocal(end)
    val midnight = local.hour == 0 && local.minute == 0
    val first = ((start - data.after) / BUCKET_S).toInt()
    val buckets = data.buckets.subList(first.coerceIn(0, data.buckets.size), (first + ROW_S / BUCKET_S).coerceIn(0, data.buckets.size))
    val spans = data.spans.filter { it.start < end && (it.end ?: now) > start }
    val labels = spans.filter { it.start >= start }.groupBy { it.label }
        .map { (label, s) -> Triple(s.minOf { it.start }, if (s.size > 1) "${labelTitle(label)} ×${s.size}" else labelTitle(label), s.first().kind) }
        .sortedByDescending { it.first }

    val scheme = MaterialTheme.colorScheme
    val motionColor = scheme.onSurfaceVariant.copy(alpha = 0.5f)
    val tickColor = scheme.outline
    val lineColor = scheme.outlineVariant
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall
    val description = buildString {
        append(formatTime(end))
        if (spans.isNotEmpty()) append(": ").append(spans.map { labelTitle(it.label) }.distinct().joinToString())
    }

    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT).semantics { contentDescription = description }.testTag("timeline_row_$index")) {
        Text(
            if (midnight) formatDay(local.toLocalDate()) else formatTime(end),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (midnight) FontWeight.Bold else FontWeight.Normal),
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.width(76.dp).padding(start = 12.dp),
        )
        Canvas(
            Modifier.fillMaxSize().pointerInput(end) {
                detectTapGestures { p -> onTap(end - p.y / size.height * ROW_S) }
            },
        ) {
            val h = size.height
            fun y(t: Double) = ((end - t) / ROW_S * h).toFloat()
            val barArea = size.width * 0.55f
            val cx = barArea / 2f
            val maxHalf = barArea / 2f - 8.dp.toPx()

            // Detected objects: a band behind their bars, like the review highlights in Frigate's web UI.
            for (s in spans) {
                val top = y((s.end ?: now).coerceAtMost(end)).coerceAtLeast(0f)
                val bottom = y(s.start.coerceAtLeast(start)).coerceAtMost(h)
                drawRect(TimelineColors.of(s.kind, motionColor).copy(alpha = 0.16f), Offset(0f, top), Size(barArea, max(2.dp.toPx(), bottom - top)))
            }
            // Ticks: long at the quarter hour, short every five minutes.
            drawLine(tickColor, Offset(0f, 0f), Offset(10.dp.toPx(), 0f), 1.dp.toPx())
            for (m in 1..2) drawLine(tickColor, Offset(0f, h * m / 3f), Offset(5.dp.toPx(), h * m / 3f), 1.dp.toPx())
            drawLine(lineColor, Offset(cx, 0f), Offset(cx, h), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))

            val thick = 2.dp.toPx()
            for (b in buckets) {
                if (b.motion <= 0f && b.kind == null) continue
                val half = max(if (b.kind != null) 6.dp.toPx() else 2.dp.toPx(), b.motion * maxHalf)
                val yb = (y(b.start + BUCKET_S) + y(b.start)) / 2f
                drawRect(TimelineColors.of(b.kind, motionColor), Offset(cx - half, yb - thick / 2f), Size(half * 2f, thick))
            }

            // Now marker, then object labels next to the bars, newest on top, never overlapping.
            var nextTop = 0f
            if (now in start..end) {
                val yn = y(now)
                drawLine(TimelineColors.person, Offset(0f, yn), Offset(barArea, yn), 2.dp.toPx())
                val nowText = textMeasurer.measure("Now", TextStyle(color = TimelineColors.person, fontSize = 10.sp, fontWeight = FontWeight.Bold))
                val ny = (yn - nowText.size.height / 2f).coerceAtLeast(0f)
                drawText(nowText, topLeft = Offset(barArea + 4.dp.toPx(), ny))
                nextTop = ny + nowText.size.height
            }
            for ((t, text, kind) in labels) {
                val layout = textMeasurer.measure(text, labelStyle.copy(color = TimelineColors.of(kind, motionColor), fontWeight = FontWeight.Medium))
                val ly = max(nextTop, y(t) - layout.size.height / 2f)
                if (ly + layout.size.height > h) break
                drawText(layout, topLeft = Offset(barArea + 4.dp.toPx(), ly))
                nextTop = ly + layout.size.height
            }
        }
    }
}
