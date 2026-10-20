package com.kenku.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

/**
 * "A different voice and accent every 2-3 words" is the app's founding request.
 * These tests pin it: no immediate repeats, and every accent group gets an equal
 * chance no matter how many voices it holds.
 */
class VoicePickTest {

    private fun accent(key: String, voices: Int, sidBase: Int) =
        Accent(key, key, "", (0 until voices).map { KVoice(sidBase + it, "$key$it", "$key voice $it") })

    private fun pool(vararg accents: Accent): List<Speaker> =
        accents.flatMap { a -> a.voices.map { Speaker.Builtin(it, a) } }

    private val mixed = pool(accent("american", 20, 0), accent("british", 2, 100), accent("scottish", 1, 200))

    @Test
    fun `the same speaker is never picked twice in a row`() {
        val rnd = Random(3)
        var last: Speaker? = null
        var lastGroup: String? = null
        repeat(2000) {
            val s = VoicePick.pick(mixed, last, lastGroup, rnd)
            assertNotEquals("repeated speaker", last, s)
            last = s; lastGroup = VoicePick.groupOf(s)
        }
    }

    @Test
    fun `the same accent is never picked twice in a row when others exist`() {
        val rnd = Random(4)
        var last: Speaker? = null
        var lastGroup: String? = null
        repeat(2000) {
            val s = VoicePick.pick(mixed, last, lastGroup, rnd)
            assertNotEquals("repeated accent", lastGroup, VoicePick.groupOf(s))
            last = s; lastGroup = VoicePick.groupOf(s)
        }
    }

    @Test
    fun `small accents are heard as often as the twenty american voices`() {
        val rnd = Random(5)
        val counts = HashMap<String, Int>()
        repeat(15000) {
            val s = VoicePick.pick(mixed, null, null, rnd)
            counts.merge(VoicePick.groupOf(s), 1) { a, b -> a + b }
        }
        val shares = counts.values.map { it / 15000.0 }
        assertEquals(3, shares.size)
        shares.forEach { assertTrue("group share $it far from 1/3", abs(it - 1.0 / 3) < 0.03) }
    }

    @Test
    fun `clones count as groups of their own`() {
        val clone = Speaker.Cloned(Clone("c1", "Bram", true, File("unused")))
        val p = pool(accent("american", 3, 0)) + clone
        val rnd = Random(6)
        var seenClone = 0
        repeat(6000) { if (VoicePick.pick(p, null, null, rnd) == clone) seenClone++ }
        // Two groups → the clone alone should take about half the picks.
        assertTrue("clone share ${seenClone / 6000.0}", abs(seenClone / 6000.0 - 0.5) < 0.03)
    }

    @Test
    fun `a single available speaker is allowed to repeat`() {
        val only = pool(accent("american", 1, 0))
        val s = VoicePick.pick(only, only[0], "american", Random(7))
        assertEquals(only[0], s)
    }

    @Test
    fun `two voices in one accent alternate rather than deadlock`() {
        val two = pool(accent("american", 2, 0))
        val rnd = Random(8)
        var last: Speaker? = null
        repeat(100) {
            val s = VoicePick.pick(two, last, last?.let { VoicePick.groupOf(it) }, rnd)
            assertNotEquals(last, s)
            last = s
        }
    }
}
