package io.github.revocx35.frigatemobile.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Automatic gain for camera sound. IP cameras' microphones record very quietly (measured on real Tapo and XMeye
 * cameras: -57 to -72 dB mean, peaks around -33 dB), which a phone speaker barely plays. This raises quiet sound by
 * up to [maxGain] towards [target] of full scale, and never above 1x for sound that is already loud.
 *
 * Peak envelope: instant attack, slow release ([releaseS]). The gain follows the envelope: down quickly
 * ([attackS], so a sudden loud sound doesn't clip for long; samples are clamped anyway), up slowly ([riseS], so
 * the background hiss doesn't pump between words). Interleaved 16-bit PCM, processed in place, state kept between
 * calls (one leveler per stream).
 */
class AudioLeveler(
    sampleRate: Int,
    private val channels: Int = 1,
    private val maxGain: Float = 20f,
    private val target: Float = 0.5f,
    attackS: Float = 0.005f,
    riseS: Float = 1.0f,
    releaseS: Float = 1.5f,
) {
    private val release = coef(sampleRate, releaseS)
    private val attack = coef(sampleRate, attackS)
    private val rise = coef(sampleRate, riseS)
    private var envelope = 0f
    var gain = 1f
        private set

    fun process(samples: ShortArray) {
        val ch = max(1, channels)
        var i = 0
        while (i < samples.size) {
            var peak = 0f
            for (c in 0 until min(ch, samples.size - i)) peak = max(peak, abs(samples[i + c].toFloat()) / FULL)
            envelope = if (peak > envelope) peak else envelope * release + peak * (1 - release)
            val wanted = (target / max(envelope, target / maxGain)).coerceIn(1f, maxGain)
            val k = if (wanted < gain) attack else rise
            gain = gain * k + wanted * (1 - k)
            for (c in 0 until min(ch, samples.size - i)) {
                samples[i + c] = (samples[i + c] * gain).roundToInt().coerceIn(-FULL.toInt(), FULL.toInt()).toShort()
            }
            i += ch
        }
    }

    private companion object {
        const val FULL = 32767f
        fun coef(rate: Int, seconds: Float) = exp(-1.0 / (max(1, rate) * seconds)).toFloat()
    }
}
