package io.github.revocx35.frigatemobile.data

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import javax.net.ssl.HttpsURLConnection
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * TLS trust: the system's CAs first (and user-installed ones, see network_security_config). Frigate's port 8971
 * serves a self-signed certificate by default, so the user can pin the server's exact certificate after checking
 * its SHA-256 fingerprint. A pin is scoped to the host it was confirmed for: one server never widens the trust of
 * another. For a pinned certificate the hostname check is skipped (Frigate's generated certificate does not name
 * the LAN address), which is safe because only that exact certificate is accepted.
 */
class ServerTrust(context: Context) : X509ExtendedTrustManager() {
    private val sp = context.getSharedPreferences("trust", Context.MODE_PRIVATE)

    private val system: X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers.filterIsInstance<X509TrustManager>().first()

    /** The host of the server in use (or being added); only its pinned certificates are loaded. */
    @Volatile
    var serverHost: String? = null
        set(v) {
            if (field == v) return
            field = v
            pins = load(v)
            lastRejected = null
        }

    @Volatile private var pins: List<X509Certificate> = emptyList()

    /** The chain of the last connection to [serverHost] that the system rejected (shown to the user for review). */
    @Volatile var lastRejected: List<X509Certificate>? = null
        private set

    val socketFactory: SSLSocketFactory = SSLContext.getInstance("TLS").apply { init(null, arrayOf(this@ServerTrust), null) }.socketFactory

    private val defaultVerifier: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()

    val hostnameVerifier = HostnameVerifier { host, session -> verifyHost(host, session) }

    fun pinned(): List<X509Certificate> = pins

    fun resetRejected() {
        lastRejected = null
    }

    fun pin(cert: X509Certificate) {
        val host = serverHost ?: return
        val list = pins.filterNot { it.encoded.contentEquals(cert.encoded) } + cert
        save(host, list)
        pins = list
        lastRejected = null
    }

    fun clearPins(host: String) {
        sp.edit { remove(key(host)) }
        if (host.equals(serverHost, true)) pins = emptyList()
    }

    private fun key(host: String) = "pins_" + host.lowercase()

    private fun load(host: String?): List<X509Certificate> =
        host?.let { sp.getStringSet(key(it), null) }.orEmpty().mapNotNull { parse(Base64.decode(it, Base64.NO_WRAP)) }

    private fun save(host: String, list: List<X509Certificate>) =
        sp.edit { putStringSet(key(host), list.map { Base64.encodeToString(it.encoded, Base64.NO_WRAP) }.toSet()) }

    private fun isPinnedLeaf(chain: Array<out java.security.cert.Certificate>?): Boolean {
        val leaf = chain?.firstOrNull() as? X509Certificate ?: return false
        return pins.any { it.encoded.contentEquals(leaf.encoded) } && runCatching { leaf.checkValidity() }.isSuccess
    }

    private fun verifyHost(host: String, session: SSLSession): Boolean {
        if (defaultVerifier.verify(host, session)) return true
        if (!host.equals(serverHost, ignoreCase = true)) return false
        return isPinnedLeaf(runCatching { session.peerCertificates }.getOrNull())
    }

    private fun check(chain: Array<X509Certificate>, host: String?, viaSystem: () -> Unit) {
        try {
            viaSystem()
        } catch (e: CertificateException) {
            if (host != null && host.equals(serverHost, ignoreCase = true)) {
                if (isPinnedLeaf(chain)) return
                lastRejected = chain.toList()
            }
            throw e
        }
    }

    private fun hostOf(socket: Socket?): String? = (socket as? SSLSocket)?.handshakeSession?.peerHost
    private fun hostOf(engine: SSLEngine?): String? = engine?.peerHost

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        check(chain, hostOf(socket)) {
            if (system is X509ExtendedTrustManager) system.checkServerTrusted(chain, authType, socket) else system.checkServerTrusted(chain, authType)
        }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        check(chain, hostOf(engine)) {
            if (system is X509ExtendedTrustManager) system.checkServerTrusted(chain, authType, engine) else system.checkServerTrusted(chain, authType)
        }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
        check(chain, null) { system.checkServerTrusted(chain, authType) }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) = throw CertificateException("no client certs")
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) = throw CertificateException("no client certs")
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException("no client certs")
    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

    companion object {
        fun parse(bytes: ByteArray): X509Certificate? = runCatching {
            CertificateFactory.getInstance("X.509").generateCertificate(bytes.inputStream()) as X509Certificate
        }.getOrNull()

        fun sha256(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }

        fun commonName(cert: X509Certificate): String {
            val dn = cert.subjectX500Principal.name
            return Regex("(?:^|,)CN=([^,]*)").find(dn)?.groupValues?.get(1) ?: dn
        }
    }
}
