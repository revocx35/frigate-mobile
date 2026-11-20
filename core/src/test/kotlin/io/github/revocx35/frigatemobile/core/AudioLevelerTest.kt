package io.github.revocx35.frigatemobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

class AudioLevelerTest {
    private fun sine(seconds: Double, amplitude: Int, rate: Int = 8000) =
        ShortArray((seconds * rate).toInt()) { i -> (amplitude * sin(2 * PI * 440 * i / rate)).roundToInt().toShort() }

    private fun ShortArray.peak(from: Int = 0) = (from until size).maxOf { abs(this[it].toInt()) }

    /** A real camera's level (peaks around -33 dB, ~740/32767) ends up clearly audible, within the gain cap. */
    @Test fun quietCameraSoundIsRaised() {
        val l = AudioLeveler(8000)
        val s = sine(4.0, 740)
        // Fed in camera-sized FLAC frames (160 samples), like the renderer does.
        for (off in s.indices step 160) {
            val chunk = s.copyOfRange(off, minOf(s.size, off + 160)); l.process(chunk); chunk.copyInto(s, off)
        }
        val settled = s.peak(from = 3 * 8000)
        assertTrue("peak $settled", settled in 10_000..16_500) // ~0.5 of full scale, gain capped at 20x
        assertTrue(l.gain <= 20f)
    }

    @Test fun loudSoundIsLeftAlone() {
        val l = AudioLeveler(8000)
        val s = sine(2.0, 24_000)
        val before = s.copyOf()
        l.process(s)
        assertEquals(1f, l.gain, 0.01f)
        assertTrue((400 until s.size).all { abs(s[it] - before[it]) <= 1 }) // after the first 50 ms
    }

    /** A sudden loud sound after a quiet stretch: the gain drops within milliseconds and nothing wraps around. */
    @Test fun loudAfterQuietNeverOverflows() {
        val l = AudioLeveler(8000)
        l.process(sine(3.0, 300))
        assertTrue(l.gain > 10f)
        val loud = sine(1.0, 30_000)
        l.process(loud)
        assertTrue(loud.peak() <= 32767)
        assertTrue(loud.peak(from = 400) <= 32767 && l.gain < 1.2f)
        // No sign flips from overflow: samples keep the sign of the input sine.
        val ref = sine(1.0, 30_000)
        assertTrue(ref.indices.all { ref[it].toInt() == 0 || loud[it].toInt() == 0 || (ref[it] > 0) == (loud[it] > 0) })
    }

    @Test fun silenceStaysSilentAndStereoKeepsChannels() {
        val l = AudioLeveler(8000, channels = 2)
        val s = ShortArray(16_000)
        l.process(s)
        assertTrue(s.all { it.toInt() == 0 })
        val st = ShortArray(8000) { i -> if (i % 2 == 0) 500 else -500 }
        l.process(st)
        assertTrue(st.indices.all { (st[it] > 0) == (it % 2 == 0) })
    }
}
