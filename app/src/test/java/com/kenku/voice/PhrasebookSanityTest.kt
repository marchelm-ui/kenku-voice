package com.kenku.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Phrasebook phrases are one-tap content: each must stay short enough to speak at
 * the table, split cleanly, and never collide — the catalog lesson (cycle 9)
 * applied to the last unguarded data in the app.
 */
class PhrasebookSanityTest {

    private val all = Phrasebook.sections.flatMap { it.second }

    @Test
    fun `sections are named, iconed and non-empty`() {
        assertTrue(Phrasebook.sections.size >= 5)
        Phrasebook.sections.forEach { (title, phrases) ->
            assertTrue("blank title", title.isNotBlank())
            assertTrue("'$title' has no emoji icon", title.codePointAt(0) > 0x2000)
            assertTrue("'$title' is empty", phrases.isNotEmpty())
        }
    }

    @Test
    fun `no phrase is duplicated across the book`() {
        assertEquals(all.size, all.map { it.lowercase() }.toSet().size)
    }

    @Test
    fun `every phrase is tap-to-speak sized and cleanly ended`() {
        all.forEach { p ->
            assertTrue("blank phrase", p.isNotBlank())
            val words = p.split(Regex("\\s+")).filter { it.isNotEmpty() }
            assertTrue("too long to be a quick line: $p", words.size in 1..12)
            assertTrue("no closing punctuation: $p", p.trimEnd().last() in ".!?")
            assertTrue("contains a [tag] — phrases must be plain speech: $p", '[' !in p)
        }
    }

    @Test
    fun `every phrase splits losslessly for the voices`() {
        for (p in all) for (seed in 0..2) {
            val chunks = Chunker.split(p, Random(seed))
            assertEquals("lost words in: $p",
                p.split(Regex("\\s+")).filter { it.isNotEmpty() }, chunks.flatMap { it.words })
            chunks.forEach { assertTrue("oversized chunk in: $p", it.words.size in 1..4) }
        }
    }
}
