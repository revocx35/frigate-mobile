package io.github.revocx35.frigatemobile.ui.cameras

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import io.github.revocx35.frigatemobile.core.AudioLeveler
import io.github.revocx35.frigatemobile.core.FlacDecoder
import java.nio.ByteOrder

/**
 * Plays FLAC audio with the app's own [FlacDecoder] instead of Android's MediaCodec. go2rtc delivers cameras' G.711
 * audio as 8 kHz verbatim FLAC with a STREAMINFO that doesn't match its frames, which `c2.android.flac.decoder`
 * rejects (live view went black with sound on). Put first, so it takes FLAC tracks; everything else falls through to
 * the default renderers.
 */
@OptIn(UnstableApi::class)
class LiveRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        out.add(FlacAudioRenderer(eventHandler, eventListener, audioSink))
        super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out)
    }
}

@OptIn(UnstableApi::class)
private class FlacAudioRenderer(handler: Handler?, listener: AudioRendererEventListener?, sink: AudioSink) :
    DecoderAudioRenderer<FlacPcmDecoder>(handler, listener, sink) {

    override fun getName() = "FlacAudioRenderer"

    override fun supportsFormatInternal(format: Format): Int = when {
        format.sampleMimeType != MimeTypes.AUDIO_FLAC -> C.FORMAT_UNSUPPORTED_TYPE
        format.channelCount !in 1..8 || format.sampleRate <= 0 -> C.FORMAT_UNSUPPORTED_SUBTYPE
        !sinkSupportsFormat(Util.getPcmFormat(C.ENCODING_PCM_16BIT, format.channelCount, format.sampleRate)) -> C.FORMAT_UNSUPPORTED_SUBTYPE
        else -> C.FORMAT_HANDLED
    }

    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?) = FlacPcmDecoder(format)

    override fun getOutputFormat(decoder: FlacPcmDecoder): Format =
        Util.getPcmFormat(C.ENCODING_PCM_16BIT, decoder.channels, decoder.sampleRate)
}

/** Media3 decoder wrapper: one input buffer (one or more FLAC frames) -> interleaved 16-bit little-endian PCM. */
@OptIn(UnstableApi::class)
private class FlacPcmDecoder(format: Format) :
    SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException>(
        // SimpleDecoder fills the arrays itself (createInputBuffer/createOutputBuffer), like Media3's extensions.
        @Suppress("UNCHECKED_CAST") (arrayOfNulls<DecoderInputBuffer>(BUFFERS) as Array<DecoderInputBuffer>),
        @Suppress("UNCHECKED_CAST") (arrayOfNulls<SimpleDecoderOutputBuffer>(BUFFERS) as Array<SimpleDecoderOutputBuffer>),
    ) {
    val channels = format.channelCount
    val sampleRate = format.sampleRate
    private val flac = FlacDecoder(FlacDecoder.streamInfoBitsPerSample(format.initializationData.firstOrNull()) ?: 16)
    private var leveler = AudioLeveler(sampleRate, channels)

    init {
        setInitialInputBufferSize(if (format.maxInputSize > 0) format.maxInputSize else 64 * 1024)
    }

    override fun getName() = "FlacPcmDecoder"

    override fun createInputBuffer() = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)

    override fun createOutputBuffer() = SimpleDecoderOutputBuffer { releaseOutputBuffer(it) }

    override fun createUnexpectedDecodeException(error: Throwable) = DecoderException("Unexpected FLAC decode error", error)

    override fun decode(inputBuffer: DecoderInputBuffer, outputBuffer: SimpleDecoderOutputBuffer, reset: Boolean): DecoderException? {
        val data = inputBuffer.data ?: return null
        val bytes = ByteArray(data.remaining())
        data.get(bytes)
        val frames = try {
            flac.decodeAll(bytes)
        } catch (e: FlacDecoder.FlacException) {
            return DecoderException(e.message ?: "FLAC decode error", e)
        }
        val size = frames.sumOf { it.samples.size } * 2
        val out = outputBuffer.init(inputBuffer.timeUs, size).order(ByteOrder.LITTLE_ENDIAN)
        if (reset) leveler = AudioLeveler(sampleRate, channels)
        for (f in frames) {
            if (f.channels != channels) return DecoderException("FLAC channel count changed")
            leveler.process(f.samples) // cameras record very quietly: raise it to a hearable level
            for (s in f.samples) out.putShort(s)
        }
        out.position(0)
        out.limit(size)
        return null
    }

    private companion object {
        const val BUFFERS = 16
    }
}
