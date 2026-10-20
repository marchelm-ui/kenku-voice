package com.kenku.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class AudioDspTest {

    private fun tone(freq: Double, seconds: Double, amp: Float, rate: Int = AudioDsp.RATE) =
        FloatArray((rate * seconds).toInt()) { i -> (amp * sin(2 * PI * freq * i / rate)).toFloat() }

    private fun rms(x: FloatArray) = sqrt(x.fold(0.0) { a, v -> a + v * v } / maxOf(1, x.size)).toFloat()

    /** Signal power at one frequency (Goertzel), as amplitude. */
    private fun amplitudeAt(x: FloatArray, freq: Double, rate: Int = AudioDsp.RATE): Double {
        val w = 2 * PI * freq / rate
        val coeff = 2 * cos(w)
        var s0 = 0.0; var s1 = 0.0; var s2 = 0.0
        for (v in x) { s0 = v + coeff * s1 - s2; s2 = s1; s1 = s0 }
        val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
        return sqrt(maxOf(0.0, power)) * 2 / x.size
    }

    // ---------- WAV round trip ----------

    @Test
    fun `wav write and read round-trip`() {
        val src = tone(440.0, 0.25, 0.5f)
        val f = File.createTempFile("kenku", ".wav")
        try {
            AudioDsp.writeWav(src, f)
            val (back, rate) = AudioDsp.readWav(f)!!
            assertEquals(AudioDsp.RATE, rate)
            assertEquals(src.size, back.size)
            var worst = 0f
            for (i in src.indices) worst = maxOf(worst, abs(src[i] - back[i]))
            assertTrue("worst error $worst", worst < 2f / 32767f)
        } finally { f.delete() }
    }

    @Test
    fun `empty audio refuses to write`() {
        val f = File.createTempFile("kenku", ".wav")
        try {
            var threw = false
            try { AudioDsp.writeWav(FloatArray(0), f) } catch (e: IllegalStateException) { threw = true }
            assertTrue(threw)
        } finally { f.delete() }
    }

    @Test
    fun `garbage files read as null, not crash`() {
        val f = File.createTempFile("kenku", ".bin")
        try {
            f.writeBytes(ByteArray(300) { it.toByte() })
            assertNull(AudioDsp.readWav(f))
            assertNull(AudioDsp.readWav(File(f.parentFile, "missing-${System.nanoTime()}.wav")))
        } finally { f.delete() }
    }

    // ---------- pitch shifting ----------

    @Test
    fun `pitch shift changes length by the factor and keeps energy`() {
        val src = tone(1000.0, 0.5, 0.4f)
        val out = AudioDsp.pitchShift(src, 1.3f)
        assertEquals((src.size / 1.3f).toInt().toFloat(), out.size.toFloat(), 2f)
        // The 1 kHz tone lands at 1.3 kHz with its amplitude intact.
        assertEquals(0.4, amplitudeAt(out, 1300.0), 0.06)
        assertTrue(rms(out) > rms(src) * 0.8f)
        // Factor 1 is the identity.
        assertTrue(AudioDsp.pitchShift(src, 1f) === src)
    }

    @Test
    fun `pitch shift is band-limited - no aliasing from above Nyquist`() {
        // A 10 kHz tone pitched up by 1.3 would land at 13 kHz, past Nyquist (12 kHz).
        // A naive resampler folds it back as a loud alias; the band-limited one removes it.
        val src = tone(10000.0, 0.5, 0.5f)
        val out = AudioDsp.pitchShift(src, 1.3f)
        assertTrue("aliased energy leaked: rms ${rms(out)}", rms(out) < 0.1f * rms(src))
    }

    @Test
    fun `resample halves 48k to 24k`() {
        val src = tone(1000.0, 0.5, 0.4f, rate = 48000)
        val out = AudioDsp.resample(src, 48000)
        assertEquals(src.size / 2f, out.size.toFloat(), 2f)
        assertEquals(0.4, amplitudeAt(out, 1000.0), 0.06)
    }

    // ---------- trim ----------

    @Test
    fun `trim removes surrounding silence but keeps the sound and a breath of padding`() {
        val burst = tone(500.0, 0.1, 0.5f) // 2400 samples
        val src = FloatArray(2000) + burst + FloatArray(2000)
        val out = AudioDsp.trim(src)
        val pad = AudioDsp.RATE * 18 / 1000
        assertTrue("too short ${out.size}", out.size >= burst.size)
        assertTrue("kept too much ${out.size}", out.size <= burst.size + 2 * pad + 256)
    }

    @Test
    fun `trim of pure silence returns something sane`() {
        val out = AudioDsp.trim(FloatArray(4800))
        assertTrue(out.size <= 4800)
    }

    // ---------- loudness ----------

    private fun voicedRmsOf(x: FloatArray): Float {
        val frames = x.toList().chunked(480).map { c -> sqrt(c.fold(0.0) { a, v -> a + v * v } / c.size).toFloat() }.sorted()
        val top = frames.subList(frames.size / 3, frames.size)
        return top.average().toFloat()
    }

    @Test
    fun `matchLoudness hits the target level`() {
        val quiet = tone(800.0, 0.5, 0.1f)
        val out = AudioDsp.matchLoudness(quiet)
        assertEquals(0.085f, voicedRmsOf(out), 0.012f)
    }

    @Test
    fun `matchLoudness never boosts more than its clamp and leaves silence alone`() {
        val whisper = tone(800.0, 0.3, 0.004f)
        val out = AudioDsp.matchLoudness(whisper)
        assertTrue(voicedRmsOf(out) <= voicedRmsOf(whisper) * 3.6f)
        val silence = FloatArray(4800)
        assertTrue(AudioDsp.matchLoudness(silence).all { it == 0f })
    }

    @Test
    fun `polish output is bounded, finite, and near the table level`() {
        val voiceish = FloatArray(AudioDsp.RATE) { i ->
            (0.3 * sin(2 * PI * 180 * i / AudioDsp.RATE) + 0.15 * sin(2 * PI * 2800 * i / AudioDsp.RATE)).toFloat()
        }
        val out = AudioDsp.polish(voiceish)
        assertTrue(out.all { it.isFinite() && abs(it) <= 0.985f })
        assertEquals(0.105f, voicedRmsOf(out), 0.025f)
        // Silence in, silence out — the chain must not invent noise.
        assertTrue(AudioDsp.polish(FloatArray(4800)).all { abs(it) < 1e-4f })
    }

    // ---------- crossfades ----------

    @Test
    fun `crossfade curves are equal-power`() {
        val n = 128
        for (i in 0 until n) {
            val o = AudioDsp.fadeOutGain(i, n)
            val g = AudioDsp.fadeInGain(i, n)
            assertEquals(1f, o * o + g * g, 1e-3f)
        }
        assertEquals(1f, AudioDsp.fadeOutGain(0, 128), 1e-4f)
        assertEquals(0f, AudioDsp.fadeInGain(0, 128), 1e-4f)
    }

    @Test
    fun `fadeEdges starts and ends at zero-ish`() {
        val out = AudioDsp.fadeEdges(FloatArray(4800) { 0.5f })
        assertEquals(0f, out[0], 1e-4f)
        assertTrue(abs(out[out.size - 1]) < 0.02f)
        assertEquals(0.5f, out[2400], 1e-4f)
    }
}
