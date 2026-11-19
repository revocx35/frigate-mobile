package io.github.revocx35.frigatemobile.ui.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.revocx35.frigatemobile.core.ReviewCounts
import io.github.revocx35.frigatemobile.core.ReviewItem
import io.github.revocx35.frigatemobile.core.Severity
import io.github.revocx35.frigatemobile.core.toUserMessage
import io.github.revocx35.frigatemobile.ui.components.EmptyBox
import io.github.revocx35.frigatemobile.ui.components.FrigateImage
import io.github.revocx35.frigatemobile.ui.components.LoadContent
import io.github.revocx35.frigatemobile.ui.components.LocalConnection
import io.github.revocx35.frigatemobile.ui.components.Tag
import io.github.revocx35.frigatemobile.ui.components.epochToLocal
import io.github.revocx35.frigatemobile.ui.components.formatDay
import io.github.revocx35.frigatemobile.ui.components.formatDuration
import io.github.revocx35.frigatemobile.ui.components.formatTime
import io.github.revocx35.frigatemobile.ui.components.labelIcon
import io.github.revocx35.frigatemobile.ui.components.labelTitle
import io.github.revocx35.frigatemobile.ui.components.rememberLoader
import io.github.revocx35.frigatemobile.ui.theme.AppIcons
import io.github.revocx35.frigatemobile.ui.theme.LocalStatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private data class ReviewData(val items: List<ReviewItem>, val counts: ReviewCounts?, val cameraTitles: Map<String, String>)

/** Frigate's review queue: alerts and detections, newest first, optionally only unreviewed ones. */
@Composable
fun ReviewScreen(onOpen: (String) -> Unit) {
    val conn = LocalConnection.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var onlyUnreviewed by rememberSaveable { mutableStateOf(true) }
    var camera by rememberSaveable { mutableStateOf<String?>(null) }
    val severity = if (tab == 0) Severity.Alert else Severity.Detection
    val loader = rememberLoader(conn, severity, onlyUnreviewed, camera) {
        coroutineScope {
            val counts = async { runCatching { conn.client.reviewCounts() }.getOrNull() }
            val titles = async { runCatching { conn.client.config().cameras.associate { it.name to it.title } }.getOrDefault(emptyMap()) }
            val items = conn.client.reviews(
                severity = severity,
                reviewed = if (onlyUnreviewed) false else null,
                cameras = camera?.let(::listOf),
                limit = 200,
            )
            ReviewData(items, counts.await(), titles.await())
        }
    }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var cameraMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Review") },
                    actions = {
                        val items = loader.value?.items.orEmpty().filterNot { it.hasBeenReviewed || it.inProgress }
                        IconButton(
                            enabled = items.isNotEmpty(),
                            onClick = {
                                scope.launch(Dispatchers.Main.immediate) {
                                    try {
                                        conn.client.markReviewed(items.map { it.id })
                                        snackbar.showSnackbar("Marked ${items.size} as reviewed")
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        snackbar.showSnackbar(e.toUserMessage())
                                    }
                                    loader.reload()
                                }
                            },
                            modifier = Modifier.testTag("mark_all"),
                        ) { Icon(AppIcons.DoneAll, "Mark all as reviewed") }
                    },
                )
                val counts = loader.value?.counts
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { CountLabel("Alerts", counts?.unreviewedAlerts) })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { CountLabel("Detections", counts?.unreviewedDetections) })
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = onlyUnreviewed,
                        onClick = { onlyUnreviewed = !onlyUnreviewed },
                        label = { Text("Unreviewed only") },
                        modifier = Modifier.testTag("unreviewed_filter"),
                    )
                    Box {
                        FilterChip(
                            selected = camera != null,
                            onClick = { cameraMenu = true },
                            label = { Text(camera?.let { loader.value?.cameraTitles?.get(it) ?: it } ?: "All cameras") },
                            trailingIcon = { Icon(AppIcons.ExpandMore, null, Modifier.size(18.dp)) },
                        )
                        DropdownMenu(expanded = cameraMenu, onDismissRequest = { cameraMenu = false }) {
                            DropdownMenuItem(text = { Text("All cameras") }, onClick = { camera = null; cameraMenu = false })
                            loader.value?.cameraTitles?.forEach { (name, title) ->
                                DropdownMenuItem(text = { Text(title) }, onClick = { camera = name; cameraMenu = false })
                            }
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LoadContent(loader, Modifier.padding(padding)) { data ->
            PullToRefreshBox(isRefreshing = loader.refreshing, onRefresh = loader::reload, modifier = Modifier.padding(padding).fillMaxSize()) {
                if (data.items.isEmpty()) {
                    EmptyBox(
                        AppIcons.CheckCircle,
                        if (onlyUnreviewed) "All caught up" else "Nothing here",
                        text = if (onlyUnreviewed) "No unreviewed ${if (tab == 0) "alerts" else "detections"}." else null,
                    )
                } else {
                    val today = java.time.LocalDate.now()
                    LazyColumn(Modifier.fillMaxSize().testTag("review_list")) {
                        var lastDay: java.time.LocalDate? = null
                        data.items.forEach { r ->
                            val day = epochToLocal(r.startTime).toLocalDate()
                            if (day != lastDay) {
                                lastDay = day
                                item(key = "day-$day") {
                                    Text(
                                        formatDay(day, today),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                                    )
                                }
                            }
                            item(key = r.id) { ReviewRow(r, data.cameraTitles[r.camera] ?: r.camera) { onOpen(r.id) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CountLabel(text: String, count: Int?) {
    if (count != null && count > 0) {
        BadgedBox(badge = { Badge { Text(if (count > 99) "99+" else count.toString()) } }) { Text(text, Modifier.padding(end = 12.dp)) }
    } else {
        Text(text)
    }
}

/** One review item: thumbnail, labels, camera, time and length. Reviewed items are dimmed. */
@Composable
fun ReviewRow(item: ReviewItem, cameraTitle: String?, onClick: () -> Unit) {
    val conn = LocalConnection.current
    val status = LocalStatusColors.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp).testTag("review_${item.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FrigateImage(
            url = conn.client.urls.reviewThumb(item)?.toString(),
            contentDescription = null,
            modifier = Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).alpha(if (item.hasBeenReviewed) 0.6f else 1f),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val first = item.labels.firstOrNull()
                if (first != null) Icon(labelIcon(first), null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    item.labels.joinToString(", ") { labelTitle(it) }.ifBlank { "Activity" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (item.hasBeenReviewed) FontWeight.Normal else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                listOfNotNull(cameraTitle, formatTime(item.startTime), item.endTime?.let { formatDuration(it - item.startTime) })
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (item.inProgress) Tag("Live", status.alert)
                if (item.isAlert) Tag("Alert", status.alert) else Tag("Detection", status.detection)
                if (item.data.zones.isNotEmpty()) Tag(item.data.zones.joinToString(", ") { labelTitle(it) }, MaterialTheme.colorScheme.primary)
            }
        }
    }
}
