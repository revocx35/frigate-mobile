package io.github.revocx35.frigatemobile.ui.recordings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.revocx35.frigatemobile.core.RecordingHour
import io.github.revocx35.frigatemobile.ui.components.EmptyBox
import io.github.revocx35.frigatemobile.ui.components.LoadContent
import io.github.revocx35.frigatemobile.ui.components.LocalConnection
import io.github.revocx35.frigatemobile.ui.components.SectionTitle
import io.github.revocx35.frigatemobile.ui.components.formatDay
import io.github.revocx35.frigatemobile.ui.components.formatDuration
import io.github.revocx35.frigatemobile.ui.components.rememberLoader
import io.github.revocx35.frigatemobile.ui.theme.AppIcons
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** A camera's recordings by day and hour (in the phone's time zone). Tapping an hour plays it. */
@Composable
fun RecordingsScreen(camera: String, onBack: () -> Unit, onPlay: (title: String, start: Double, end: Double) -> Unit) {
    val conn = LocalConnection.current
    val zone = ZoneId.systemDefault()
    val loader = rememberLoader(conn, camera) {
        val title = runCatching { conn.client.config().camera(camera)?.title }.getOrNull() ?: camera
        title to conn.client.recordingDays(camera, zone.id)
    }
    val hourFmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recordings · ${loader.value?.first ?: camera}") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(AppIcons.Back, "Back") } },
                actions = { IconButton(onClick = loader::reload) { Icon(AppIcons.Refresh, "Refresh") } },
            )
        },
    ) { padding ->
        LoadContent(loader, Modifier.padding(padding)) { (title, days) ->
            if (days.isEmpty()) {
                EmptyBox(AppIcons.Movie, "No recordings", Modifier.padding(padding), text = "Frigate has no recordings for this camera, or recording is off.")
                return@LoadContent
            }
            LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("recording_days")) {
                days.forEach { day ->
                    val date = runCatching { LocalDate.parse(day.day) }.getOrNull() ?: return@forEach
                    item(key = "d-${day.day}") { SectionTitle(formatDay(date)) }
                    items(day.hours.sortedByDescending { it.hour.toIntOrNull() ?: 0 }, key = { "${day.day}-${it.hour}" }) { h ->
                        val hour = h.hour.toIntOrNull() ?: return@items
                        val start = date.atTime(LocalTime.of(hour, 0)).atZone(zone)
                        val label = "${start.format(hourFmt)} – ${start.plusHours(1).format(hourFmt)}"
                        ListItem(
                            headlineContent = { Text(label) },
                            supportingContent = { Text(hourSummary(h)) },
                            leadingContent = { Icon(AppIcons.Play, null) },
                            modifier = Modifier.clickable {
                                val s = start.toEpochSecond().toDouble()
                                onPlay("$title · ${formatDay(date)} $label", s, s + 3600)
                            }.testTag("hour_${day.day}_${h.hour}"),
                        )
                    }
                }
            }
        }
    }
}

private fun hourSummary(h: RecordingHour): String = buildList {
    if (h.duration > 0) add("${formatDuration(h.duration)} recorded")
    if (h.events > 0) add("${h.events} event${if (h.events == 1) "" else "s"}")
    if (h.motion > 0 && h.events == 0) add("motion")
}.joinToString(" · ").ifBlank { "Recorded" }
