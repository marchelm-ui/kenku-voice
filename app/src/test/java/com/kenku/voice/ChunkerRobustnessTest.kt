package com.kenku.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Lines typed at a D&D table are not clean prose: dice notation, ALL CAPS, emoji,
 * "What?!?!", ellipses, tabs from pasted notes. The splitter must survive all of it
 * losslessly — the catch-all error message is for the voice model, not for Chunker.
 */
class ChunkerRobustnessTest {

    private val tableTalk = listOf(
        "Roll 2d6 plus 3 for the goblin's axe",
        "I love gold 🪙 and fire 🔥 very much",
        "WAIT. STOP. EVERYONE BE QUIET NOW.",
        "What?!?! You sold the map?!",
        "Wait… let me think… okay go!",
        "The password is 'swordfish' (I think)",
        "He said — and I quote — \"never again\"",
        "Supercalifragilisticexpialidocious is my familiar's name",
        "Attack\tthe\ttower\nbefore\nsunrise",
        "10 gold, 20 silver, 300 copper pieces",
    )

    private fun words(s: String) = s.split(Regex("\\s+")).filter { it.isNotEmpty() }

    @Test
    fun `table talk splits losslessly`() {
        for (line in tableTalk) for (seed in 0..4) {
            val chunks = Chunker.split(line, Random(seed))
            assertEquals("lost words in: $line", words(line), chunks.flatMap { it.words })
        }
    }

    @Test
    fun `every spoken piece is punctuated and sized for the model`() {
        for (line in tableTalk) for (seed in 0..4) {
            val chunks = Chunker.split(line, Random(seed))
            chunks.filter { it.sound == null }.forEach { c ->
                assertTrue("oversized chunk in: $line", c.words.size in 1..4)
                val last = c.spoken.trimEnd('"', '\'', '”', '’', ')', ']', '*').lastOrNull()
                assertTrue("unpunctuated spoken piece '${c.spoken}' in: $line",
                    last != null && (last in ".!?,;:" || last == '—' || last == '-' || last == '…'))
            }
        }
    }

    @Test
    fun `an ellipsis is punctuation, never doubled up for the model`() {
        assertEquals("think…", Chunker.Chunk(listOf("think…"), isLast = false).spoken)
        assertEquals("think…", Chunker.Chunk(listOf("think…"), isLast = true).spoken)
        assertEquals("“wait…”", Chunker.Chunk(listOf("“wait…”"), isLast = false).spoken.lowercase())
    }

    @Test
    fun `empty and whitespace input yields nothing, symbol soup survives losslessly`() {
        for (line in listOf("", "   ", "\n\t"))
            assertTrue("expected nothing for blank input", Chunker.split(line, Random(0)).isEmpty())
        for (line in listOf("?!?!", "…", "()"))
            assertEquals(words(line), Chunker.split(line, Random(0)).flatMap { it.words })
    }
}
