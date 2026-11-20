package io.github.revocx35.frigatemobile.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fixtures in src/test/resources/flac were made with ffmpeg (sine tones and white noise), with ffmpeg's own decode
 * as `.pcm` (s16le): the decoder must match it sample for sample.
 */
class FlacDecoderTest {
    private fun res(name: String) = javaClass.getResource("/flac/$name")!!.readBytes()

    /** The frames of a native .flac file: skip "fLaC" and the metadata blocks. */
    private fun frames(file: ByteArray): Pair<ByteArray, Int> {
        var p = 4
        var bps = 16
        while (true) {
            val last = file[p].toInt() and 0x80 != 0
            val len = ((file[p + 1].toInt() and 0xFF) shl 16) or ((file[p + 2].toInt() and 0xFF) shl 8) or (file[p + 3].toInt() and 0xFF)
            if (file[p].toInt() and 0x7F == 0) bps = FlacDecoder.streamInfoBitsPerSample(file.copyOfRange(p, p + 4 + len))!!
            p += 4 + len
            if (last) break
        }
        return file.copyOfRange(p, file.size) to bps
    }

    private fun pcm(name: String): ShortArray {
        val b = ByteBuffer.wrap(res(name)).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(b.remaining()).also { b.get(it) }
    }

    private fun decodeFile(name: String): Pair<ShortArray, FlacDecoder.Frame> {
        val (data, bps) = frames(res("$name.flac"))
        assertEquals(16, bps)
        val decoded = FlacDecoder(bps).decodeAll(data)
        val all = decoded.flatMap { it.samples.asList() }.toShortArray()
        return all to decoded.first()
    }

    @Test fun monoLpcMatchesFfmpeg() {
        val (samples, first) = decodeFile("mono8k")
        assertEquals(1, first.channels)
        assertEquals(8000, first.sampleRate)
        assertArrayEquals(pcm("mono8k.pcm"), samples)
    }

    @Test fun stereoDecorrelationMatchesFfmpeg() {
        val (samples, first) = decodeFile("stereo44k")
        assertEquals(2, first.channels)
        assertEquals(44100, first.sampleRate)
        assertArrayEquals(pcm("stereo44k.pcm"), samples)
    }

    @Test fun noiseMatchesFfmpeg() {
        val (samples, _) = decodeFile("noise8k")
        assertArrayEquals(pcm("noise8k.pcm"), samples)
    }

    /** go2rtc's PCMA -> FLAC frame: variable blocking, 16-bit block size, 8 kHz, mono 16-bit, one VERBATIM subframe. */
    @Test fun go2rtcVerbatimFrame() {
        val n = 1024
        val expected = ShortArray(n) { ((it * 37) % 2000 - 1000).toShort() }
        val frame = ByteBuffer.allocate(9 + n * 2 + 2).order(ByteOrder.BIG_ENDIAN).apply {
            put(0xFF.toByte()); put(0xF9.toByte()) // sync, variable blocking
            put(0x74) // block size: 16 bits at the end of the header; 8 kHz
            put(0x08) // mono, 16 bit
            put(0x00) // sample number 0 (UTF-8)
            putShort((n - 1).toShort())
            put(0x00) // CRC-8 (not checked)
            put(0x02) // subframe: VERBATIM, no wasted bits
            expected.forEach { putShort(it) }
            putShort(0) // CRC-16 (not checked)
        }.array()
        val decoded = FlacDecoder().decodeAll(frame + frame) // two frames in one buffer
        assertEquals(2, decoded.size)
        assertEquals(8000, decoded[0].sampleRate)
        assertArrayEquals(expected, decoded[1].samples)
    }

    @Test fun streamInfoFromMp4CodecData() {
        // Media3's codec data for MP4 FLAC: "fLaC" + the dfLa metadata blocks. This STREAMINFO is go2rtc's
        // (min/max block 32768, 8000 Hz, mono, 16 bit).
        val streamInfo = byteArrayOf(0x80.toByte(), 0, 0, 0x22) + hex("80008000000000000000" + "01f400f0" + "00".repeat(24))
        assertEquals(16, FlacDecoder.streamInfoBitsPerSample("fLaC".toByteArray() + streamInfo))
        assertNull(FlacDecoder.streamInfoBitsPerSample(byteArrayOf(1, 2, 3)))
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
