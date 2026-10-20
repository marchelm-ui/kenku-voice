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
}
