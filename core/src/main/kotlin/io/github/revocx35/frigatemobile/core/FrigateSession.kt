package io.github.revocx35.frigatemobile.core

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

/** Frigate's own sign-in (`auth.enabled`). [password] is kept only when the user chose to stay signed in. */
data class Credentials(val user: String, val password: String)

/** A session cookie as Frigate set it (the name is configurable: `auth.cookie_name`, default `frigate_token`). */
data class SessionToken(val cookieName: String, val value: String) {
    override fun toString() = "SessionToken($cookieName=***)" // never print the token
}

/**
 * The signed-in session with one Frigate server, shared by the API client, image loading and video playback
 * (all requests go through [interceptor]):
 * - adds the session cookie, only to requests for this server (never to another host),
 * - adds `X-CSRF-TOKEN` to state-changing requests (Frigate rejects them without it when an Origin is sent),
 * - picks up the renewed cookie Frigate sends when the session nears its end (`auth.refresh_time`),
 * - on a 401 signs in again once with the remembered password (if any) and retries the request.
 *
 * [onTokenChanged] persists a new token (or null when the session ended) and is called on OkHttp threads.
 */
class FrigateSession(
    val address: ServerAddress,
    token: SessionToken?,
    @Volatile var credentials: Credentials?,
    private val onTokenChanged: (SessionToken?) -> Unit = {},
) {
    @Volatile var token: SessionToken? = token
        private set

    /** Set when the server has auth turned off (login answers 404): requests then go without a cookie. */
    @Volatile var authDisabled: Boolean = false

    private val lock = Any()

    val interceptor: Interceptor = Interceptor { chain -> intercept(chain) }

    private fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (!address.owns(original.url)) return chain.proceed(original)
        val used = token
        val response = chain.proceed(authorize(original, used))
        capture(response)
        if (response.code != 401 || isLogin(original) || authDisabled) return response
        // The session expired or was revoked. Sign in again once (one thread does it, the others reuse it).
        val creds = credentials ?: return response.also { invalidate(used) }
        // OkHttp allows another proceed() only after the previous response is closed: keep a copy of the 401.
        val copy = response.newBuilder().body(response.peekBody(16 * 1024)).build()
        response.close()
        val fresh = synchronized(lock) {
            if (token != null && token != used) token
            else runCatching { login(chain, creds) }.getOrNull()
        } ?: return copy.also { invalidate(used) }
        val retried = chain.proceed(authorize(original, fresh))
        capture(retried)
        return retried
    }

    private fun isLogin(r: Request) = r.url.encodedPath.endsWith("/api/login")

    private fun authorize(r: Request, t: SessionToken?): Request = r.newBuilder().apply {
        if (t != null) header("Cookie", "${t.cookieName}=${t.value}")
        if (r.method != "GET" && r.method != "HEAD") header("X-CSRF-TOKEN", "1")
    }.build()

    private fun invalidate(used: SessionToken?) {
        synchronized(lock) {
            if (token == used && used != null) {
                token = null
                onTokenChanged(null)
            }
        }
    }

    /** Frigate renews the cookie in a Set-Cookie header; a Set-Cookie with an empty value ends the session. */
    private fun capture(response: Response) {
        val current = token ?: return
        val renewed = response.headers("Set-Cookie").firstNotNullOfOrNull { parseCookie(it)?.takeIf { c -> c.cookieName == current.cookieName } }
            ?: return
        if (renewed.value != current.value && renewed.value.isNotEmpty()) {
            synchronized(lock) { token = renewed }
            onTokenChanged(renewed)
        }
    }

    private fun login(chain: Interceptor.Chain, creds: Credentials): SessionToken? {
        val request = loginRequest(address, creds)
        chain.proceed(request).use { r ->
            if (!r.isSuccessful) return null
            val t = r.headers("Set-Cookie").firstNotNullOfOrNull { parseCookie(it)?.takeIf { c -> c.looksLikeJwt() } } ?: return null
            token = t
            onTokenChanged(t)
            return t
        }
    }

    /** Signs in with [creds] through [client] (the app client, which carries [interceptor]). */
    @Throws(IOException::class, FrigateException::class)
    fun signIn(client: OkHttpClient, creds: Credentials): SessionToken {
        client.newCall(loginRequest(address, creds)).execute().use { r ->
            when {
                r.code == 404 -> throw FrigateException.AuthDisabled()
                r.code == 401 || r.code == 400 || r.code == 422 -> throw FrigateException.BadCredentials()
                r.code == 429 -> throw FrigateException.RateLimited()
                !r.isSuccessful -> throw FrigateException.Http(r.code, "Sign-in failed (HTTP ${r.code})")
            }
            val t = r.headers("Set-Cookie").firstNotNullOfOrNull { parseCookie(it)?.takeIf { c -> c.looksLikeJwt() } }
                ?: throw FrigateException.Protocol("The server accepted the sign-in but sent no session cookie. Is this a Frigate server?")
            synchronized(lock) { token = t }
            onTokenChanged(t)
            return t
        }
    }

    fun signOutLocally() {
        synchronized(lock) { token = null }
        credentials = null
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        fun loginRequest(address: ServerAddress, creds: Credentials): Request {
            val body = buildJsonObject { put("user", creds.user); put("password", creds.password) }.toString()
            return Request.Builder().url(address.resolve("api/login")).post(body.toRequestBody(JSON))
                .header("X-CSRF-TOKEN", "1").build()
        }

        fun parseCookie(header: String): SessionToken? {
            val first = header.substringBefore(';')
            val eq = first.indexOf('=')
            if (eq <= 0) return null
            return SessionToken(first.substring(0, eq).trim(), first.substring(eq + 1).trim().removeSurrounding("\""))
        }

        private fun SessionToken.looksLikeJwt() = value.count { it == '.' } == 2
    }
}
