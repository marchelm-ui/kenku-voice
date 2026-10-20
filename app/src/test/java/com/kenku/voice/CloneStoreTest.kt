package com.kenku.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * CloneStore.prepare turns a raw recording into the reference audio Pocket TTS
 * imitates. Everything the cloned voice sounds like flows through here, so its
 * promises — trimmed, level-matched, capped, click-free — are pinned.
 */
class CloneStoreTest {

    private val rate = AudioDsp.RATE

    private fun speech(seconds: Int, amp: Float = 0.3f, leadSilence: Int = rate): FloatArray {
        val rnd = Random(11)
        val n = leadSilence + rate * seconds + leadSilence
        return FloatArray(n) { i ->
            if (i < leadSilence || i >= leadSilence + rate * seconds) 0f
            else (amp * sin(2 * PI * 180.0 * i / rate) + 0.3 * amp * sin(2 * PI * 700.0 * i / rate)).toFloat() *
                (0.7f + 0.3f * rnd.nextFloat())
        }
    }

    @Test
    fun `leading and trailing silence is trimmed away`() {
        val p = CloneStore.prepare(speech(5))
        // 2 s of silence around 5 s of speech: the result should be close to the speech alone.
        assertTrue("kept ${p.size / rate.toFloat()} s", p.size in (rate * 4.5f).toInt()..(rate * 5.6f).toInt())
    }

    @Test
    fun `a long recording is capped at 20 seconds`() {
        val p = CloneStore.prepare(speech(24, leadSilence = 0))
        assertTrue("kept ${p.size / rate.toFloat()} s", p.size <= rate * 20)
        assertTrue(p.size >= rate * 19)
    }

    @Test
    fun `output is level-matched for the cloning model`() {
        for (amp in listOf(0.08f, 0.3f)) {
            val p = CloneStore.prepare(speech(5, amp = amp))
            val v = AudioDsp.voicedRms(p)
            assertTrue("amp $amp gave voiced rms $v", abs(v - 0.09f) < 0.035f)
        }
        // A very loud take is attenuated, but only down to the 0.35x gain clamp —
        // dynamics are preserved over exact level.
        val loud = speech(5, amp = 0.8f)
        val v = AudioDsp.voicedRms(CloneStore.prepare(loud))
        assertTrue("loud take gave voiced rms $v", v < AudioDsp.voicedRms(loud) * 0.5f && v < 0.25f)
    }

    @Test
    fun `edges are faded so the hard 20-second cut cannot click`() {
        val p = CloneStore.prepare(speech(24, leadSilence = 0))
        assertEquals(0f, p.first(), 0.02f)
        assertEquals(0f, p.last(), 0.02f)
    }

    @Test
    fun `prepared audio never clips`() {
        val p = CloneStore.prepare(speech(5, amp = 0.95f))
        assertTrue(p.all { abs(it) <= 1f })
    }
}
