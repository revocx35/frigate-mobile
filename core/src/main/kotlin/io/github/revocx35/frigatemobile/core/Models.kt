package io.github.revocx35.frigatemobile.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Lenient JSON: Frigate adds fields every release; every model field has a default. */
val FrigateJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = true
}

/** `GET /api/profile`. `allowed_cameras` exists since 0.16 (camera-level roles); null = all cameras. */
@Serializable
data class Profile(
    val username: String = "",
    val role: String = "viewer",
    @SerialName("allowed_cameras") val allowedCameras: List<String>? = null,
) {
    val isAdmin: Boolean get() = role == "admin"
}

enum class Severity(val api: String) { Alert("alert"), Detection("detection") }

/** A review item (`GET /api/review`): one span of activity on one camera, made of one or more tracked objects. */
@Serializable
data class ReviewItem(
    val id: String = "",
    val camera: String = "",
    @SerialName("start_time") val startTime: Double = 0.0,
    /** Null while the activity is still going on. */
    @SerialName("end_time") val endTime: Double? = null,
    val severity: String = "detection",
    @SerialName("thumb_path") val thumbPath: String = "",
    @SerialName("has_been_reviewed") val hasBeenReviewed: Boolean = false,
    val data: ReviewData = ReviewData(),
) {
    val isAlert: Boolean get() = severity == Severity.Alert.api
    val inProgress: Boolean get() = endTime == null

    /** Labels to show: objects, then audio, with sub labels (recognized faces, plates) when Frigate has them. */
    val labels: List<String> get() = (data.subLabels + data.objects + data.audio).distinct()
}

@Serializable
data class ReviewData(
    val detections: List<String> = emptyList(),
    val objects: List<String> = emptyList(),
    @SerialName("sub_labels") val subLabels: List<String> = emptyList(),
    val zones: List<String> = emptyList(),
    val audio: List<String> = emptyList(),
)

/** `GET /api/review/summary`, `last24Hours` part. */
@Serializable
data class ReviewCounts(
    @SerialName("reviewed_alert") val reviewedAlert: Int = 0,
    @SerialName("reviewed_detection") val reviewedDetection: Int = 0,
    @SerialName("total_alert") val totalAlert: Int = 0,
    @SerialName("total_detection") val totalDetection: Int = 0,
) {
    val unreviewedAlerts: Int get() = (totalAlert - reviewedAlert).coerceAtLeast(0)
    val unreviewedDetections: Int get() = (totalDetection - reviewedDetection).coerceAtLeast(0)
}

/** A tracked object (`GET /api/events`). */
@Serializable
data class FrigateEvent(
    val id: String = "",
    val camera: String = "",
    val label: String = "",
    @SerialName("sub_label") val subLabel: JsonElement? = null,
    @SerialName("top_score") val topScore: Double? = null,
    @SerialName("start_time") val startTime: Double = 0.0,
    @SerialName("end_time") val endTime: Double? = null,
    @SerialName("has_clip") val hasClip: Boolean = false,
    @SerialName("has_snapshot") val hasSnapshot: Boolean = false,
    val zones: List<String> = emptyList(),
    val data: EventData = EventData(),
) {
    /** The sub label is a string in older releases and `[name, score]` in newer ones. */
    val subLabelText: String?
        get() = when (val s = subLabel) {
            is JsonPrimitive -> s.content.takeIf { s.isString && it.isNotBlank() }
            is JsonArray -> (s.firstOrNull() as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            else -> null
        }

    /** Best known confidence 0..1 (newer releases keep it in `data`). */
    val score: Double? get() = (data.topScore ?: topScore ?: data.score)?.takeIf { it > 0 }
}

@Serializable
data class EventData(
    val score: Double? = null,
    @SerialName("top_score") val topScore: Double? = null,
    val type: String? = null,
)

/** One day of `GET /api/{camera}/recordings/summary`. */
@Serializable
data class RecordingDay(
    val day: String = "",
    val events: Int = 0,
    val hours: List<RecordingHour> = emptyList(),
)

@Serializable
data class RecordingHour(
    val hour: String = "",
    val events: Int = 0,
    val motion: Double = 0.0,
    val objects: Double = 0.0,
    val duration: Double = 0.0,
)

/** The parts of a camera's configuration the app shows. Runtime toggles are reflected by `GET /api/config`. */
data class CameraInfo(
    val name: String,
    val friendlyName: String? = null,
    val enabled: Boolean = true,
    val detect: Boolean = false,
    val record: Boolean = false,
    val snapshots: Boolean = false,
    val audio: Boolean = false,
    val motion: Boolean = true,
    /** The feature is configured at all (Frigate refuses to switch on audio/recording that isn't in the config). */
    val audioConfigured: Boolean = false,
    val recordConfigured: Boolean = true,
    val width: Int = 0,
    val height: Int = 0,
    val order: Int = 0,
    val dashboard: Boolean = true,
    /** `live.streams`: label (e.g. "fhd") -> go2rtc stream name, in config order. Frigate fills in {name: name}. */
    val liveStreams: Map<String, String> = emptyMap(),
    /** `onvif.host` is set: the camera may support PTZ (ask `GET /api/{camera}/ptz/info`). */
    val onvifConfigured: Boolean = false,
) {
    val title: String get() = friendlyName?.takeIf { it.isNotBlank() } ?: name.replace('_', ' ').replaceFirstChar { it.uppercase() }
    val aspectRatio: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 16f / 9f
}

data class FrigateConfig(val cameras: List<CameraInfo>) {
    fun camera(name: String): CameraInfo? = cameras.firstOrNull { it.name == name }

    companion object {
        fun parse(root: JsonObject): FrigateConfig {
            val cams = (root["cameras"] as? JsonObject).orEmpty().mapNotNull { (name, v) ->
                val c = v as? JsonObject ?: return@mapNotNull null
                CameraInfo(
                    name = name,
                    friendlyName = c.str("friendly_name"),
                    enabled = c.bool("enabled") ?: true,
                    detect = c.obj("detect").bool("enabled") ?: false,
                    record = c.obj("record").bool("enabled") ?: false,
                    snapshots = c.obj("snapshots").bool("enabled") ?: false,
                    audio = c.obj("audio").bool("enabled") ?: false,
                    motion = c.obj("motion").bool("enabled") ?: true,
                    audioConfigured = c.obj("audio").bool("enabled_in_config") ?: (c.obj("audio").bool("enabled") ?: false),
                    recordConfigured = c.obj("record").bool("enabled_in_config") ?: true,
                    width = c.obj("detect").int("width") ?: 0,
                    height = c.obj("detect").int("height") ?: 0,
                    order = c.obj("ui").int("order") ?: 0,
                    dashboard = c.obj("ui").bool("dashboard") ?: true,
                    liveStreams = c.obj("live").obj("streams").mapNotNull { (label, v) ->
                        (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }?.let { label to it }
                    }.toMap(),
                    onvifConfigured = !c.obj("onvif").str("host").isNullOrBlank(),
                )
            }.sortedWith(compareBy({ it.order }, { it.name }))
            return FrigateConfig(cams)
        }
    }
}

/**
 * `GET /api/{camera}/ptz/info`: `{}` when the camera has no ONVIF. Features: `pt` (continuous pan/tilt), `zoom`,
 * `pt-r`/`zoom-r` (relative), `zoom-a` (absolute), `focus`, `pt-r-fov`. Presets are names.
 */
@Serializable
data class PtzInfo(
    val name: String = "",
    val features: List<String> = emptyList(),
    val presets: List<String> = emptyList(),
) {
    val canMove: Boolean get() = "pt" in features
    val canZoom: Boolean get() = "zoom" in features
    val canFocus: Boolean get() = "focus" in features
    val available: Boolean get() = canMove || canZoom || presets.isNotEmpty()
}

/** `GET /api/stats`, reduced to what the System screen shows. */
data class FrigateStats(
    val version: String? = null,
    /** Newest Frigate release the server knows about (it checks GitHub unless `telemetry.version_check` is off). */
    val latestVersion: String? = null,
    val uptimeSeconds: Long? = null,
    val cameras: List<CameraStats> = emptyList(),
    val detectors: List<DetectorStats> = emptyList(),
    val storage: List<StorageStats> = emptyList(),
    val cpuPercent: Double? = null,
    val memPercent: Double? = null,
    val gpus: List<GpuStats> = emptyList(),
) {
    companion object {
        fun parse(root: JsonObject): FrigateStats {
            val service = root.obj("service")
            val cameras = root.obj("cameras").mapNotNull { (name, v) ->
                val c = v as? JsonObject ?: return@mapNotNull null
                CameraStats(
                    name = name,
                    cameraFps = c.dbl("camera_fps") ?: 0.0,
                    detectionFps = c.dbl("detection_fps") ?: 0.0,
                    skippedFps = c.dbl("skipped_fps") ?: 0.0,
                    connectionQuality = c.str("connection_quality"),
                )
            }.sortedBy { it.name }
            val detectors = root.obj("detectors").mapNotNull { (name, v) ->
                val d = v as? JsonObject ?: return@mapNotNull null
                DetectorStats(name, d.dbl("inference_speed") ?: 0.0)
            }
            val storage = service.obj("storage").mapNotNull { (path, v) ->
                val s = v as? JsonObject ?: return@mapNotNull null
                val total = s.dbl("total") ?: return@mapNotNull null
                StorageStats(path, total = total, used = s.dbl("used") ?: 0.0)
            }.filter { it.total > 0 && !it.path.startsWith("/dev/shm") && !it.path.startsWith("/tmp") }
            val full = root.obj("cpu_usages").obj("frigate.full_system")
            val gpus = root.obj("gpu_usages").mapNotNull { (name, v) ->
                val g = v as? JsonObject ?: return@mapNotNull null
                GpuStats(name, g.str("gpu")?.removeSuffix("%")?.trim()?.toDoubleOrNull(), g.str("mem")?.removeSuffix("%")?.trim()?.toDoubleOrNull())
            }
            return FrigateStats(
                version = service.str("version"),
                latestVersion = service.str("latest_version")?.takeIf { it.isNotBlank() && it != "disabled" && it != "unknown" },
                uptimeSeconds = service.long("uptime"),
                cameras = cameras,
                detectors = detectors,
                storage = storage,
                cpuPercent = full.str("cpu")?.toDoubleOrNull(),
                memPercent = full.str("mem")?.toDoubleOrNull(),
                gpus = gpus,
            )
        }
    }
}

data class CameraStats(val name: String, val cameraFps: Double, val detectionFps: Double, val skippedFps: Double, val connectionQuality: String?) {
    /** A camera that delivers no frames is offline (Frigate shows it as "no frames received"). */
    val online: Boolean get() = cameraFps > 0.0
}
data class DetectorStats(val name: String, val inferenceMs: Double)
/** Sizes in MB, as Frigate reports them. */
data class StorageStats(val path: String, val total: Double, val used: Double) {
    val fraction: Float get() = if (total > 0) (used / total).toFloat().coerceIn(0f, 1f) else 0f
}
data class GpuStats(val name: String, val gpuPercent: Double?, val memPercent: Double?)

// Small, null-safe accessors for the loosely typed parts of Frigate's JSON.
internal fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())
internal fun JsonObject.obj(key: String): JsonObject = (this[key] as? JsonObject) ?: JsonObject(emptyMap())
internal fun JsonObject.prim(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }
internal fun JsonObject.str(key: String): String? = prim(key)?.content
internal fun JsonObject.bool(key: String): Boolean? = prim(key)?.let { it.booleanOrNull ?: it.intOrNull?.let { n -> n != 0 } }
internal fun JsonObject.int(key: String): Int? = prim(key)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
internal fun JsonObject.long(key: String): Long? = prim(key)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
internal fun JsonObject.dbl(key: String): Double? = prim(key)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
