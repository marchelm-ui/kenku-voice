package com.kenku.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The switch cadence is user-adjustable: every word, the classic 2-3, a calmer 4-6,
 * or one voice per sentence. Each mode keeps the core contracts — losslessness,
 * punctuated spoken pieces, tags in place.
 */
class ChunkerCadenceTest {

    private val line = "You owe us gold, the tavern keeper said so. Pay up now or face the watch tonight. Run!"
    private fun words(s: String) = s.split(Regex("\\s+")).filter { it.isNotEmpty() }

    @Test
    fun `every-word cadence gives one word per voice`() {
        for (seed in 0..4) {
            val chunks = Chunker.split(line, Random(seed), Cadence.WORD)
            assertTrue(chunks.all { it.words.size == 1 })
            assertEquals(words(line), chunks.flatMap { it.words })
        }
    }

    @Test
    fun `long cadence prefers four to six words`() {
        for (seed in 0..9) {
            val chunks = Chunker.split(line, Random(seed), Cadence.LONG)
            assertEquals(words(line), chunks.flatMap { it.words })
            chunks.forEach { assertTrue("size ${it.words.size}", it.words.size in 2..6) }
            val preferred = chunks.count { it.words.size in 4..6 }
            assertTrue("only $preferred of ${chunks.size} chunks in 4..6", preferred * 2 >= chunks.size)
        }
    }

    @Test
    fun `sentence cadence gives one voice per sentence`() {
        val chunks = Chunker.split(line, Random(0), Cadence.SENTENCE)
        assertEquals(3, chunks.size)
        assertEquals(words(line), chunks.flatMap { it.words })
        assertEquals("so.", chunks[0].words.last())
        assertEquals("tonight.", chunks[1].words.last())
        assertEquals(listOf("Run!"), chunks[2].words)
    }

    @Test
    fun `sentence cadence keeps an unpunctuated tail as a final chunk`() {
        val chunks = Chunker.split("Stop right there. and then we ran", Random(0), Cadence.SENTENCE)
        assertEquals(2, chunks.size)
        assertEquals(listOf("and", "then", "we", "ran"), chunks[1].words)
        // The final spoken piece still closes the sentence for the model.
        assertTrue(chunks[1].spoken.endsWith("."))
    }

    @Test
    fun `sound tags stay in place in every cadence`() {
        for (c in Cadence.entries) {
            val chunks = Chunker.split("Pay up [coins] right now friend.", Random(1), c)
            assertEquals("cadence $c", listOf("coins"), chunks.mapNotNull { it.sound })
            assertEquals("cadence $c", listOf("Pay", "up", "right", "now", "friend."), chunks.flatMap { it.words })
        }
    }

    @Test
    fun `default cadence is the classic patter`() {
        val short = Chunker.split(line, Random(3), Cadence.SHORT)
        val default = Chunker.split(line, Random(3))
        assertEquals(short.map { it.words }, default.map { it.words })
        short.forEach { assertTrue(it.words.size in 1..4) }
    }
}
