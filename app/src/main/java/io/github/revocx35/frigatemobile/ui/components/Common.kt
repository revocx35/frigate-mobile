package io.github.revocx35.frigatemobile.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import io.github.revocx35.frigatemobile.core.toUserMessage
import io.github.revocx35.frigatemobile.data.Connection
import io.github.revocx35.frigatemobile.ui.theme.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** The signed-in connection, provided by the main shell. */
val LocalConnection = compositionLocalOf<Connection> { error("No connection") }

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

/** Loads data for a screen; keeps showing the last value while refreshing. */
@Stable
class Loader<T>(private val scope: CoroutineScope, private val block: suspend () -> T) {
    var state: Load<T> by mutableStateOf(Load.Loading)
        private set
    var refreshing by mutableStateOf(false)
        private set
    private var job: Job? = null

    val value: T? get() = (state as? Load.Ok<T>)?.value

    fun reload() {
        job?.cancel()
        refreshing = true
        job = scope.launch(Dispatchers.Main.immediate) {
            try {
                state = Load.Ok(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep stale data visible on a failed background refresh; show the error otherwise.
                if (state !is Load.Ok) state = Load.Failed(e.toUserMessage())
                lastError = e.toUserMessage()
            } finally {
                refreshing = false
            }
        }
    }

    /** The message of the last failed refresh (also when older data is still shown). */
    var lastError: String? by mutableStateOf(null)
        private set

    fun set(value: T) { state = Load.Ok(value) }
}

@Composable
fun <T> rememberLoader(vararg keys: Any?, block: suspend () -> T): Loader<T> {
    val scope = rememberCoroutineScope()
    val loader = remember(*keys) { Loader(scope, block) }
    LaunchedEffect(loader) { loader.reload() }
    return loader
}

/** Renders a [Loader]'s state: spinner, error with retry, or [content]. */
@Composable
fun <T> LoadContent(loader: Loader<T>, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    when (val s = loader.state) {
        Load.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is Load.Failed -> ErrorBox(s.message, modifier, onRetry = loader::reload)
        is Load.Ok -> content(s.value)
    }
}

@Composable
fun ErrorBox(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxSize().padding(32.dp).testTag("error"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(AppIcons.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text(message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        if (onRetry != null) {
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
fun EmptyBox(icon: ImageVector, title: String, modifier: Modifier = Modifier, text: String? = null) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (text != null) {
            Spacer(Modifier.height(4.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

/** An image from the Frigate server, loaded through the signed-in session. */
@Composable
fun FrigateImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    /** Keep the previous image while the next one loads (for refreshing camera frames). */
    placeholderKey: String? = null,
) {
    val conn = LocalConnection.current
    val ctx = LocalPlatformContext.current
    val request = remember(url) {
        ImageRequest.Builder(ctx).data(url).apply { placeholderKey?.let { placeholderMemoryCacheKey(it); memoryCacheKey(url) } }.build()
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (url != null) {
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                imageLoader = conn.imageLoader,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** A small colored label (e.g. "Alert", "person"). */
@Composable
fun Tag(text: String, color: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Surface(color = color.copy(alpha = 0.16f), contentColor = color, shape = RoundedCornerShape(6.dp), modifier = modifier) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

// ---- formatting ----

fun labelTitle(label: String): String = label.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.getDefault()) }

fun labelIcon(label: String): ImageVector = when (label.lowercase()) {
    "person", "face" -> AppIcons.Motion
    "car", "truck", "motorcycle", "bus", "bicycle", "license_plate", "boat" -> AppIcons.Car
    "dog", "cat", "bird", "horse", "animal", "bear", "deer" -> AppIcons.Pets
    "speech", "bark", "yell", "scream", "fire_alarm", "glass", "music" -> AppIcons.Mic
    else -> AppIcons.Detect
}

private val timeFmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val dateFmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

fun epochToLocal(epochSeconds: Double) = Instant.ofEpochMilli((epochSeconds * 1000).toLong()).atZone(ZoneId.systemDefault())

fun formatTime(epochSeconds: Double): String = epochToLocal(epochSeconds).format(timeFmt)

/** "Today", "Yesterday" or the date. */
fun formatDay(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> date.format(dateFmt)
}

fun formatDuration(seconds: Double): String {
    val s = seconds.toLong().coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m ${s % 60}s"
        else -> "${s / 3600}h ${(s % 3600) / 60}m"
    }
}

fun formatUptime(seconds: Long): String {
    val d = seconds / 86400
    val h = (seconds % 86400) / 3600
    val m = (seconds % 3600) / 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m"
    }
}

fun formatMb(mb: Double): String = when {
    mb >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f TB", mb / 1024 / 1024)
    mb >= 1024 -> String.format(Locale.getDefault(), "%.1f GB", mb / 1024)
    else -> String.format(Locale.getDefault(), "%.0f MB", mb)
}

/** Number formatting outside composables (the current locale is read at call time). */
fun formatDecimal(pattern: String, vararg args: Any): String = String.format(Locale.getDefault(), pattern, *args)
