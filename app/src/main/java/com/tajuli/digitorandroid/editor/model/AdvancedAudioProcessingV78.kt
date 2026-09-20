package com.tajuli.digitorandroid.editor.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Lightweight realtime voice styles. These are intentionally local DSP transforms rather than
 * cloud/ML pitch models, so they work in preview and export on-device.
 */
enum class VoiceStyleV78(val label: String) {
    NORMAL("Normal"),
    DEEP("Deep"),
    BRIGHT("Bright"),
    ROBOT("Robot"),
}

/**
 * Shared selected-clip audio DSP used by Media3 preview/export and the native fallback mixdown.
 *
 * Processing order:
 *  1. adaptive noise reduction
 *  2. stereo center/vocal focus
 *  3. rumble cleanup + 3-band EQ / voice-style tone shaping
 *  4. speech compressor/presence enhancement
 *  5. optional robot modulation
 *
 * This deliberately stays allocation-free per audio frame.
 */
class ClipAudioDspV78(
    private val mix: AudioMix,
    sampleRate: Int,
    channelCount: Int,
) {
    private val safeRate = sampleRate.coerceAtLeast(1)
    private val channels = channelCount.coerceAtLeast(1)
    private val noiseReducer = mix.noiseReduction
        .takeIf { it > 0f }
        ?.let(::AdaptiveNoiseReducer)

    private val lowState = FloatArray(channels)
    private val highLowPassState = FloatArray(channels)
    private val hpPreviousInput = FloatArray(channels)
    private val hpPreviousOutput = FloatArray(channels)

    private val lowAlpha = onePoleLowPassAlpha(220f)
    private val highAlpha = onePoleLowPassAlpha(3_800f)
    private val rumbleHighPassAlpha = highPassAlpha(82f)
    private var compressorEnvelope = 0f
    private var robotPhase = 0.0

    fun reset() {
        noiseReducer?.reset()
        lowState.fill(0f)
        highLowPassState.fill(0f)
        hpPreviousInput.fill(0f)
        hpPreviousOutput.fill(0f)
        compressorEnvelope = 0f
        robotPhase = 0.0
    }

    /** Processes the first configured channelCount samples of [frame] in place. */
    fun processFrame(frame: FloatArray) {
        val count = minOf(channels, frame.size)
        if (count <= 0) return

        if (noiseReducer != null) {
            var level = 0f
            for (channel in 0 until count) level = max(level, abs(frame[channel]))
            val gain = noiseReducer.processFrame(level, safeRate)
            for (channel in 0 until count) frame[channel] *= gain
        }

        // Most dialogue/lead vocals are center-panned. Reducing the stereo side signal is a
        // deterministic realtime "vocal focus" that improves intelligibility without pretending
        // to be full AI source separation.
        val vocalFocus = mix.vocalFocus.coerceIn(0f, 1f)
        if (vocalFocus > 0f && count >= 2) {
            val left = frame[0]
            val right = frame[1]
            val mid = (left + right) * .5f
            val side = (left - right) * .5f
            // 100% becomes an exact stereo-center extraction; lower values blend naturally.
            val sideGain = 1f - vocalFocus
            frame[0] = mid + side * sideGain
            frame[1] = mid - side * sideGain
        }

        val enhance = mix.voiceEnhance.coerceIn(0f, 1f)
        val style = mix.resolvedVoiceStyleV78
        val bassDb = mix.bassDb.coerceIn(-12f, 12f) - 1.5f * enhance + when (style) {
            VoiceStyleV78.DEEP -> 4f
            VoiceStyleV78.BRIGHT -> -2f
            else -> 0f
        }
        val midDb = mix.midDb.coerceIn(-12f, 12f) + 2.4f * enhance + when (style) {
            VoiceStyleV78.BRIGHT -> 1f
            else -> 0f
        }
        val trebleDb = mix.trebleDb.coerceIn(-12f, 12f) + 1.5f * enhance + when (style) {
            VoiceStyleV78.DEEP -> -5f
            VoiceStyleV78.BRIGHT -> 4f
            else -> 0f
        }
        val lowGain = dbToLinear(bassDb)
        val midGain = dbToLinear(midDb)
        val highGain = dbToLinear(trebleDb)

        var framePeak = 0f
        for (channel in 0 until count) {
            val dry = frame[channel].coerceIn(-1.5f, 1.5f)

            // Blend in a gentle ~82 Hz high-pass only when Voice Enhance is enabled.
            val hp = rumbleHighPassAlpha * (
                hpPreviousOutput[channel] + dry - hpPreviousInput[channel]
            )
            hpPreviousInput[channel] = dry
            hpPreviousOutput[channel] = hp
            val cleaned = dry * (1f - .72f * enhance) + hp * (.72f * enhance)

            lowState[channel] += lowAlpha * (cleaned - lowState[channel])
            highLowPassState[channel] += highAlpha * (cleaned - highLowPassState[channel])
            val low = lowState[channel]
            val high = cleaned - highLowPassState[channel]
            val mid = cleaned - low - high
            val shaped = low * lowGain + mid * midGain + high * highGain
            frame[channel] = shaped
            framePeak = max(framePeak, abs(shaped))
        }

        // Speech-oriented soft compression. Attack catches sudden peaks; slower release avoids
        // obvious pumping. The user amount crossfades between dry dynamics and the compressor.
        if (enhance > 0f) {
            val envelopeMs = if (framePeak > compressorEnvelope) 5f else 120f
            compressorEnvelope += (framePeak - compressorEnvelope) * smoothingAlpha(envelopeMs)
            val threshold = .24f
            val compressedGain = if (compressorEnvelope <= threshold || compressorEnvelope <= 1e-6f) {
                1f
            } else {
                val compressedLevel = threshold + (compressorEnvelope - threshold) / 3.2f
                (compressedLevel / compressorEnvelope).coerceIn(.25f, 1f)
            }
            val gain = (1f + (compressedGain - 1f) * enhance) * (1f + .12f * enhance)
            for (channel in 0 until count) frame[channel] *= gain
        }

        if (style == VoiceStyleV78.ROBOT) {
            val carrier = sin(robotPhase).toFloat()
            val wet = .70f
            for (channel in 0 until count) {
                val dry = frame[channel]
                frame[channel] = dry * (1f - wet) + dry * carrier * wet
            }
            robotPhase += 2.0 * PI * 72.0 / safeRate.toDouble()
            if (robotPhase > 2.0 * PI) robotPhase -= 2.0 * PI
        }

        for (channel in 0 until count) {
            frame[channel] = softLimit(frame[channel])
        }
    }

    private fun onePoleLowPassAlpha(cutoffHz: Float): Float =
        (1f - exp((-2.0 * PI * cutoffHz.toDouble() / safeRate.toDouble())).toFloat())
            .coerceIn(.00001f, 1f)

    private fun highPassAlpha(cutoffHz: Float): Float {
        val dt = 1.0 / safeRate.toDouble()
        val rc = 1.0 / (2.0 * PI * cutoffHz.toDouble())
        return (rc / (rc + dt)).toFloat().coerceIn(0f, 1f)
    }

    private fun smoothingAlpha(timeMs: Float): Float {
        val samples = (timeMs * safeRate.toFloat() / 1000f).coerceAtLeast(1f)
        return (1f - exp((-1f / samples).toDouble()).toFloat()).coerceIn(0f, 1f)
    }

    private fun dbToLinear(db: Float): Float =
        10.0.pow(db.coerceIn(-24f, 24f).toDouble() / 20.0).toFloat()

    private fun softLimit(value: Float): Float {
        if (value in -1f..1f) return value
        return tanh(value.toDouble()).toFloat().coerceIn(-1f, 1f)
    }
}
