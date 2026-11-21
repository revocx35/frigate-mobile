package io.github.revocx35.frigatemobile.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.Locale

/**
 * Frigate's HTTP API (checked against 0.14 – 0.18). [http] must carry [FrigateSession.interceptor] for this
 * server. All calls run on [Dispatchers.IO] and throw [FrigateException] or [java.io.IOException].
 */
class FrigateClient(val session: FrigateSession, val http: OkHttpClient) {
    val address: ServerAddress get() = session.address
    val urls = MediaUrls(session.address)

    suspend fun version(): String = getText("api/version").trim()

    suspend fun profile(): Profile = get("api/profile", Profile.serializer())

    suspend fun config(): FrigateConfig = FrigateConfig.parse(getObject("api/config"))

    suspend fun stats(): FrigateStats = FrigateStats.parse(getObject("api/stats"))

    suspend fun reviews(
        severity: Severity? = null,
        reviewed: Boolean? = null,
        cameras: Collection<String>? = null,
        limit: Int = 100,
        before: Double? = null,
        after: Double? = null,
    ): List<ReviewItem> {
        val url = address.resolve("api/review").newBuilder().apply {
            addQueryParameter("limit", limit.toString())
            severity?.let { addQueryParameter("severity", it.api) }
            reviewed?.let { addQueryParameter("reviewed", if (it) "1" else "0") }
            if (!cameras.isNullOrEmpty()) addQueryParameter("cameras", cameras.joinToString(","))
            before?.let { addQueryParameter("before", fmt(it)) }
            after?.let { addQueryParameter("after", fmt(it)) }
        }.build()
        return get(url, ListSerializer(ReviewItem.serializer()))
    }

    /** One review item (`GET /api/review/{id}`). */
    suspend fun review(id: String): ReviewItem = get("api/review/${seg(id)}", ReviewItem.serializer())

    suspend fun reviewCounts(): ReviewCounts {
        val root = getObject("api/review/summary")
        val last = root["last24Hours"] as? JsonObject ?: return ReviewCounts()
        return FrigateJson.decodeFromJsonElement(ReviewCounts.serializer(), last)
    }

    /** Marks review items as reviewed (any signed-in role may do this). */
    suspend fun markReviewed(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val body = buildJsonObject { putJsonArray("ids") { ids.forEach { add(it) } }; put("reviewed", true) }
        send("POST", address.resolve("api/reviews/viewed"), body)
    }

    /** Tracked objects, newest first. [after]/[before] filter on the start time. */
    suspend fun events(camera: String? = null, limit: Int = 50, before: Double? = null, after: Double? = null): List<FrigateEvent> {
        val url = address.resolve("api/events").newBuilder().apply {
            addQueryParameter("limit", limit.toString())
            camera?.let { addQueryParameter("cameras", it) }
            before?.let { addQueryParameter("before", fmt(it)) }
            after?.let { addQueryParameter("after", fmt(it)) }
        }.build()
        return get(url, ListSerializer(FrigateEvent.serializer()))
    }

    /** The tracked objects of a review item. `GET /api/event_ids` (0.14+), else one request per id. */
    suspend fun eventsByIds(ids: Collection<String>): List<FrigateEvent> {
        if (ids.isEmpty()) return emptyList()
        val url = address.resolve("api/event_ids").newBuilder().addQueryParameter("ids", ids.joinToString(",")).build()
        return try {
            get(url, ListSerializer(FrigateEvent.serializer()))
        } catch (_: FrigateException.NotFound) {
            ids.mapNotNull { id -> runCatching { get("api/events/${seg(id)}", FrigateEvent.serializer()) }.getOrNull() }
        }.sortedBy { it.startTime }
    }

    /**
     * A camera's motion between [after] and [before] in [scale]-second steps (`GET /api/review/activity/motion`,
     * 0.14+, from the recording segments). Any role that may see the camera.
     */
    suspend fun motionActivity(camera: String, after: Double, before: Double, scale: Int = 60): List<MotionSample> {
        val url = address.resolve("api/review/activity/motion").newBuilder()
            .addQueryParameter("cameras", camera)
            .addQueryParameter("after", fmt(after))
            .addQueryParameter("before", fmt(before))
            .addQueryParameter("scale", scale.toString())
            .build()
        return Timeline.parseMotion(get(url, JsonArray.serializer()))
    }

    suspend fun recordingDays(camera: String, timezone: String): List<RecordingDay> {
        val url = address.resolve("api/${seg(camera)}/recordings/summary").newBuilder().addQueryParameter("timezone", timezone).build()
        return get(url, ListSerializer(RecordingDay.serializer())).sortedByDescending { it.day }
    }

    /**
     * Turns a camera feature on or off. Admin only. Uses `PUT /api/camera/{name}/set/{feature}` (Frigate 0.17+);
     * older releases only offer this over MQTT/websocket and answer 404/405 here.
     */
    suspend fun setCameraFeature(camera: String, feature: CameraFeature, on: Boolean) {
        val body = buildJsonObject { put("value", if (on) "ON" else "OFF") }
        try {
            send("PUT", address.resolve("api/camera/${seg(camera)}/set/${feature.api}"), body)
        } catch (e: FrigateException.Http) {
            if (e.code == 404 || e.code == 405) throw FrigateException.Unsupported("Switching camera features from the app")
            throw e
        } catch (_: FrigateException.NotFound) {
            throw FrigateException.Unsupported("Switching camera features from the app")
        }
    }

    /** PTZ capabilities and presets (ONVIF). Empty [PtzInfo] when the camera has none; any role with access may ask. */
    suspend fun ptzInfo(camera: String): PtzInfo = get("api/${seg(camera)}/ptz/info", PtzInfo.serializer())

    /**
     * What go2rtc can do with a stream (`GET /api/go2rtc/streams/{name}`). Frigate asks go2rtc with its `microphone`
     * probe, so a camera with a talkback channel lists an `audio, sendonly` media. Any role that may see the stream.
     */
    suspend fun go2rtcStream(stream: String): Go2rtcStreamInfo =
        Go2rtcStreamInfo.parse(getObject("api/go2rtc/streams/${seg(stream)}"))

    // ---- plumbing ----

    private suspend fun <T> get(path: String, serializer: KSerializer<T>): T = get(address.resolve(path), serializer)

    private suspend fun <T> get(url: HttpUrl, serializer: KSerializer<T>): T = withContext(Dispatchers.IO) {
        execute(Request.Builder().url(url).header("Accept", "application/json").build()) { r ->
            val text = r.body.string()
            try {
                FrigateJson.decodeFromString(serializer, text)
            } catch (e: Exception) {
                throw FrigateException.Protocol("Unexpected answer from the server (${url.encodedPath}). Is this a Frigate server?")
            }
        }
    }

    private suspend fun getObject(path: String): JsonObject = get(path, JsonObject.serializer())

    private suspend fun getText(path: String): String = withContext(Dispatchers.IO) {
        execute(Request.Builder().url(address.resolve(path)).build()) { it.body.string() }
    }

    private suspend fun send(method: String, url: HttpUrl, body: JsonObject) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).method(method, body.toString().toRequestBody(JSON)).build()
        // Frigate answers {"success": false, "message": ...} with a 4xx for refused changes (see errorFor).
        execute(req) { }
    }

    private fun <T> execute(request: Request, read: (Response) -> T): T =
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw errorFor(r)
            read(r)
        }

    private fun errorFor(r: Response): FrigateException {
        val message = runCatching { r.peekBody(4096).string() }.getOrNull()
            ?.let { runCatching { (FrigateJson.parseToJsonElement(it) as? JsonObject)?.str("message") }.getOrNull() }
        return when (r.code) {
            401 -> FrigateException.Unauthorized()
            403 -> FrigateException.Forbidden()
            404 -> FrigateException.NotFound(message ?: r.request.url.encodedPath)
            429 -> FrigateException.RateLimited()
            else -> FrigateException.Http(r.code, message ?: "The server answered HTTP ${r.code}.")
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        internal fun fmt(t: Double) = String.format(Locale.ROOT, "%.3f", t)
        /** One encoded path segment (camera names are `[a-zA-Z0-9_-]` in Frigate, but be safe). */
        internal fun seg(s: String): String = HttpUrl.Builder().scheme("https").host("x").addPathSegment(s).build().encodedPath.removePrefix("/")
    }
}

enum class CameraFeature(val api: String, val label: String) {
    Enabled("enabled", "Camera"),
    Detect("detect", "Object detection"),
    Recordings("recordings", "Recording"),
    Snapshots("snapshots", "Snapshots"),
    Audio("audio", "Audio detection"),
    Motion("motion", "Motion detection"),
}

/** URLs of Frigate's images and video. They are fetched through the session's OkHttp client (cookie added there). */
class MediaUrls(private val address: ServerAddress) {
    private fun cam(c: String) = FrigateClient.seg(c)

    /** The newest frame of a camera. `h` is the pre-0.15 name of `height`. */
    fun latest(camera: String, height: Int, cacheBust: Long? = null): HttpUrl =
        address.resolve("api/${cam(camera)}/latest.jpg").newBuilder()
            .addQueryParameter("height", height.toString()).addQueryParameter("h", height.toString())
            .apply { cacheBust?.let { addQueryParameter("t", it.toString()) } }.build()

    /** Frigate's MJPEG live feed (multipart/x-mixed-replace JPEGs) at the detect resolution, scaled to [height]. */
    fun mjpeg(camera: String, fps: Int, height: Int, boxes: Boolean = false): HttpUrl =
        address.resolve("api/${cam(camera)}").newBuilder()
            .addQueryParameter("fps", fps.toString())
            .addQueryParameter("height", height.toString()).addQueryParameter("h", height.toString())
            .apply { if (boxes) addQueryParameter("bbox", "1") }.build()

    /**
     * go2rtc's MSE live stream (fMP4 over a websocket) of a go2rtc stream name (`live.streams` value). Frigate's
     * nginx authenticates it and refuses (403) names that aren't existing streams or that the role may not see.
     */
    fun mse(stream: String): HttpUrl =
        address.resolve("live/mse/api/ws").newBuilder().addQueryParameter("src", stream).build()

    /**
     * go2rtc's WebRTC signaling websocket for a stream (`webrtc/offer`, `webrtc/answer`, `webrtc/candidate`), used
     * for two-way audio. Same nginx check as [mse].
     */
    fun webrtc(stream: String): HttpUrl =
        address.resolve("live/webrtc/api/ws").newBuilder().addQueryParameter("src", stream).build()

    /** Frigate's own websocket (`/ws`): state updates out, commands such as `<camera>/ptz` in. */
    fun socket(): HttpUrl = address.resolve("ws")

    /** A review item's thumbnail: `thumb_path` is a file path under /media/frigate, served from /clips/. */
    fun reviewThumb(item: ReviewItem): HttpUrl? {
        val p = item.thumbPath.takeIf { it.isNotBlank() } ?: return null
        val rel = p.substringAfter("/media/frigate/", p.trimStart('/'))
        return address.resolve(rel)
    }

    fun eventThumb(id: String): HttpUrl = address.resolve("api/events/${cam(id)}/thumbnail.jpg")

    fun eventSnapshot(id: String): HttpUrl = address.resolve("api/events/${cam(id)}/snapshot.jpg").newBuilder().addQueryParameter("bbox", "1").build()

    fun eventClip(id: String): HttpUrl = address.resolve("api/events/${cam(id)}/clip.mp4")

    /** An HLS playlist of the recordings between two times (nginx-vod), seekable and quick to start. */
    fun vod(camera: String, start: Double, end: Double): HttpUrl =
        address.resolve("vod/${cam(camera)}/start/${start.toLong()}/end/${kotlin.math.ceil(end).toLong()}/index.m3u8")

    /** An MP4 of the recordings between two times, for saving or sharing. */
    fun clip(camera: String, start: Double, end: Double): HttpUrl =
        address.resolve("api/${cam(camera)}/start/${start.toLong()}/end/${kotlin.math.ceil(end).toLong()}/clip.mp4")
}
