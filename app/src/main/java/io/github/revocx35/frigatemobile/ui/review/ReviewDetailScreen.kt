package io.github.revocx35.frigatemobile.ui.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.revocx35.frigatemobile.core.FrigateEvent
import io.github.revocx35.frigatemobile.core.ReviewItem
import io.github.revocx35.frigatemobile.ui.components.FrigateImage
import io.github.revocx35.frigatemobile.ui.components.LoadContent
import io.github.revocx35.frigatemobile.ui.components.LocalConnection
import io.github.revocx35.frigatemobile.ui.components.SectionTitle
import io.github.revocx35.frigatemobile.ui.components.Tag
import io.github.revocx35.frigatemobile.ui.components.epochToLocal
import io.github.revocx35.frigatemobile.ui.components.formatDay
import io.github.revocx35.frigatemobile.ui.components.formatDecimal
import io.github.revocx35.frigatemobile.ui.components.formatDuration
import io.github.revocx35.frigatemobile.ui.components.formatTime
import io.github.revocx35.frigatemobile.ui.components.labelIcon
import io.github.revocx35.frigatemobile.ui.components.labelTitle
import io.github.revocx35.frigatemobile.ui.components.rememberLoader
import io.github.revocx35.frigatemobile.ui.player.FullscreenEffect
import io.github.revocx35.frigatemobile.ui.player.VideoPlayer
import io.github.revocx35.frigatemobile.ui.player.rememberFrigatePlayer
import io.github.revocx35.frigatemobile.ui.theme.AppIcons
import io.github.revocx35.frigatemobile.ui.theme.LocalStatusColors
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private data class Detail(val item: ReviewItem, val events: List<FrigateEvent>, val cameraTitle: String)

/** Seconds of recording shown before and after a review item, like Frigate's web UI. */
private const val PAD_BEFORE = 4.0
private const val PAD_AFTER = 4.0

/** A review item: its recording, what was tracked (with snapshots), and it is marked reviewed once opened. */
@Composable
fun ReviewDetailScreen(id: String, onBack: () -> Unit) {
    val conn = LocalConnection.current
    val loader = rememberLoader(conn, id) {
        coroutineScope {
            val item = conn.client.review(id)
            val title = async { runCatching { conn.client.config().camera(item.camera)?.title }.getOrNull() ?: item.camera }
            val events = async { runCatching { conn.client.eventsByIds(item.data.detections) }.getOrDefault(emptyList()) }
            Detail(item, events.await(), title.await())
        }
    }
    // Like the web UI: opening a finished item marks it reviewed.
    LaunchedEffect(loader.value?.item?.id) {
        val item = loader.value?.item ?: return@LaunchedEffect
        if (!item.hasBeenReviewed && !item.inProgress) runCatching { conn.client.markReviewed(listOf(item.id)) }
    }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var snapshot by rememberSaveable { mutableStateOf<String?>(null) }
    if (fullscreen) FullscreenEffect { fullscreen = false }

    Scaffold(
        topBar = {
            if (!fullscreen) {
                TopAppBar(
                    title = { Text(loader.value?.cameraTitle ?: "Review") },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(AppIcons.Back, "Back") } },
                )
            }
        },
    ) { padding ->
        LoadContent(loader, Modifier.padding(padding)) { d ->
            val item = d.item
            val end = item.endTime ?: (System.currentTimeMillis() / 1000.0)
            val url = remember(item.id) { conn.client.urls.vod(item.camera, item.startTime - PAD_BEFORE, end + PAD_AFTER).toString() }
            val player = rememberFrigatePlayer(url)
            if (fullscreen) {
                VideoPlayer(player, Modifier.fillMaxSize()) { fullscreen = it }
                return@LoadContent
            }
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
                item { VideoPlayer(player, Modifier.fillMaxWidth().aspectRatio(16f / 9f)) { fullscreen = it } }
                item { Summary(item) }
                if (d.events.isNotEmpty()) {
                    item { SectionTitle("Tracked objects") }
                    items(d.events, key = { it.id }) { e -> EventRow(e) { if (e.hasSnapshot) snapshot = e.id } }
                }
            }
        }
    }
    snapshot?.let { eventId -> SnapshotDialog(conn.client.urls.eventSnapshot(eventId).toString()) { snapshot = null } }
}

@Composable
private fun Summary(item: ReviewItem) {
    val status = LocalStatusColors.current
    Column(Modifier.padding(16.dp)) {
        Text(item.labels.joinToString(", ") { labelTitle(it) }.ifBlank { "Activity" }, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        val day = formatDay(epochToLocal(item.startTime).toLocalDate())
        Text(
            listOfNotNull(day, formatTime(item.startTime), item.endTime?.let { formatDuration(it - item.startTime) } ?: "in progress").joinToString(" · "),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (item.isAlert) Tag("Alert", status.alert) else Tag("Detection", status.detection)
            item.data.zones.forEach { Tag(labelTitle(it), MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
private fun EventRow(e: FrigateEvent, onClick: () -> Unit) {
    val conn = LocalConnection.current
    Row(
        Modifier.fillMaxWidth().clickable(enabled = e.hasSnapshot, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp).testTag("event_${e.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FrigateImage(conn.client.urls.eventThumb(e.id).toString(), null, Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(labelIcon(e.label), null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    listOfNotNull(labelTitle(e.label), e.subLabelText).joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                listOfNotNull(
                    formatTime(e.startTime),
                    e.score?.let { formatDecimal("%.0f%%", it * 100) },
                    e.zones.takeIf { it.isNotEmpty() }?.joinToString(", ") { labelTitle(it) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (e.hasSnapshot) Icon(AppIcons.Image, "Snapshot", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SnapshotDialog(url: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(8.dp)) {
            Column(horizontalAlignment = Alignment.End) {
                FrigateImage(url, "Snapshot", Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}
