package io.github.revocx35.frigatemobile.data

import android.content.Context
import androidx.core.content.edit
import io.github.revocx35.frigatemobile.core.Credentials
import io.github.revocx35.frigatemobile.core.ServerAddress
import io.github.revocx35.frigatemobile.core.SessionToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The signed-in Frigate server and user. Secrets are only kept encrypted (see [AccountStore]). */
@Serializable
data class Account(
    val server: String,
    /** Empty when the server has authentication turned off. */
    val user: String = "",
    val role: String = "viewer",
    val cookieName: String = "frigate_token",
    val tokenEnc: String? = null,
    /** Only set when the user chose "stay signed in": renews the session when Frigate's token expires. */
    val passwordEnc: String? = null,
    val authDisabled: Boolean = false,
) {
    val address: ServerAddress get() = ServerAddress.parse(server) ?: error("Stored server address is invalid")
    val isAdmin: Boolean get() = role == "admin"
    val remembersPassword: Boolean get() = passwordEnc != null
}

/**
 * The account in private SharedPreferences (excluded from backups). The session token and the remembered password
 * are encrypted with [cipher] (AES-GCM, key in the Android Keystore). Safe to call from any thread.
 */
class AccountStore(context: Context, private val cipher: SecretCipher) {
    private val sp = context.getSharedPreferences("account", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _account = MutableStateFlow(load())
    val account: StateFlow<Account?> = _account.asStateFlow()

    private fun load(): Account? = sp.getString(KEY, null)
        ?.let { runCatching { json.decodeFromString(Account.serializer(), it) }.getOrNull() }
        ?.takeIf { ServerAddress.parse(it.server) != null }

    @Synchronized
    private fun persist(a: Account?) {
        sp.edit { if (a == null) remove(KEY) else putString(KEY, json.encodeToString(Account.serializer(), a)) }
        _account.value = a
    }

    @Synchronized
    fun save(address: ServerAddress, user: String, role: String, token: SessionToken?, password: String?, authDisabled: Boolean) {
        persist(
            Account(
                server = address.display,
                user = user,
                role = role,
                cookieName = token?.cookieName ?: "frigate_token",
                tokenEnc = token?.value?.let(cipher::encrypt),
                passwordEnc = password?.let(cipher::encrypt),
                authDisabled = authDisabled,
            ),
        )
    }

    @Synchronized
    fun setToken(token: SessionToken?) {
        val a = _account.value ?: return
        persist(a.copy(tokenEnc = token?.value?.let(cipher::encrypt), cookieName = token?.cookieName ?: a.cookieName))
    }

    @Synchronized
    fun setRole(role: String) {
        val a = _account.value ?: return
        if (a.role != role) persist(a.copy(role = role))
    }

    fun token(a: Account): SessionToken? = a.tokenEnc?.let(cipher::decrypt)?.let { SessionToken(a.cookieName, it) }

    fun credentials(a: Account): Credentials? = a.passwordEnc?.let(cipher::decrypt)?.let { Credentials(a.user, it) }

    /** Signs out: forgets token and password, keeps nothing secret. The server address and user stay prefilled. */
    @Synchronized
    fun clear() {
        _account.value?.let { sp.edit { putString(KEY_LAST_SERVER, it.server); putString(KEY_LAST_USER, it.user) } }
        persist(null)
    }

    val lastServer: String? get() = sp.getString(KEY_LAST_SERVER, null)
    val lastUser: String? get() = sp.getString(KEY_LAST_USER, null)

    private companion object {
        const val KEY = "account_v1"
        const val KEY_LAST_SERVER = "last_server"
        const val KEY_LAST_USER = "last_user"
    }
}
