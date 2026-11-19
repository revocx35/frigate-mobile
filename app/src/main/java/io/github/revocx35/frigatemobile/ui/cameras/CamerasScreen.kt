package io.github.revocx35.frigatemobile.ui.cameras

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import io.github.revocx35.frigatemobile.core.CameraInfo
import io.github.revocx35.frigatemobile.core.FrigateConfig
import io.github.revocx35.frigatemobile.core.FrigateStats
import io.github.revocx35.frigatemobile.ui.components.EmptyBox
import io.github.revocx35.frigatemobile.ui.components.FrigateImage
import io.github.revocx35.frigatemobile.ui.components.LoadContent
import io.github.revocx35.frigatemobile.ui.components.LocalConnection
import io.github.revocx35.frigatemobile.ui.components.rememberLoader
import io.github.revocx35.frigatemobile.ui.theme.AppIcons
import io.github.revocx35.frigatemobile.ui.theme.LocalStatusColors
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

data class CamerasData(val config: FrigateConfig, val stats: FrigateStats?)

/** All cameras with a frame that refreshes every few seconds while the screen is visible. */
@Composable
fun CamerasScreen(onOpen: (String) -> Unit) {
    val conn = LocalConnection.current
    val loader = rememberLoader(conn) {
        coroutineScope {
            val stats = async { runCatching { conn.client.stats() }.getOrNull() }
            CamerasData(conn.client.config(), stats.await())
        }
    }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    var tick by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(lifecycle) {
        while (lifecycle.isAtLeast(Lifecycle.State.RESUMED)) {
            delay(REFRESH_MS)
            tick = System.currentTimeMillis() / 1000
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cameras") },
                actions = {
                    IconButton(onClick = { loader.reload(); tick = System.currentTimeMillis() / 1000 }) { Icon(AppIcons.Refresh, "Refresh") }
                },
            )
        },
    ) { padding ->
        LoadContent(loader, Modifier.padding(padding)) { data ->
            val cameras = data.config.cameras.filter { it.dashboard }.ifEmpty { data.config.cameras }
            if (cameras.isEmpty()) {
                EmptyBox(AppIcons.Videocam, "No cameras", Modifier.padding(padding), text = "Your Frigate role has no cameras, or none are configured.")
                return@LoadContent
            }
            PullToRefreshBox(
                isRefreshing = loader.refreshing,
                onRefresh = { loader.reload(); tick = System.currentTimeMillis() / 1000 },
                modifier = Modifier.padding(padding).fillMaxSize(),
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 300.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize().testTag("cameras"),
                ) {
                    items(cameras, key = { it.name }) { cam ->
                        CameraCard(cam, online = data.stats?.cameras?.firstOrNull { it.name == cam.name }?.online, tick = tick) { onOpen(cam.name) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraCard(cam: CameraInfo, online: Boolean?, tick: Long, onClick: () -> Unit) {
    val conn = LocalConnection.current
    val status = LocalStatusColors.current
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("camera_${cam.name}")) {
        Box(Modifier.fillMaxWidth().aspectRatio(cam.aspectRatio)) {
            if (cam.enabled) {
                FrigateImage(
                    url = conn.client.urls.latest(cam.name, 360, cacheBust = tick).toString(),
                    contentDescription = cam.title,
                    placeholderKey = "latest-${cam.name}-prev",
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                EmptyBox(AppIcons.Videocam, "Camera disabled")
            }
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                contentColor = Color.White,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            ) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (online != null) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (online) status.online else status.offline))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(cam.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (cam.record) Badge(AppIcons.Record, "Recording")
                if (cam.detect) Badge(AppIcons.Detect, "Detecting")
            }
        }
    }
}

@Composable
private fun Badge(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String) {
    Surface(color = Color.Black.copy(alpha = 0.55f), contentColor = Color.White, shape = CircleShape) {
        Icon(icon, description, modifier = Modifier.padding(4.dp).size(14.dp))
    }
}

private const val REFRESH_MS = 10_000L
