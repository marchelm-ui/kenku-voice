package com.kenku.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Property test: whatever gets typed, the Chunker's three contracts hold —
 * nothing is lost, nothing oversized reaches the model, every spoken piece is
 * punctuated, and [sound] tags come out as their own chunks in order.
 */
class ChunkerFuzzTest {

    private val words = listOf(
        "gold", "the", "tavern", "keeper", "said", "so", "run", "hide", "now", "friend",
        "2d6", "WAIT", "don't", "it's", "o'clock", "hm", "—", "Supercalifragilistic",
        "“quoted”", "(aside)", "coins!", "what?!", "wait…", "no,", "stop.", "🪙",
    )
    private val tags = listOf("[coins]", "[door creak]", "[wolf]")

    private fun randomLine(rnd: Random): Pair<String, List<String>> {
        val parts = ArrayList<String>()
        val expectTags = ArrayList<String>()
        repeat(rnd.nextInt(1, 30)) {
            if (rnd.nextInt(8) == 0) { val t = tags.random(rnd); parts.add(t); expectTags.add(t.trim('[', ']')) }
            else parts.add(words.random(rnd))
        }
        return parts.joinToString(" ") to expectTags
    }

    private fun strip(s: String) = s.split(Regex("\\s+")).filter { it.isNotEmpty() }

    @Test
    fun `three hundred random lines keep every contract`() {
        for (seed in 0 until 300) {
            val rnd = Random(seed)
            val (line, expectTags) = randomLine(rnd)
            val chunks = Chunker.split(line, Random(seed * 31 + 7))

            // 1. Lossless: spoken words equal the line's words minus the tags.
            val lineWordsNoTags = strip(line.replace(Regex("\\[[^\\]]*]"), " "))
            assertEquals("lost words in: $line", lineWordsNoTags, chunks.flatMap { it.words })

            // 2. Tags preserved, in order, as their own chunks.
            assertEquals("tags wrong in: $line", expectTags, chunks.mapNotNull { it.sound })

            // 3. Model-sized, punctuated speech.
            chunks.filter { it.sound == null }.forEach { c ->
                assertTrue("oversized chunk in: $line", c.words.size in 1..4)
                val sp = c.spoken
                if (sp.isNotEmpty()) {
                    val last = sp.trimEnd('"', '\'', '”', '’', ')', ']', '*').lastOrNull()
                    assertTrue("unpunctuated '$sp' in: $line",
                        last != null && (last in ".!?,;:" || last == '—' || last == '-' || last == '…'))
                }
            }

            // 4. Exactly one chunk may close the sentence.
            assertTrue(chunks.count { it.sound == null && it.isLast } <= 1)
        }
    }

    @Test
    fun `adjacent and leading tags split cleanly`() {
        val chunks = Chunker.split("[coins] [door creak] pay up [wolf]", Random(1))
        assertEquals(listOf("coins", "door creak", "wolf"), chunks.mapNotNull { it.sound })
        assertEquals(listOf("pay", "up"), chunks.flatMap { it.words })
    }
}
