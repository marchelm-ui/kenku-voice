package com.kenku.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class StitcherTest {

    private val xf = AudioDsp.RATE * 40 / 1000 // the app's "smooth" crossfade: 960 samples

    private fun assemble(crossfade: Int = xf, block: (Stitcher) -> Unit): FloatArray {
        val pieces = ArrayList<FloatArray>()
        val st = Stitcher(crossfade) { if (it.isNotEmpty()) pieces.add(it) }
        block(st)
        val all = FloatArray(pieces.sumOf { it.size })
        var o = 0
        for (a in pieces) { a.copyInto(all, o); o += a.size }
        return all
    }

    private fun sine(n: Int, freq: Double = 400.0, amp: Float = 0.5f, phase: Double = 0.0) =
        FloatArray(n) { i -> (amp * sin(phase + 2 * PI * freq * i / AudioDsp.RATE)).toFloat() }

    @Test
    fun `crossfaded join overlaps by exactly the crossfade length`() {
        val out = assemble { st ->
            st.addSpeech(sine(4800), 0)
            st.addSpeech(sine(4800, phase = 1.0), 0)
            st.finish()
        }
        assertEquals(4800 + 4800 - xf, out.size)
    }

    @Test
    fun `punctuation gap closes the old voice, breathes, then starts fresh`() {
        val gapMs = 260
        val out = assemble { st ->
            st.addSpeech(sine(4800), 0)
            st.addSpeech(sine(4800, phase = 2.0), gapMs)
            st.finish()
        }
        val gap = AudioDsp.RATE * gapMs / 1000
        assertEquals(4800 + gap + 4800, out.size)
        // The old voice is fully faded by the end of its region…
        assertTrue(abs(out[4799]) < 0.01f)
        // …and the pause is true silence.
        for (i in 4800 until 4800 + gap) assertEquals(0f, out[i], 1e-6f)
    }

    @Test
    fun `no clicks - the whole assembled line stays sample-continuous`() {
        // Worst case: joining anti-phase tones. A hard cut would step by ~1.0; a clean
        // crossfade keeps every sample-to-sample step within a sine's natural slope.
        val out = assemble { st ->
            st.addSpeech(sine(4800), 0)
            st.addSpeech(sine(4000, phase = PI), 0)
            st.addSpeech(sine(3000, phase = PI / 2), 0)
            st.finish()
        }
        var maxStep = 0f
        for (i in 1 until out.size) maxStep = maxOf(maxStep, abs(out[i] - out[i - 1]))
        val naturalSlope = (0.5 * 2 * PI * 400.0 / AudioDsp.RATE).toFloat()
        assertTrue("click detected: step $maxStep vs slope $naturalSlope", maxStep < naturalSlope * 2.2f)
    }

    @Test
    fun `sound splice pads both sides with silence and resets the voice`() {
        val fxLen = 2400
        val out = assemble { st ->
            st.addSpeech(sine(4800), 0)
            st.addSound(FloatArray(fxLen) { 0.5f })
            st.addSpeech(sine(4800, phase = 1.0), 0)
            st.finish()
        }
        val pre = AudioDsp.RATE * 60 / 1000
        val post = AudioDsp.RATE * 80 / 1000
        assertEquals(4800 + pre + fxLen + post + 4800, out.size)
        for (i in 4800 until 4800 + pre) assertEquals(0f, out[i], 1e-6f)
        for (i in 4800 + pre + fxLen until 4800 + pre + fxLen + post) assertEquals(0f, out[i], 1e-6f)
        // The spliced sound itself is click-free at its edges.
        assertTrue(abs(out[4800 + pre]) < 0.02f)
        assertTrue(abs(out[4800 + pre + fxLen - 1]) < 0.02f)
    }

    @Test
    fun `finish fades the last voice to silence`() {
        val out = assemble { st ->
            st.addSpeech(sine(4800), 0)
            st.finish()
        }
        assertEquals(4800, out.size)
        assertTrue(abs(out[out.size - 1]) < 0.01f)
    }

    @Test
    fun `tiny and empty snippets never break the line`() {
        val out = assemble { st ->
            st.addSpeech(FloatArray(0), 0)
            st.addSpeech(sine(10), 0)
            st.addSpeech(sine(1), 110)
            st.addSpeech(sine(500, phase = 1.0), 0)
            st.addSound(FloatArray(5) { 0.3f })
            st.addSpeech(sine(4800), 0)
            st.finish()
        }
        assertTrue(out.isNotEmpty())
        assertTrue(out.all { it.isFinite() && abs(it) <= 1f })
    }

    @Test
    fun `nothing is lost - energy of the line survives assembly`() {
        val a = sine(4800); val b = sine(4800, phase = 0.3)
        val out = assemble { st -> st.addSpeech(a, 0); st.addSpeech(b, 0); st.finish() }
        fun energy(x: FloatArray) = x.fold(0.0) { acc, v -> acc + v * v }
        // Overlap and edge fades cost a little; the bulk of both snippets must survive.
        assertTrue(energy(out) > (energy(a) + energy(b)) * 0.8)
    }
}
