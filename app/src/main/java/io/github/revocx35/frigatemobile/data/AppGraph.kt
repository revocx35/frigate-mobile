package io.github.revocx35.frigatemobile.data

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import io.github.revocx35.frigatemobile.core.Credentials
import io.github.revocx35.frigatemobile.core.FrigateClient
import io.github.revocx35.frigatemobile.core.FrigateException
import io.github.revocx35.frigatemobile.core.FrigateSession
import io.github.revocx35.frigatemobile.core.ServerAddress
import io.github.revocx35.frigatemobile.core.SessionToken
import io.github.revocx35.frigatemobile.core.toUserMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Path.Companion.toOkioPath
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.io.IOException

/** Everything that talks to the signed-in Frigate: one session, one HTTP client for API, images and video. */
class Connection(
    val account: Account,
    val session: FrigateSession,
    val http: OkHttpClient,
    val client: FrigateClient,
    val imageLoader: ImageLoader,
    /** The newest frame of each camera seen this session (camera grid), so it isn't blank when shown again. */
    val frames: MutableMap<String, android.graphics.Bitmap> = java.util.concurrent.ConcurrentHashMap(),
)

sealed interface SignInResult {
    data object Ok : SignInResult
    /** The server's certificate isn't trusted by Android: ask the user to check and pin it. */
    data class UntrustedCertificate(val chain: List<X509Certificate>) : SignInResult
    data class Failed(val message: String) : SignInResult
}

/** Manual dependency graph, created once in [io.github.revocx35.frigatemobile.FrigateMobileApp]. */
class AppGraph(val context: Context, cipher: SecretCipher = KeystoreCipher()) {
    val accounts = AccountStore(context, cipher)
    val settings = SettingsStore(context)
    val trust = ServerTrust(context)
    val appIcon = AppIcon(context)

    /** No cookies jar, no redirects to other schemes; certificates checked by [trust]. */
    val baseHttp: OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(trust.socketFactory, trust)
        .hostnameVerifier(trust.hostnameVerifier)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .build()

    private val _connection = MutableStateFlow<Connection?>(null)
    val connection: StateFlow<Connection?> = _connection.asStateFlow()

    /** Set when the session ended and could not be renewed; the sign-in screen explains why. */
    private val _signedOutReason = MutableStateFlow<String?>(null)
    val signedOutReason: StateFlow<String?> = _signedOutReason.asStateFlow()

    init {
        accounts.account.value?.let { connect(it) }
    }

    private fun connect(account: Account) {
        trust.serverHost = account.address.host
        val session = FrigateSession(account.address, accounts.token(account), accounts.credentials(account)) { token ->
            accounts.setToken(token)
            if (token == null && !account.authDisabled) sessionEnded()
        }
        session.authDisabled = account.authDisabled
        val http = baseHttp.newBuilder().addInterceptor(session.interceptor).build()
        val loader = ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { http })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
            .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("images").toOkioPath()).maxSizeBytes(64L * 1024 * 1024).build() }
            .crossfade(true)
            .build()
        _connection.value?.imageLoader?.shutdown()
        _connection.value = Connection(account, session, http, FrigateClient(session, http), loader)
    }

    private fun sessionEnded() {
        _signedOutReason.value = "Your Frigate session has ended. Sign in again."
        disconnect()
    }

    /** Background work that must not run on the main thread (closing TLS connections sends close_notify). */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun disconnect() {
        _connection.value?.let { c ->
            c.session.signOutLocally()
            c.imageLoader.shutdown()
            io.launch { c.http.connectionPool.evictAll() }
        }
        _connection.value = null
        accounts.clear()
        runCatching { AlertWorker.cancel(context) }
    }

    /** Signs out locally (Frigate's JWT can't be revoked server-side; it is forgotten here). */
    fun signOut() {
        _signedOutReason.value = null
        disconnect()
        io.launch { context.cacheDir.resolve("images").deleteRecursively() }
    }

    /**
     * Checks the server, signs in and stores the account. With an empty [user] the server must have
     * authentication turned off. [remember] keeps the password (encrypted) to renew expired sessions silently.
     */
    suspend fun signIn(address: ServerAddress, user: String, password: String, remember: Boolean): SignInResult = withContext(Dispatchers.IO) {
        trust.serverHost = address.host
        trust.resetRejected()
        val session = FrigateSession(address, null, null)
        val http = baseHttp.newBuilder().addInterceptor(session.interceptor).build()
        try {
            val code = http.newCall(Request.Builder().url(address.resolve("api/version")).build()).execute().use { it.code }
            var token: SessionToken? = null
            var authDisabled = false
            if (user.isBlank()) {
                if (code == 401 || code == 403) return@withContext SignInResult.Failed("This Frigate requires a username and password.")
                authDisabled = true
            } else {
                try {
                    token = session.signIn(http, Credentials(user.trim(), password))
                } catch (_: FrigateException.AuthDisabled) {
                    authDisabled = true
                }
            }
            session.authDisabled = authDisabled
            val client = FrigateClient(session, http)
            val profile = client.profile()
            client.config()
            accounts.save(address, if (authDisabled) "" else user.trim(), profile.role, token, password.takeIf { remember && !authDisabled }, authDisabled)
            _signedOutReason.value = null
            connect(accounts.account.value!!)
            SignInResult.Ok
        } catch (e: IOException) {
            // With several addresses (IPv6 + IPv4) OkHttp reports a ConnectException and only suppresses the
            // TLS error, so check what the trust manager saw rather than the exception type.
            trust.lastRejected?.let { SignInResult.UntrustedCertificate(it) } ?: SignInResult.Failed(e.toUserMessage())
        } catch (e: FrigateException.NotFound) {
            SignInResult.Failed("This doesn't look like a Frigate server (${e.message}). Use Frigate's address, usually https://<host>:8971.")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            SignInResult.Failed(e.toUserMessage())
        } finally {
            http.connectionPool.evictAll()
        }
    }

    /** Refreshes the stored role (an admin may have changed it). */
    fun updateRole(role: String) {
        val a = accounts.account.value ?: return
        if (a.role == role) return
        accounts.setRole(role)
        _connection.value?.let { c -> _connection.value = Connection(accounts.account.value!!, c.session, c.http, c.client, c.imageLoader, c.frames) }
    }
}
