package io.github.revocx35.frigatemobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.HttpUrl.Companion.toHttpUrl

class ServerAddressTest {
    @Test fun addsHttpsAndDropsUiRoutes() {
        assertEquals("https://frigate.example.com", ServerAddress.parse("frigate.example.com")!!.display)
        assertEquals("https://192.168.1.5:8971", ServerAddress.parse("192.168.1.5:8971/review?id=1")!!.display)
        assertEquals("https://192.168.1.5:8971", ServerAddress.parse("https://192.168.1.5:8971/api/config")!!.display)
        assertEquals("https://example.com/frigate", ServerAddress.parse("https://example.com/frigate/live")!!.display)
    }

    @Test fun refusesCleartextAndJunk() {
        assertEquals(ServerAddress.ParseResult.Cleartext, ServerAddress.tryParse("http://192.168.1.5:5000"))
        assertEquals(ServerAddress.ParseResult.Invalid, ServerAddress.tryParse(""))
        assertEquals(ServerAddress.ParseResult.Invalid, ServerAddress.tryParse("a b"))
        assertEquals(ServerAddress.ParseResult.Invalid, ServerAddress.tryParse("ftp://x"))
        assertNull(ServerAddress.parse("http://frigate.local"))
    }

    @Test fun stripsUserInfo() {
        assertEquals("https://h.example", ServerAddress.parse("https://user:pw@h.example/")!!.display)
    }

    @Test fun subPathResolvesRelative() {
        val a = ServerAddress.parse("https://example.com/frigate")!!
        assertEquals("https://example.com/frigate/api/stats", a.resolve("api/stats").toString())
        assertEquals("https://example.com/frigate/vod/x", a.resolve("/vod/x").toString())
    }

    @Test fun ownsOnlySameOrigin() {
        val a = ServerAddress.parse("https://f.example:8971")!!
        assertTrue(a.owns("https://f.example:8971/api/x".toHttpUrl()))
        assertFalse(a.owns("https://f.example/api/x".toHttpUrl()))
        assertFalse(a.owns("https://evil.example:8971/api/x".toHttpUrl()))
        assertFalse(a.owns("http://f.example:8971/api/x".toHttpUrl()))
    }

    @Test fun localNames() {
        assertTrue(ServerAddress.resolvesToLan("nvr.local"))
        assertTrue(ServerAddress.resolvesToLan("192.168.1.10"))
        assertTrue(ServerAddress.resolvesToLan("10.0.0.1"))
        assertFalse(ServerAddress.resolvesToLan("8.8.8.8"))
    }
}
