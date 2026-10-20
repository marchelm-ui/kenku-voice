package com.kenku.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * The clamps in StyleTuning are what fixed the app's one field-reported fault:
 * voices pitched or sped past understanding. These tests make that fix permanent —
 * no style, jitter roll, or future catalog addition can tune outside the bounds.
 */
class StyleTuningTest {

    private fun everyCase(block: (Style, Boolean, Boolean, StyleTuning.Tuning) -> Unit) {
        for (style in Catalog.styles) for (smooth in listOf(true, false)) for (wild in listOf(true, false))
            for (seed in 0..49) block(style, smooth, wild, StyleTuning.tune(style, smooth, wild, Random(seed)))
    }

    @Test
    fun `clear mode can never leave the intelligible range`() {
        everyCase { style, _, wild, t ->
            if (!wild) {
                assertTrue("${style.key} pitch ${t.pitch}", t.pitch in StyleTuning.CLEAR_PITCH_MIN..StyleTuning.CLEAR_PITCH_MAX)
                assertTrue("${style.key} rate ${t.rate}", t.rate in StyleTuning.CLEAR_RATE_MIN..StyleTuning.CLEAR_RATE_MAX)
            }
        }
    }

    @Test
    fun `wild mode is exaggerated but still bounded`() {
        everyCase { style, _, wild, t ->
            if (wild) {
                assertTrue("${style.key} pitch ${t.pitch}", t.pitch in StyleTuning.WILD_PITCH_MIN..StyleTuning.WILD_PITCH_MAX)
                assertTrue("${style.key} rate ${t.rate}", t.rate in StyleTuning.WILD_RATE_MIN..StyleTuning.WILD_RATE_MAX)
            }
        }
    }

    @Test
    fun `the model is never asked for a speed outside its sweet spot`() {
        everyCase { style, _, _, t ->
            assertTrue("${style.key} speed ${t.speed}", t.speed in StyleTuning.SPEED_MIN..StyleTuning.SPEED_MAX)
        }
    }

    @Test
    fun `every catalog style already sits inside the clear clamps`() {
        // If a style's base values needed clamping, the style would not sound as designed.
        for (s in Catalog.styles) {
            assertTrue("${s.key} pitch ${s.pitch}", s.pitch in StyleTuning.CLEAR_PITCH_MIN..StyleTuning.CLEAR_PITCH_MAX)
            assertTrue("${s.key} rate ${s.rate}", s.rate in StyleTuning.CLEAR_RATE_MIN..StyleTuning.CLEAR_RATE_MAX)
            assertTrue("${s.key} wildPitch ${s.wildPitch}", s.wildPitch in StyleTuning.WILD_PITCH_MIN..StyleTuning.WILD_PITCH_MAX)
            assertTrue("${s.key} wildRate ${s.wildRate}", s.wildRate in StyleTuning.WILD_RATE_MIN..StyleTuning.WILD_RATE_MAX)
        }
    }

    @Test
    fun `natural style stays within jitter of the voice's own sound`() {
        for (seed in 0..49) {
            val t = StyleTuning.tune(Catalog.styles.first { it.key == "natural" }, smooth = true, wild = false, rnd = Random(seed))
            assertTrue("pitch drifted: ${t.pitch}", abs(t.pitch - 1f) <= 0.016f)
            assertEquals(1f, t.rate, 1e-6f)
        }
    }

    @Test
    fun `smooth mode jitters less than lively mode`() {
        val style = Catalog.styles.first { it.key == "bright" }
        fun spread(smooth: Boolean): Float {
            val ps = (0..199).map { StyleTuning.tune(style, smooth, wild = false, rnd = Random(it)).pitch }
            return ps.max() - ps.min()
        }
        assertTrue(spread(true) < spread(false))
    }

    @Test
    fun `tuning is deterministic for a given seed`() {
        val style = Catalog.styles.first { it.key == "giant" }
        assertEquals(StyleTuning.tune(style, true, true, Random(7)), StyleTuning.tune(style, true, true, Random(7)))
    }
}
