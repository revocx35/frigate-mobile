package io.github.revocx35.frigatemobile.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class MjpegReaderTest {
    private fun jpeg(vararg body: Int) = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + body.map { it.toByte() }.toByteArray() + byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    @Test fun splitsMultipartFrames() {
        val a = jpeg(1, 2, 0xFF, 0x00, 3)
        val b = jpeg(9, 8, 7)
        val out = ByteArrayOutputStream()
        for (f in listOf(a, b)) {
            out.write("--frame\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
            out.write(f)
            out.write("\r\n".toByteArray())
        }
        val r = MjpegReader(out.toByteArray().inputStream())
        assertArrayEquals(a, r.next())
        assertArrayEquals(b, r.next())
        assertNull(r.next())
    }

    @Test fun dropsOversizedFrame() {
        val big = jpeg(*IntArray(100) { 1 })
        val small = jpeg(5)
        val r = MjpegReader((big + small).inputStream(), maxFrameBytes = 50)
        assertArrayEquals(small, r.next())
    }

    @Test fun truncatedStreamEnds() {
        val r = MjpegReader(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2).inputStream())
        assertNull(r.next())
    }
}
