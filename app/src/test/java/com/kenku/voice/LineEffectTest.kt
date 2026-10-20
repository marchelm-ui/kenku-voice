package com.kenku.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class LineEffectTest {

    private fun amplitudeAt(x: FloatArray, freq: Double): Double {
        val w = 2 * PI * freq / AudioDsp.RATE
        val coeff = 2 * cos(w)
        var s1 = 0.0; var s2 = 0.0
        for (v in x) { val s0 = v + coeff * s1 - s2; s2 = s1; s1 = s0 }
        return sqrt(maxOf(0.0, s1 * s1 + s2 * s2 - coeff * s1 * s2)) * 2 / x.size
    }

    @Test
    fun `none is a true bypass`() {
        val fx = LineEffect("none")
        val src = FloatArray(2400) { Random(7).nextFloat() - 0.5f }
        assertTrue(!fx.active)
        assertArrayEquals(src, fx.process(src), 0f)
        assertEquals(0, fx.tail().size)
    }

    @Test
    fun `distant muffles the highs much more than the lows`() {
        val fx = LineEffect("distant")
        val low = tone(300.0)
        val high = tone(6000.0)
        val lowKept = amplitudeAt(fx.process(low), 300.0)
        val highKept = amplitudeAt(LineEffect("distant").process(high), 6000.0)
        assertTrue("low $lowKept high $highKept", highKept < lowKept * 0.4)
    }

    @Test
    fun `cave rings on after the sound stops`() {
        val fx = LineEffect("cave")
        val impulse = FloatArray(AudioDsp.RATE / 2)
        impulse[0] = 0.8f
        val out = fx.process(impulse)
        assertTrue(out.all { it.isFinite() && abs(it) <= 0.985f })
        val tail = fx.tail()
        assertTrue(tail.isNotEmpty())
        assertTrue("echo tail is silent", tail.any { abs(it) > 1e-4f })
        // The echo decays rather than growing.
        val first = tail.take(tail.size / 3).maxOf { abs(it) }
        val lastPart = tail.takeLast(tail.size / 3).maxOf { abs(it) }
        assertTrue("tail grows: $first -> $lastPart", lastPart <= first + 0.05f)
    }

    @Test
    fun `memory stays bounded and finite`() {
        val fx = LineEffect("memory")
        val out = fx.process(tone(700.0))
        assertTrue(out.all { it.isFinite() && abs(it) <= 0.985f })
    }

    @Test
    fun `effects process in pieces without seams blowing up`() {
        // The Performer streams audio in pieces; state must carry across calls.
        val fx = LineEffect("cave")
        val whole = tone(500.0, seconds = 1.0)
        var peak = 0f
        whole.toList().chunked(1111).forEach { piece ->
            fx.process(piece.toFloatArray()).forEach { peak = maxOf(peak, abs(it)) }
        }
        assertTrue(peak <= 0.985f)
    }

    private fun tone(freq: Double, seconds: Double = 0.5, amp: Float = 0.4f) =
        FloatArray((AudioDsp.RATE * seconds).toInt()) { i -> (amp * sin(2 * PI * freq * i / AudioDsp.RATE)).toFloat() }

    private fun rms(x: FloatArray) = sqrt(x.sumOf { (it * it).toDouble() } / maxOf(1, x.size))

    private val newKinds = listOf("glitch", "phantom", "radio", "underwater", "clockwork")

    @Test
    fun `every new texture is active, bounded, finite and audibly present`() {
        for (kind in newKinds) {
            val fx = LineEffect(kind)
            assertTrue("$kind not active", fx.active)
            val src = tone(440.0, seconds = 2.0)
            val out = fx.process(src)
            assertTrue("$kind unbounded", out.all { it.isFinite() && abs(it) <= 0.985f })
            val kept = rms(out) / rms(src)
            assertTrue("$kind energy ratio $kept", kept in 0.25..2.5)
        }
    }

    @Test
    fun `new textures survive streamed piece-by-piece processing`() {
        for (kind in newKinds) {
            val fx = LineEffect(kind)
            val whole = tone(500.0, seconds = 1.5)
            val streamed = ArrayList<Float>()
            whole.toList().chunked(997).forEach { piece -> fx.process(piece.toFloatArray()).forEach { streamed.add(it) } }
            assertEquals(whole.size, streamed.size)
            assertTrue("$kind blew up mid-stream", streamed.all { it.isFinite() && abs(it) <= 0.985f })
            assertTrue("$kind went silent mid-stream", rms(streamed.toFloatArray()) > 0.02)
        }
    }

    @Test
    fun `underwater muffles highs, clockwork reshapes the tone, radio narrows the band`() {
        val highKept = amplitudeAt(LineEffect("underwater").process(tone(5000.0)), 5000.0)
        val lowKept = amplitudeAt(LineEffect("underwater").process(tone(200.0)), 200.0)
        assertTrue("underwater high $highKept low $lowKept", highKept < lowKept * 0.3)
        // Ring modulation moves energy off the input frequency into sidebands.
        val ringAt = amplitudeAt(LineEffect("clockwork").process(tone(800.0)), 800.0)
        assertTrue("clockwork left the tone intact ($ringAt)", ringAt < 0.4 * 0.75)
        val subKept = amplitudeAt(LineEffect("radio").process(tone(150.0)), 150.0)
        assertTrue("radio kept the rumble ($subKept)", subKept < 0.12)
    }

    @Test
    fun `glitch is deterministic per instance and keeps most of the audio`() {
        val a = LineEffect("glitch").process(tone(600.0, seconds = 3.0))
        val b = LineEffect("glitch").process(tone(600.0, seconds = 3.0))
        assertArrayEquals(a, b, 0f)
        assertTrue("glitch ate the line: ${rms(a)}", rms(a) > rms(tone(600.0, seconds = 3.0)) * 0.5)
    }

    @Test
    fun `only the echo textures ring on after the line`() {
        for (kind in newKinds) assertEquals("$kind should end clean", 0, LineEffect(kind).tail().size)
        assertTrue(LineEffect("cave").tail().isNotEmpty())
    }
}
