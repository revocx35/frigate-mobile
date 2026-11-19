package io.github.revocx35.frigatemobile

import androidx.test.core.app.ApplicationProvider
import io.github.revocx35.frigatemobile.core.ServerAddress
import io.github.revocx35.frigatemobile.core.SessionToken
import io.github.revocx35.frigatemobile.data.AccountStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AccountStoreTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<TestApp>()

    @Test fun secretsEncryptedAndCleared() {
        val store = AccountStore(ctx, FakeCipher())
        store.save(ServerAddress.parse("https://nvr.example:8971")!!, "admin", "admin", SessionToken("frigate_token", "a.b.c"), "pw", false)
        val raw = ctx.getSharedPreferences("account", 0).all.values.joinToString()
        assertFalse(raw.contains("\"a.b.c\""))
        assertFalse(raw.contains("\"pw\""))
        val a = store.account.value!!
        assertEquals("a.b.c", store.token(a)!!.value)
        assertEquals("pw", store.credentials(a)!!.password)

        // A second store (app restart) reads the same account.
        assertEquals("admin", AccountStore(ctx, FakeCipher()).account.value!!.user)

        store.clear()
        assertNull(store.account.value)
        assertEquals("https://nvr.example:8971", store.lastServer)
        assertNull(AccountStore(ctx, FakeCipher()).account.value)
    }

    @Test fun noPasswordUnlessAsked() {
        val store = AccountStore(ctx, FakeCipher())
        store.save(ServerAddress.parse("https://nvr.example")!!, "u", "viewer", SessionToken("frigate_token", "a.b.c"), null, false)
        assertNull(store.credentials(store.account.value!!))
        store.setToken(null)
        assertNull(store.token(store.account.value!!))
    }
}
