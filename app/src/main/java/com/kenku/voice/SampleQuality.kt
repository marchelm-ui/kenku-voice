package com.kenku.voice

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Judges a clone recording before it is saved: how much real speech it holds, how far
 * the voice sits above the room noise, and whether the microphone overloaded. A noisy
 * or clipped sample makes every later cloned line buzz, so the user is warned while
 * re-recording still costs nothing.
 */
object SampleQuality {

    data class Report(val speechSeconds: Float, val snrDb: Float, val clippedPercent: Float) {
        val clipped get() = clippedPercent > 1f
        val noisy get() = snrDb < 14f

        /** One friendly line for the recording panel. */
        fun hint(): String = when {
            clipped -> "The microphone overloaded (%.0f%% of the sound is distorted). Hold the phone a little farther away and try again.".format(clippedPercent)
            noisy -> "Background noise is high (voice is only %.0f dB above the room). It will work, but a quieter room clones much better.".format(snrDb)
            else -> "Clean recording — the voice sits %.0f dB above the room noise.".format(snrDb)
        }
    }

    private const val WIN = 480 // 20 ms at 24 kHz

    fun assess(x: FloatArray): Report {
        if (x.size < WIN * 4) return Report(0f, 0f, 0f)
        val frames = ArrayList<Float>(x.size / WIN + 1)
        var clippedSamples = 0
        var i = 0
        while (i + WIN <= x.size) {
            var sum = 0.0
            for (k in i until i + WIN) {
                val v = x[k]
                sum += v * v
                if (abs(v) > 0.985f) clippedSamples++
            }
            frames.add(sqrt(sum / WIN).toFloat())
            i += WIN
        }
        val sorted = frames.sorted()
        // Room noise: the quietest tenth of the recording (the pauses between words).
        val floorFrames = sorted.subList(0, maxOf(1, sorted.size / 10))
        val noise = maxOf(1e-4f, floorFrames.average().toFloat())
        // Voice level: the loudest third, same convention as the loudness matcher.
        val speech = sorted.subList(sorted.size * 2 / 3, sorted.size).average().toFloat()
        val snr = (20 * log10((speech / noise).toDouble())).toFloat().coerceIn(0f, 60f)
        val speechThreshold = maxOf(noise * 2.5f, 0.02f)
        val speechSeconds = frames.count { it > speechThreshold } * WIN / AudioDsp.RATE.toFloat()
        val clippedPercent = 100f * clippedSamples / x.size
        return Report(speechSeconds, snr, clippedPercent)
    }
}
