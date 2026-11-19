package io.github.revocx35.frigatemobile.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.revocx35.frigatemobile.data.ThemeMode

// Brand scheme around a deep blue (#1D4ED8); used when dynamic color is unavailable (Android < 12).
private val Light = lightColorScheme(
    primary = Color(0xFF2C55C2), onPrimary = Color.White, primaryContainer = Color(0xFFDBE1FF), onPrimaryContainer = Color(0xFF00174B),
    secondary = Color(0xFF585E71), onSecondary = Color.White, secondaryContainer = Color(0xFFDCE2F9), onSecondaryContainer = Color(0xFF151B2C),
    tertiary = Color(0xFF735471), onTertiary = Color.White, tertiaryContainer = Color(0xFFFFD6F9), onTertiaryContainer = Color(0xFF2B122B),
    background = Color(0xFFFAF8FF), onBackground = Color(0xFF1A1B21), surface = Color(0xFFFAF8FF), onSurface = Color(0xFF1A1B21),
    surfaceVariant = Color(0xFFE2E2EC), onSurfaceVariant = Color(0xFF45464F), outline = Color(0xFF757680), outlineVariant = Color(0xFFC5C6D0),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFB4C5FF), onPrimary = Color(0xFF002A78), primaryContainer = Color(0xFF0E3DA8), onPrimaryContainer = Color(0xFFDBE1FF),
    secondary = Color(0xFFC0C6DD), onSecondary = Color(0xFF2A3042), secondaryContainer = Color(0xFF404659), onSecondaryContainer = Color(0xFFDCE2F9),
    tertiary = Color(0xFFE1BBDC), onTertiary = Color(0xFF422741), tertiaryContainer = Color(0xFF5A3D58), onTertiaryContainer = Color(0xFFFFD6F9),
    background = Color(0xFF121318), onBackground = Color(0xFFE3E1E9), surface = Color(0xFF121318), onSurface = Color(0xFFE3E1E9),
    surfaceVariant = Color(0xFF45464F), onSurfaceVariant = Color(0xFFC5C6D0), outline = Color(0xFF8F909A), outlineVariant = Color(0xFF45464F),
)

/** Colors for states Material has no role for. */
@Immutable
data class StatusColors(val online: Color, val offline: Color, val alert: Color, val detection: Color)

private val LightStatus = StatusColors(online = Color(0xFF1E7D32), offline = Color(0xFFBA1A1A), alert = Color(0xFFC62828), detection = Color(0xFFB26A00))
private val DarkStatus = StatusColors(online = Color(0xFF7FD98A), offline = Color(0xFFFFB4AB), alert = Color(0xFFFF8A80), detection = Color(0xFFFFC56B))

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

@Composable
fun FrigateTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val scheme: ColorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> Dark
        else -> Light
    }
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
