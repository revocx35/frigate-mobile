package io.github.revocx35.frigatemobile.ui.system

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.revocx35.frigatemobile.R
import io.github.revocx35.frigatemobile.data.AppGraph
import io.github.revocx35.frigatemobile.data.IconChoice
import io.github.revocx35.frigatemobile.ui.theme.AppIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The app's current logo (built-in or the user's image), as shown inside the app. */
@Composable
fun AppLogo(graph: AppGraph, size: Dp, modifier: Modifier = Modifier) {
    val settings by graph.settings.settings.collectAsState()
    val custom by graph.appIcon.custom.collectAsState()
    val shape = RoundedCornerShape(size * 0.22f)
    when {
        settings.icon == IconChoice.Custom && custom != null ->
            Image(custom!!.asImageBitmap(), "App logo", modifier.size(size).clip(shape))
        settings.icon == IconChoice.Bird ->
            Box(modifier.size(size).clip(shape).background(Color(0xFF2563EB)), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_launcher_bird_foreground), "App logo", tint = Color.White, modifier = Modifier.size(size * 1.8f))
            }
        else ->
            Box(modifier.size(size).clip(shape).background(Color(0xFF1D4ED8)), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_launcher_foreground), "App logo", tint = Color.White, modifier = Modifier.size(size * 1.8f))
            }
    }
}

/**
 * System > App icon. Built-in icons change the launcher icon; a custom image is used inside the app and can be
 * added to the home screen as a shortcut (Android doesn't let apps make an arbitrary image their launcher icon).
 */
@Composable
fun AppIconSection(graph: AppGraph) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsState()
    val custom by graph.appIcon.custom.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }

    fun choose(choice: IconChoice) {
        graph.settings.update { it.copy(icon = choice) }
        graph.appIcon.apply(choice)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.Main.immediate) {
            try {
                graph.appIcon.setCustom(uri)
                error = null
                choose(IconChoice.Custom)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Couldn't use this image."
            }
        }
    }

    Column(Modifier.fillMaxWidth().testTag("app_icon")) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppLogo(graph, 56.dp)
            Text(
                "Changing the icon can take a few seconds to show on the home screen; some launchers move it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        IconChoice.entries.forEach { choice ->
            ListItem(
                headlineContent = { Text(choice.label) },
                supportingContent = if (choice == IconChoice.Custom) {
                    { Text(if (custom == null) "Pick an image from your phone" else "Shown in the app and as a home-screen shortcut") }
                } else null,
                leadingContent = {
                    RadioButton(selected = settings.icon == choice, onClick = null)
                },
                modifier = Modifier
                    .selectable(selected = settings.icon == choice, role = Role.RadioButton) {
                        if (choice == IconChoice.Custom && custom == null) {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        } else {
                            choose(choice)
                        }
                    }
                    .testTag("icon_${choice.name}"),
            )
        }
        if (settings.icon == IconChoice.Custom) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Icon(AppIcons.Image, null, Modifier.size(18.dp))
                    Text("Change image", Modifier.padding(start = 6.dp))
                }
                if (custom != null && graph.appIcon.canPinShortcut) {
                    FilledTonalButton(
                        onClick = {
                            if (!graph.appIcon.pinCustomShortcut()) Toast.makeText(context, "Your launcher doesn't support adding shortcuts.", Toast.LENGTH_LONG).show()
                        },
                        modifier = Modifier.testTag("pin_shortcut"),
                    ) { Text("Add to home screen") }
                }
            }
            if (custom != null) {
                ListItem(
                    headlineContent = { Text("Remove custom image") },
                    leadingContent = { Icon(AppIcons.Close, null) },
                    modifier = Modifier.selectable(selected = false) {
                        graph.appIcon.clearCustom()
                        choose(IconChoice.Default)
                    }.testTag("remove_custom_icon"),
                )
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp).testTag("icon_error")) }
    }
}
