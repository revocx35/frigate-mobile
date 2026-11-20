package io.github.revocx35.frigatemobile.ui.system

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.revocx35.frigatemobile.BuildConfig
import io.github.revocx35.frigatemobile.core.FrigateStats
import io.github.revocx35.frigatemobile.data.AlertWorker
import io.github.revocx35.frigatemobile.data.AppGraph
import io.github.revocx35.frigatemobile.data.LiveQuality
import io.github.revocx35.frigatemobile.data.ServerTrust
import io.github.revocx35.frigatemobile.data.ThemeMode
import io.github.revocx35.frigatemobile.ui.components.Load
import io.github.revocx35.frigatemobile.ui.components.LocalConnection
import io.github.revocx35.frigatemobile.ui.components.SectionTitle
import io.github.revocx35.frigatemobile.ui.components.formatDecimal
import io.github.revocx35.frigatemobile.ui.components.formatMb
import io.github.revocx35.frigatemobile.ui.components.formatUptime
import io.github.revocx35.frigatemobile.ui.components.rememberLoader
import io.github.revocx35.frigatemobile.ui.theme.AppIcons
import io.github.revocx35.frigatemobile.ui.theme.LocalStatusColors
import java.util.Locale

/** Server health (Frigate's stats), app settings and the account. */
@Composable
fun SystemScreen(graph: AppGraph) {
    val conn = LocalConnection.current
    val settings by graph.settings.settings.collectAsState()
    val loader = rememberLoader(conn) { conn.client.stats() }
    val context = LocalContext.current
    var confirmSignOut by remember { mutableStateOf(false) }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) enableNotifications(graph)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("System") },
                actions = { IconButton(onClick = loader::reload) { Icon(AppIcons.Refresh, "Refresh") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).testTag("system")) {
            SectionTitle("Server")
            when (val s = loader.state) {
                Load.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                is Load.Failed -> ListItem(headlineContent = { Text(s.message) }, leadingContent = { Icon(AppIcons.Error, null) })
                is Load.Ok -> StatsSection(s.value)
            }

            SectionTitle("Notifications")
            ListItem(
                headlineContent = { Text("Alert notifications") },
                supportingContent = { Text("Checks for new unreviewed alerts every 15 minutes") },
                leadingContent = { Icon(AppIcons.Notifications, null) },
                trailingContent = {
                    Switch(
                        checked = settings.alertNotifications,
                        onCheckedChange = { on ->
                            if (!on) {
                                graph.settings.update { it.copy(alertNotifications = false) }
                                AlertWorker.cancel(context)
                            } else if (Build.VERSION.SDK_INT >= 33 && !AlertWorker.canNotify(context)) {
                                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                enableNotifications(graph)
                            }
                        },
                        modifier = Modifier.testTag("notifications_switch"),
                    )
                },
            )
            if (settings.alertNotifications) {
                ListItem(
                    headlineContent = { Text("Also notify about detections") },
                    trailingContent = {
                        Switch(checked = settings.notifyDetections, onCheckedChange = { on -> graph.settings.update { it.copy(notifyDetections = on) } })
                    },
                )
            }

            SectionTitle("Live view")
            ChoiceItem("Quality", settings.liveQuality.label, LiveQuality.entries.map { it.label }) { i ->
                graph.settings.update { it.copy(liveQuality = LiveQuality.entries[i]) }
            }
            ListItem(
                headlineContent = { Text("Show detection boxes") }, supportingContent = { Text("MJPEG live view only") },
                trailingContent = { Switch(checked = settings.showBoxes, onCheckedChange = { on -> graph.settings.update { it.copy(showBoxes = on) } }) },
            )
            ChoiceItem("Theme", settings.theme.label, ThemeMode.entries.map { it.label }) { i ->
                graph.settings.update { it.copy(theme = ThemeMode.entries[i]) }
            }

            SectionTitle("App icon")
            AppIconSection(graph)

            SectionTitle("Account")
            val account = conn.account
            ListItem(
                headlineContent = { Text(if (account.authDisabled) "No sign-in (authentication off)" else account.user) },
                supportingContent = { Text("${account.server} · ${account.role}") },
                leadingContent = { Icon(AppIcons.Person, null) },
            )
            graph.trust.pinned().firstOrNull()?.let { cert ->
                ListItem(
                    headlineContent = { Text("Trusted certificate: ${ServerTrust.commonName(cert)}") },
                    supportingContent = { Text("SHA-256 " + ServerTrust.sha256(cert), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) },
                    leadingContent = { Icon(AppIcons.Shield, null) },
                )
            }
            ListItem(
                headlineContent = { Text("Sign out") },
                leadingContent = { Icon(AppIcons.Logout, null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable { confirmSignOut = true }.testTag("sign_out"),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Frigate Mobile ${BuildConfig.VERSION_NAME}") },
                supportingContent = { Text("Unofficial client for Frigate NVR. Open source, MIT license.") },
                leadingContent = { AppLogo(graph, 24.dp) },
            )
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("The app forgets the session and the saved password. Notifications stop.") },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; graph.signOut() }, modifier = Modifier.testTag("confirm_sign_out")) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

private fun enableNotifications(graph: AppGraph) {
    graph.settings.lastNotified = System.currentTimeMillis() / 1000.0
    graph.settings.update { it.copy(alertNotifications = true) }
    AlertWorker.schedule(graph.context)
}

@Composable
private fun StatsSection(s: FrigateStats) {
    val status = LocalStatusColors.current
    val updateAvailable = s.latestVersion != null && s.version != null && !s.version!!.startsWith(s.latestVersion!!)
    ListItem(
        headlineContent = { Text("Frigate ${s.version ?: "?"}") },
        supportingContent = {
            Text(
                listOfNotNull(
                    s.uptimeSeconds?.let { "up ${formatUptime(it)}" },
                    s.latestVersion?.takeIf { updateAvailable }?.let { "update available: $it" },
                ).joinToString(" · "),
            )
        },
        leadingContent = { Icon(AppIcons.Server, null) },
    )
    if (s.cpuPercent != null || s.memPercent != null) {
        ListItem(
            headlineContent = { Text("Load") },
            supportingContent = {
                Text(listOfNotNull(s.cpuPercent?.let { "CPU ${pct(it)}" }, s.memPercent?.let { "memory ${pct(it)}" }).joinToString(" · "))
            },
            leadingContent = { Icon(AppIcons.Speed, null) },
        )
    }
    s.detectors.forEach { d ->
        ListItem(
            headlineContent = { Text("Detector ${d.name}") },
            supportingContent = { Text(formatDecimal("%.1f ms inference", d.inferenceMs)) },
            leadingContent = { Icon(AppIcons.Memory, null) },
        )
    }
    s.gpus.forEach { g ->
        ListItem(
            headlineContent = { Text(g.name) },
            supportingContent = { Text(listOfNotNull(g.gpuPercent?.let { "GPU ${pct(it)}" }, g.memPercent?.let { "memory ${pct(it)}" }).joinToString(" · ")) },
            leadingContent = { Icon(AppIcons.Memory, null) },
        )
    }
    s.storage.forEach { st ->
        ListItem(
            headlineContent = { Text(st.path) },
            supportingContent = {
                Column {
                    Text("${formatMb(st.used)} of ${formatMb(st.total)}")
                    LinearProgressIndicator(progress = { st.fraction }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            },
            leadingContent = { Icon(AppIcons.Storage, null) },
        )
    }
    s.cameras.forEach { c ->
        ListItem(
            headlineContent = { Text(c.name) },
            supportingContent = {
                Text(
                    if (c.online) formatDecimal("%.1f fps · detection %.1f fps", c.cameraFps, c.detectionFps)
                    else "No frames received",
                )
            },
            leadingContent = { Icon(AppIcons.Videocam, null, tint = if (c.online) status.online else status.offline) },
        )
    }
}

private fun pct(v: Double) = String.format(Locale.getDefault(), "%.0f%%", v)

@Composable
private fun ChoiceItem(title: String, value: String, options: List<String>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(headlineContent = { Text(title) }, supportingContent = { Text(value) }, modifier = Modifier.clickable { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onPick(i) }) }
        }
    }
}
