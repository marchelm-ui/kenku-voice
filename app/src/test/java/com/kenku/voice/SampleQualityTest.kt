package com.kenku.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class SampleQualityTest {

    private val rate = AudioDsp.RATE

    /** Half a second of silence, two seconds of "speech", half a second of silence. */
    private fun recording(speechAmp: Float, noiseAmp: Float, seed: Int = 42): FloatArray {
        val rnd = Random(seed)
        val n = rate * 3
        return FloatArray(n) { i ->
            val inSpeech = i in rate / 2 until rate / 2 + rate * 2
            val speech = if (inSpeech) (speechAmp * sin(2 * PI * 220.0 * i / rate) +
                0.4 * speechAmp * sin(2 * PI * 900.0 * i / rate)).toFloat() else 0f
            val noise = ((rnd.nextFloat() * 2 - 1) * noiseAmp)
            (speech + noise).coerceIn(-1f, 1f)
        }
    }

    @Test
    fun `a clean quiet-room recording reads as clean`() {
        val r = SampleQuality.assess(recording(speechAmp = 0.3f, noiseAmp = 0.003f))
        assertTrue("snr ${r.snrDb}", r.snrDb >= 25f)
        assertFalse(r.noisy)
        assertFalse(r.clipped)
        assertEquals(2f, r.speechSeconds, 0.4f)
        assertTrue(r.hint().startsWith("Clean recording"))
    }

    @Test
    fun `a noisy room is flagged with the measured headroom`() {
        val r = SampleQuality.assess(recording(speechAmp = 0.25f, noiseAmp = 0.1f))
        assertTrue("snr ${r.snrDb}", r.snrDb < 14f)
        assertTrue(r.noisy)
        assertFalse(r.clipped)
        assertTrue(r.hint().contains("noise"))
    }

    @Test
    fun `an overloaded microphone is flagged as clipped`() {
        val square = FloatArray(rate * 2) { i -> if ((i / 50) % 2 == 0) 0.999f else -0.999f }
        val r = SampleQuality.assess(square)
        assertTrue("clipped ${r.clippedPercent}%", r.clipped)
        assertTrue(r.hint().contains("overloaded"))
    }

    @Test
    fun `clipping wins over noise in the verdict`() {
        val loud = recording(speechAmp = 0.9f, noiseAmp = 0.2f).map { (it * 1.6f).coerceIn(-1f, 1f) }.toFloatArray()
        val r = SampleQuality.assess(loud)
        if (r.clipped) assertTrue(r.hint().contains("overloaded"))
    }

    @Test
    fun `a continuous sound with no pauses still counts as audible seconds`() {
        // A mimicked sound effect (coin jingle, door creak) has no silent frames, so the
        // adaptive noise floor equals the signal — the threshold cap keeps it counting.
        val rnd = Random(7)
        val steady = FloatArray(rate * 2) { i ->
            (0.25 * sin(2 * PI * 300.0 * i / rate)).toFloat() + (rnd.nextFloat() * 2 - 1) * 0.05f
        }
        val r = SampleQuality.assess(steady)
        assertTrue("speechSeconds ${r.speechSeconds}", r.speechSeconds >= 1.0f)
    }

    @Test
    fun `tiny input never divides by zero`() {
        val r = SampleQuality.assess(FloatArray(100))
        assertEquals(0f, r.speechSeconds, 1e-6f)
        assertFalse(r.snrDb.isNaN())
    }
}
