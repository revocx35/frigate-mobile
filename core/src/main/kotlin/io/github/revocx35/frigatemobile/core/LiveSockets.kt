package io.github.revocx35.frigatemobile.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * go2rtc's MSE protocol through Frigate (`/live/mse/api/ws?src=<stream>`): the client sends the codecs it can play,
 * go2rtc answers `{"type":"mse","value":"video/mp4; codecs=..."}` and then streams fragmented MP4 (init segment,
 * then moof/mdat fragments) as binary messages. Full camera resolution and frame rate, unlike the MJPEG feed.
 *
 * [http] must be the session's client (cookie + pinning). Binary messages are queued for one reader ([next]).
 */
class MseStream private constructor(private val queue: LinkedBlockingQueue<Any>) : Closeable {
    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false

    /** The MIME type go2rtc chose, e.g. `video/mp4; codecs="avc1.640029,mp4a.40.2"`. */
    var mimeType: String = ""
        private set

    /** The next binary message, waiting up to [timeoutMs]; null at the end of the stream. */
    @Throws(IOException::class)
    fun next(timeoutMs: Long): ByteArray? {
        if (closed && queue.isEmpty()) return null
        val item = try {
            queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            throw InterruptedIOException("Live stream interrupted")
        } ?: throw FrigateException.Protocol("The camera stopped sending video.")
        return when (item) {
            is ByteArray -> item
            is Throwable -> throw (item as? IOException ?: IOException(item.toUserMessage(), item))
            else -> null
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        socket?.cancel()
        queue.offer(END)
    }

    companion object {
        private val END = Any()

        /** Codecs Frigate's web UI offers; HEVC only when the device can decode it. */
        fun codecs(hevc: Boolean): String = buildList {
            addAll(listOf("avc1.640029", "avc1.64002A", "avc1.640033"))
            if (hevc) add("hvc1.1.6.L153.B0")
            addAll(listOf("mp4a.40.2", "mp4a.40.5", "flac", "opus"))
        }.joinToString(",")

        /**
         * Connects and waits until go2rtc has chosen the format. Throws [FrigateException.Forbidden] when Frigate
         * refuses the stream (no such go2rtc stream, or the role may not see the camera), or the go2rtc error.
         */
        suspend fun open(http: OkHttpClient, url: HttpUrl, codecs: String, timeoutMs: Long = 15_000): MseStream {
            val queue = LinkedBlockingQueue<Any>()
            val stream = MseStream(queue)
            val ready = CompletableDeferred<String>()
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(buildJsonObject { put("type", "mse"); put("value", codecs) }.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val msg = runCatching { FrigateJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
                    when (msg.str("type")) {
                        "mse" -> ready.complete(msg.str("value").orEmpty())
                        "error" -> fail(FrigateException.Protocol("go2rtc: ${msg.str("value") ?: "stream error"}"))
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (!stream.closed) queue.offer(bytes.toByteArray())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    if (!ready.isCompleted) ready.completeExceptionally(FrigateException.Protocol("The live stream closed (${reason.ifBlank { code.toString() }})."))
                    queue.offer(END)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    fail(
                        when (response?.code) {
                            401 -> FrigateException.Unauthorized()
                            403 -> FrigateException.Forbidden()
                            404 -> FrigateException.NotFound("The live stream endpoint")
                            null -> t
                            else -> FrigateException.Http(response.code, "Live stream refused (HTTP ${response.code}).")
                        },
                    )
                }

                private fun fail(e: Throwable) {
                    if (!ready.completeExceptionally(e)) queue.offer(e)
                }
            }
            val client = http.newBuilder().pingInterval(15, TimeUnit.SECONDS).build()
            stream.socket = client.newWebSocket(Request.Builder().url(url).build(), listener)
            try {
                stream.mimeType = withTimeout(timeoutMs) { ready.await() }
            } catch (e: Throwable) {
                stream.close()
                throw e
            }
            return stream
        }
    }
}

/** PTZ commands as Frigate's `<camera>/ptz` topic takes them (Frigate lower-cases them). */
enum class PtzCommand(val payload: String) {
    Up("MOVE_UP"), Down("MOVE_DOWN"), Left("MOVE_LEFT"), Right("MOVE_RIGHT"),
    ZoomIn("ZOOM_IN"), ZoomOut("ZOOM_OUT"), FocusIn("FOCUS_IN"), FocusOut("FOCUS_OUT"), Stop("STOP"),
}

/**
 * Frigate's websocket (`/ws`), used to send commands the REST API doesn't have, such as PTZ. Frigate allows a
 * camera's `ptz` topic to admins and to roles that may see that camera. Connects on first use and again after a
 * failure; incoming state messages are ignored. Never blocks: OkHttp queues messages until the socket is open.
 */
class FrigateSocket(private val http: OkHttpClient, private val url: HttpUrl) : Closeable {
    private var socket: WebSocket? = null
    @Volatile private var broken = false
    @Volatile var lastError: Throwable? = null
        private set

    private val listener = object : WebSocketListener() {
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            lastError = when (response?.code) {
                401 -> FrigateException.Unauthorized()
                403 -> FrigateException.Forbidden()
                else -> t
            }
            broken = true
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            broken = true
            webSocket.close(1000, null)
        }
    }

    @Synchronized
    private fun current(): WebSocket {
        val s = socket
        if (s != null && !broken) return s
        s?.cancel()
        broken = false
        return http.newWebSocket(Request.Builder().url(url).build(), listener).also { socket = it }
    }

    /** Opens the connection ahead of the first command, so the first press reacts at once. */
    fun warmUp() {
        current()
    }

    /** Publishes [payload] on [topic]; false when it couldn't be queued even after reconnecting. */
    fun publish(topic: String, payload: String): Boolean {
        val msg = buildJsonObject { put("topic", topic); put("payload", payload); put("retain", false) }.toString()
        if (current().send(msg)) return true
        synchronized(this) { broken = true }
        return current().send(msg)
    }

    fun ptz(camera: String, command: PtzCommand): Boolean = publish("$camera/ptz", command.payload)

    /** Moves to a preset by name (`preset_<name>`). */
    fun ptzPreset(camera: String, preset: String): Boolean = publish("$camera/ptz", "preset_$preset")

    @Synchronized
    override fun close() {
        socket?.close(1000, null)
        socket = null
    }
}
