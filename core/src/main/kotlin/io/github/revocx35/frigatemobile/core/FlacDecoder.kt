package io.github.revocx35.frigatemobile.core

/**
 * A small FLAC frame decoder (all subframe types: constant, verbatim, fixed, LPC; stereo decorrelation; wasted
 * bits), producing interleaved 16-bit PCM.
 *
 * Why it exists: go2rtc sends cameras' G.711 audio to MSE clients as 8 kHz FLAC with verbatim subframes and a
 * STREAMINFO that claims 32768-sample blocks while the frames carry 1024. Android's `c2.android.flac.decoder`
 * rejects that stream, so the app decodes it itself (see the app's FlacAudioRenderer).
 */
class FlacDecoder(
    /** Bits per sample from STREAMINFO, used when a frame header says "see STREAMINFO". */
    private val streamBitsPerSample: Int = 16,
) {
    class FlacException(message: String) : Exception(message)

    /** One decoded frame: interleaved samples, scaled to 16 bits. */
    class Frame(val samples: ShortArray, val channels: Int, val sampleRate: Int, val blockSize: Int)

    /** Decodes every frame in `data[offset, offset + length)`. */
    fun decodeAll(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<Frame> {
        val r = BitReader(data, offset, offset + length)
        val frames = ArrayList<Frame>(1)
        while (r.bytesLeft() >= 6) {
            frames += decodeFrame(r)
        }
        return frames
    }

    private fun decodeFrame(r: BitReader): Frame {
        val sync = r.bits(15)
        if (sync != 0x7FFC) throw FlacException("FLAC frame sync not found")
        r.bits(1) // blocking strategy (fixed/variable): the frame/sample number below isn't needed
        val bsCode = r.bits(4)
        val srCode = r.bits(4)
        val chCode = r.bits(4)
        val ssCode = r.bits(3)
        r.bits(1)
        readUtf8Number(r)
        val blockSize = when (bsCode) {
            1 -> 192
            in 2..5 -> 576 shl (bsCode - 2)
            6 -> r.bits(8) + 1
            7 -> r.bits(16) + 1
            in 8..15 -> 256 shl (bsCode - 8)
            else -> throw FlacException("Reserved FLAC block size")
        }
        val sampleRate = when (srCode) {
            0 -> 0
            1 -> 88200; 2 -> 176400; 3 -> 192000; 4 -> 8000; 5 -> 16000; 6 -> 22050; 7 -> 24000
            8 -> 32000; 9 -> 44100; 10 -> 48000; 11 -> 96000
            12 -> r.bits(8) * 1000
            13 -> r.bits(16)
            14 -> r.bits(16) * 10
            else -> throw FlacException("Invalid FLAC sample rate")
        }
        val bps = when (ssCode) {
            0 -> streamBitsPerSample
            1 -> 8; 2 -> 12; 4 -> 16; 5 -> 20; 6 -> 24; 7 -> 32
            else -> throw FlacException("Reserved FLAC sample size")
        }
        r.bits(8) // CRC-8 of the header (the transport is TLS; not checked)

        val channels = if (chCode < 8) chCode + 1 else if (chCode <= 10) 2 else throw FlacException("Reserved FLAC channel assignment")
        val data = Array(channels) { ch ->
            // The side channel carries one extra bit.
            val extra = when (chCode) { 8 -> if (ch == 1) 1 else 0; 9 -> if (ch == 0) 1 else 0; 10 -> if (ch == 1) 1 else 0; else -> 0 }
            decodeSubframe(r, blockSize, bps + extra)
        }
        when (chCode) {
            8 -> for (i in 0 until blockSize) data[1][i] = data[0][i] - data[1][i] // left/side
            9 -> for (i in 0 until blockSize) data[0][i] = data[0][i] + data[1][i] // side/right
            10 -> for (i in 0 until blockSize) { // mid/side
                val side = data[1][i]
                val mid = (data[0][i] shl 1) or (side and 1)
                data[0][i] = (mid + side) shr 1
                data[1][i] = (mid - side) shr 1
            }
        }
        r.alignToByte()
        r.bits(16) // CRC-16 of the frame

        val out = ShortArray(blockSize * channels)
        val shift = bps - 16
        for (i in 0 until blockSize) for (ch in 0 until channels) {
            val v = data[ch][i]
            out[i * channels + ch] = (if (shift >= 0) v shr shift else v shl -shift).coerceIn(-32768, 32767).toShort()
        }
        return Frame(out, channels, sampleRate, blockSize)
    }

    private fun readUtf8Number(r: BitReader) {
        val first = r.bits(8)
        val extra = when {
            first and 0x80 == 0 -> 0
            first and 0xE0 == 0xC0 -> 1
            first and 0xF0 == 0xE0 -> 2
            first and 0xF8 == 0xF0 -> 3
            first and 0xFC == 0xF8 -> 4
            first and 0xFE == 0xFC -> 5
            first == 0xFE -> 6
            else -> throw FlacException("Invalid FLAC frame number")
        }
        repeat(extra) { r.bits(8) }
    }

    private fun decodeSubframe(r: BitReader, blockSize: Int, bitsIn: Int): IntArray {
        if (r.bits(1) != 0) throw FlacException("FLAC subframe padding bit set")
        val type = r.bits(6)
        var wasted = 0
        if (r.bits(1) == 1) wasted = r.unary() + 1
        val bps = bitsIn - wasted
        val out = IntArray(blockSize)
        when {
            type == 0 -> out.fill(r.signed(bps))
            type == 1 -> for (i in 0 until blockSize) out[i] = r.signed(bps)
            type in 8..12 -> {
                val order = type - 8
                for (i in 0 until order) out[i] = r.signed(bps)
                residual(r, out, blockSize, order)
                fixedPredict(out, order)
            }
            type in 32..63 -> {
                val order = type - 31
                for (i in 0 until order) out[i] = r.signed(bps)
                val precision = r.bits(4) + 1
                if (precision == 16) throw FlacException("Invalid FLAC LPC precision")
                val shift = r.signed(5)
                val coefs = IntArray(order) { r.signed(precision) }
                residual(r, out, blockSize, order)
                for (i in order until blockSize) {
                    var sum = 0L
                    for (j in 0 until order) sum += coefs[j].toLong() * out[i - j - 1]
                    out[i] += (sum shr shift).toInt()
                }
            }
            else -> throw FlacException("Reserved FLAC subframe type $type")
        }
        if (wasted > 0) for (i in 0 until blockSize) out[i] = out[i] shl wasted
        return out
    }

    private fun fixedPredict(s: IntArray, order: Int) {
        for (i in order until s.size) {
            s[i] += when (order) {
                0 -> 0
                1 -> s[i - 1]
                2 -> 2 * s[i - 1] - s[i - 2]
                3 -> 3 * s[i - 1] - 3 * s[i - 2] + s[i - 3]
                else -> 4 * s[i - 1] - 6 * s[i - 2] + 4 * s[i - 3] - s[i - 4]
            }
        }
    }

    /** Reads the Rice-coded residual into `out[order until blockSize]`. */
    private fun residual(r: BitReader, out: IntArray, blockSize: Int, order: Int) {
        val method = r.bits(2)
        if (method > 1) throw FlacException("Reserved FLAC residual coding")
        val paramBits = if (method == 0) 4 else 5
        val escape = if (method == 0) 15 else 31
        val partitionOrder = r.bits(4)
        val partitions = 1 shl partitionOrder
        var i = order
        for (p in 0 until partitions) {
            val count = (blockSize shr partitionOrder) - if (p == 0) order else 0
            val k = r.bits(paramBits)
            if (k == escape) {
                val n = r.bits(5)
                repeat(count) { out[i++] = if (n == 0) 0 else r.signed(n) }
            } else {
                repeat(count) {
                    val q = r.unary()
                    val v = (q.toLong() shl k) or r.bits(k).toLong()
                    out[i++] = ((v ushr 1) xor -(v and 1)).toInt()
                }
            }
        }
    }

    private class BitReader(private val d: ByteArray, private var pos: Int, private val end: Int) {
        private var bit = 0 // bits already used of d[pos]

        fun bytesLeft() = end - pos

        fun bits(n: Int): Int {
            if (n == 0) return 0
            var v = 0L
            var left = n
            while (left > 0) {
                if (pos >= end) throw FlacException("FLAC frame truncated")
                val avail = 8 - bit
                val take = minOf(avail, left)
                val b = (d[pos].toInt() and 0xFF) shr (avail - take) and ((1 shl take) - 1)
                v = (v shl take) or b.toLong()
                left -= take
                bit += take
                if (bit == 8) { bit = 0; pos++ }
            }
            return v.toInt()
        }

        fun signed(n: Int): Int {
            if (n == 0) return 0
            val v = bits(n)
            return if (n == 32) v else (v shl (32 - n)) shr (32 - n)
        }

        /** Number of 0 bits before the next 1. */
        fun unary(): Int {
            var n = 0
            while (bits(1) == 0) n++
            return n
        }

        fun alignToByte() {
            if (bit != 0) { bit = 0; pos++ }
        }
    }

    companion object {
        /**
         * Bits per sample from FLAC codec data: `fLaC` + metadata blocks (what Media3 makes of an MP4 `dfLa` box), or
         * the bare metadata blocks. Null when there's no STREAMINFO.
         */
        fun streamInfoBitsPerSample(init: ByteArray?): Int? {
            if (init == null) return null
            var p = if (init.size >= 4 && init[0] == 'f'.code.toByte() && init[1] == 'L'.code.toByte()) 4 else 0
            if (init.size < p + 4 + 34) return null
            if (init[p].toInt() and 0x7F != 0) return null // first block must be STREAMINFO
            p += 4
            // STREAMINFO: min/max block (2+2), min/max frame (3+3), then 20 bits rate, 3 channels-1, 5 bps-1.
            val b12 = init[p + 12].toInt() and 0xFF
            val b13 = init[p + 13].toInt() and 0xFF
            return (((b12 and 0x01) shl 4) or (b13 shr 4)) + 1
        }
    }
}
