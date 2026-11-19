package io.github.revocx35.frigatemobile.core

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress

/**
 * The address of a Frigate instance, normalized from what the user typed:
 * `frigate.example.com` -> `https://frigate.example.com`, `192.168.1.5:8971/review` -> `https://192.168.1.5:8971`.
 * Sub-path installs behind a reverse proxy (`https://example.com/frigate`) keep their path.
 *
 * Only HTTPS is accepted: the app sends passwords, session tokens and camera video, which must never travel in
 * cleartext. Frigate's authenticated port 8971 serves TLS out of the box (self-signed; the app pins it).
 */
data class ServerAddress(val baseUrl: HttpUrl) {
    init {
        require(baseUrl.isHttps) { "Frigate Mobile only connects over HTTPS" }
    }

    val display: String get() = baseUrl.toString().removeSuffix("/")
    val host: String get() = baseUrl.host

    /** Resolves a server-relative path (`api/stats`, `/vod/...`) against the base URL (keeps a sub-path). */
    fun resolve(path: String): HttpUrl = baseUrl.resolve(path.trimStart('/')) ?: error("Bad path: $path")

    /** True if [url] points at this server (same scheme, host and port): only such requests get the session. */
    fun owns(url: HttpUrl): Boolean = url.scheme == baseUrl.scheme && url.host.equals(baseUrl.host, true) && url.port == baseUrl.port

    override fun toString() = display

    sealed interface ParseResult {
        data class Ok(val address: ServerAddress) : ParseResult
        data object Invalid : ParseResult
        data object Cleartext : ParseResult
    }

    companion object {
        fun parse(input: String): ServerAddress? = (tryParse(input) as? ParseResult.Ok)?.address

        fun tryParse(input: String): ParseResult {
            var text = input.trim()
            if (text.isEmpty() || text.any { it.isWhitespace() }) return ParseResult.Invalid
            if (!text.contains("://")) {
                text = "https://$text"
            } else if (text.startsWith("http://", ignoreCase = true)) {
                return ParseResult.Cleartext
            }
            val url = text.toHttpUrlOrNull() ?: return ParseResult.Invalid
            if (url.host.isEmpty() || !url.isHttps) return ParseResult.Invalid
            val segments = url.pathSegments.filter { it.isNotEmpty() }.toMutableList()
            // People paste the address of the page they're on: drop the API root and the web UI's routes.
            val cut = segments.indexOfFirst { it in CUT_AT }
            if (cut >= 0) while (segments.size > cut) segments.removeAt(segments.size - 1)
            val base = url.newBuilder().username("").password("").query(null).fragment(null).encodedPath("/").apply {
                segments.forEach { addPathSegment(it) }
                if (segments.isNotEmpty()) addPathSegment("")
            }.build()
            return ParseResult.Ok(ServerAddress(base))
        }

        // First path segments of Frigate's API and web UI routes.
        private val CUT_AT = setOf(
            "api", "login", "review", "explore", "export", "exports", "system", "config", "logs", "faces",
            "settings", "events", "recording", "live", "vod", "clips", "ws", "index.html",
        )

        /** Private LAN address (RFC 1918, link-local, IPv6 ULA fc00::/7). */
        fun isLanAddress(addr: InetAddress): Boolean =
            addr.isSiteLocalAddress || addr.isLinkLocalAddress ||
                (addr.address.size == 16 && (addr.address[0].toInt() and 0xfe) == 0xfc)

        /**
         * True if [host] is (or resolves to) a LAN address or uses a local-only name (`.local`, `.lan`, …).
         * May do a DNS lookup: call it off the main thread. Android 17+ needs a runtime permission for these.
         */
        fun resolvesToLan(host: String): Boolean {
            val h = host.lowercase().trim('[', ']')
            if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa") || h.endsWith(".internal")) return true
            val addrs = runCatching { InetAddress.getAllByName(h) }.getOrNull() ?: return false
            return addrs.any { isLanAddress(it) }
        }
    }
}
