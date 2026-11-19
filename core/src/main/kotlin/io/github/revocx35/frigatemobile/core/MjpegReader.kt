package io.github.revocx35.frigatemobile.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Splits Frigate's MJPEG feed (`multipart/x-mixed-replace`, parts without Content-Length) into JPEG frames by
 * scanning for the JPEG start (FF D8) and end (FF D9) markers. Bytes between frames (part headers) are skipped.
 * A frame larger than [maxFrameBytes] is dropped instead of growing memory without bound.
 */
class MjpegReader(input: InputStream, private val maxFrameBytes: Int = 8 * 1024 * 1024) {
    private val stream = input.buffered(64 * 1024)
    private val frame = ByteArrayOutputStream(256 * 1024)

    /** The next complete JPEG, or null at the end of the stream. */
    @Throws(IOException::class)
    fun next(): ByteArray? {
        // Find SOI.
        var prev = -1
        while (true) {
            val b = stream.read()
            if (b < 0) return null
            if (prev == 0xFF && b == 0xD8) break
            prev = b
        }
        frame.reset()
        frame.write(0xFF); frame.write(0xD8)
        prev = -1
        while (true) {
            val b = stream.read()
            if (b < 0) return null
            frame.write(b)
            if (prev == 0xFF && b == 0xD9) return frame.toByteArray()
            prev = b
            if (frame.size() > maxFrameBytes) {
                frame.reset()
                return next()
            }
        }
    }
}
