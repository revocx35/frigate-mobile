package io.github.revocx35.frigatemobile.ui.cameras

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.revocx35.frigatemobile.core.FrigateSocket
import io.github.revocx35.frigatemobile.core.PtzCommand
import io.github.revocx35.frigatemobile.core.PtzInfo
import io.github.revocx35.frigatemobile.core.toUserMessage
import io.github.revocx35.frigatemobile.ui.components.LocalConnection
import io.github.revocx35.frigatemobile.ui.components.labelTitle
import io.github.revocx35.frigatemobile.ui.theme.AppIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Frigate's websocket for PTZ commands, open while the camera screen is shown. */
@Composable
fun rememberFrigateSocket(): FrigateSocket {
    val conn = LocalConnection.current
    val socket = remember(conn) { FrigateSocket(conn.http, conn.client.urls.socket()) }
    DisposableEffect(socket) {
        socket.warmUp()
        onDispose { socket.close() }
    }
    return socket
}

/**
 * ONVIF PTZ through Frigate: hold an arrow (or zoom) to move, release to stop, like Frigate's web UI. Tapping with
 * TalkBack moves briefly. Presets move the camera to a saved position.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PtzControls(
    camera: String,
    info: PtzInfo,
    socket: FrigateSocket,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
    overlay: Boolean = false,
) {
    val send: (PtzCommand) -> Unit = { cmd ->
        if (!socket.ptz(camera, cmd)) onError(socket.lastError?.toUserMessage() ?: "Couldn't reach Frigate to move the camera.")
    }
    val buttonSize = if (overlay) 44.dp else 52.dp
    Column(modifier.testTag("ptz"), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = if (overlay) Modifier else Modifier.fillMaxWidth()) {
            if (info.canMove) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    HoldButton(AppIcons.ArrowUp, "Tilt up", "ptz_up", buttonSize, overlay, { send(PtzCommand.Up) }) { send(PtzCommand.Stop) }
                    Row {
                        HoldButton(AppIcons.ArrowLeft, "Pan left", "ptz_left", buttonSize, overlay, { send(PtzCommand.Left) }) { send(PtzCommand.Stop) }
                        Spacer(Modifier.size(buttonSize))
                        HoldButton(AppIcons.ArrowRight, "Pan right", "ptz_right", buttonSize, overlay, { send(PtzCommand.Right) }) { send(PtzCommand.Stop) }
                    }
                    HoldButton(AppIcons.ArrowDown, "Tilt down", "ptz_down", buttonSize, overlay, { send(PtzCommand.Down) }) { send(PtzCommand.Stop) }
                }
            }
            if (info.canMove && info.canZoom) Spacer(Modifier.width(if (overlay) 12.dp else 32.dp))
            if (info.canZoom) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HoldButton(AppIcons.ZoomIn, "Zoom in", "ptz_zoom_in", buttonSize, overlay, { send(PtzCommand.ZoomIn) }) { send(PtzCommand.Stop) }
                    HoldButton(AppIcons.ZoomOut, "Zoom out", "ptz_zoom_out", buttonSize, overlay, { send(PtzCommand.ZoomOut) }) { send(PtzCommand.Stop) }
                }
            }
        }
        if (info.presets.isNotEmpty() && !overlay) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                info.presets.forEach { preset ->
                    AssistChip(
                        onClick = { if (!socket.ptzPreset(camera, preset)) onError(socket.lastError?.toUserMessage() ?: "Couldn't reach Frigate to move the camera.") },
                        label = { Text(labelTitle(preset)) },
                        leadingIcon = { Icon(AppIcons.Bookmark, null, Modifier.size(18.dp)) },
                        modifier = Modifier.testTag("ptz_preset_$preset"),
                    )
                }
            }
        }
    }
}

/** Sends [onStart] on touch down and [onStop] on release (or when the finger leaves / the gesture is cancelled). */
@Composable
private fun HoldButton(
    icon: ImageVector,
    description: String,
    tag: String,
    size: Dp,
    overlay: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val start by rememberUpdatedState(onStart)
    val stop by rememberUpdatedState(onStop)
    Surface(
        shape = CircleShape,
        color = if (overlay) Color.Black.copy(alpha = 0.5f) else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (overlay) Color.White else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .padding(2.dp)
            .size(size)
            .testTag(tag)
            .semantics {
                role = Role.Button
                contentDescription = description
                onClick(label = description) {
                    start()
                    scope.launch { delay(400); stop() }
                    true
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    start()
                    try {
                        tryAwaitRelease()
                    } finally {
                        stop()
                    }
                })
            },
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(28.dp)) }
    }
}
