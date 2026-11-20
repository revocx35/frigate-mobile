package io.github.revocx35.frigatemobile.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String) { System("System"), Light("Light"), Dark("Dark") }

/** Live view quality: Frigate re-encodes the detect stream as MJPEG at this height and frame rate. */
enum class LiveQuality(val label: String, val height: Int, val fps: Int) {
    Low("Low · 360p, 3 fps", 360, 3),
    Medium("Medium · 480p, 5 fps", 480, 5),
    High("High · 720p, 10 fps", 720, 10),
}

data class AppSettings(
    val theme: ThemeMode = ThemeMode.System,
    val liveQuality: LiveQuality = LiveQuality.Medium,
    val showBoxes: Boolean = false,
    val alertNotifications: Boolean = false,
    val notifyDetections: Boolean = false,
    val icon: IconChoice = IconChoice.Default,
)

class SettingsStore(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun load() = AppSettings(
        theme = enumOr(sp.getString("theme", null), ThemeMode.System),
        liveQuality = enumOr(sp.getString("live_quality", null), LiveQuality.Medium),
        showBoxes = sp.getBoolean("show_boxes", false),
        alertNotifications = sp.getBoolean("alert_notifications", false),
        notifyDetections = sp.getBoolean("notify_detections", false),
        icon = enumOr(sp.getString("icon", null), IconChoice.Default),
    )

    fun update(change: (AppSettings) -> AppSettings) {
        val s = change(_settings.value)
        sp.edit {
            putString("theme", s.theme.name)
            putString("live_quality", s.liveQuality.name)
            putBoolean("show_boxes", s.showBoxes)
            putBoolean("alert_notifications", s.alertNotifications)
            putBoolean("notify_detections", s.notifyDetections)
            putString("icon", s.icon.name)
        }
        _settings.value = s
    }

    /** Start time of the newest review item already notified (epoch seconds). */
    var lastNotified: Double
        get() = sp.getString("last_notified", null)?.toDoubleOrNull() ?: 0.0
        set(v) = sp.edit { putString("last_notified", v.toString()) }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
