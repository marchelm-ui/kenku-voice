package com.kenku.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartGateTest {

    /** RTF = generation seconds per second of audio produced. */
    private fun afterChunks(chunks: Int, totalWords: Int, rtf: Double, wordsPerChunk: Int = 2, secPerWord: Double = 0.35): Triple<Int, Double, Double> {
        val words = chunks * wordsPerChunk
        val audio = words * secPerWord
        return Triple(words, audio, audio * rtf)
    }

    @Test
    fun `fast generation starts on the very first chunk`() {
        // RTF 0.6 like the real benchmark: the rest can always be made in time.
        val (words, audio, gen) = afterChunks(1, totalWords = 50, rtf = 0.6)
        assertTrue(StartGate.shouldStart(words, 50, audio, audio, gen))
    }

    @Test
    fun `slow generation waits until enough is buffered`() {
        // RTF 1.4: generating slower than real time. Starting on chunk one would stall.
        val (w1, a1, g1) = afterChunks(1, totalWords = 50, rtf = 1.4)
        assertFalse(StartGate.shouldStart(w1, 50, a1, a1, g1))
        // By the time most of the line is buffered, the remainder is affordable.
        val (w2, a2, g2) = afterChunks(22, totalWords = 50, rtf = 1.4)
        assertTrue(StartGate.shouldStart(w2, 50, a2, a2, g2))
    }

    @Test
    fun `the last chunk always starts playback, however slow things were`() {
        assertTrue(StartGate.shouldStart(50, 50, 17.5, 17.5, 90.0))
        assertTrue(StartGate.shouldStart(51, 50, 17.5, 17.5, 90.0))
    }

    @Test
    fun `no words means no start, and zero audio cannot divide`() {
        assertFalse(StartGate.shouldStart(0, 50, 0.0, 0.0, 0.0))
        assertFalse(StartGate.shouldStart(2, 50, 0.0, 0.0, 1.0))
    }

    @Test
    fun `once affordable, more buffer never flips the decision back`() {
        // Same rates, growing progress: the gate's answer may flip false→true, never true→false.
        var wasTrue = false
        for (chunks in 1..25) {
            val (w, a, g) = afterChunks(chunks, totalWords = 50, rtf = 1.1)
            val now = StartGate.shouldStart(w, 50, a, a, g)
            if (wasTrue) assertTrue("flipped back at chunk $chunks", now)
            if (now) wasTrue = true
        }
        assertTrue("never became affordable", wasTrue)
    }

    @Test
    fun `headroom makes the gate conservative near the break-even rate`() {
        // At RTF just under 1.0 the raw math says "affordable", but the 1.15 headroom
        // should hold the first chunk back — a stutter is worse than 200 ms more wait.
        val (w, a, g) = afterChunks(1, totalWords = 50, rtf = 0.95)
        assertFalse(StartGate.shouldStart(w, 50, a, a, g))
    }
}
