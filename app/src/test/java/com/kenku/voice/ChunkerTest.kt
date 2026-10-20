package com.kenku.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ChunkerTest {

    private fun speechWords(chunks: List<Chunker.Chunk>) = chunks.filter { it.sound == null }.flatMap { it.words }

    @Test
    fun `splitting is lossless - every word survives in order`() {
        val text = "You owe us gold the tavern keeper said so pay now little bird or the guards hear everything"
        for (seed in 0..30) {
            val chunks = Chunker.split(text, Random(seed))
            assertEquals(text.split(" "), speechWords(chunks))
        }
    }

    @Test
    fun `chunk sizes stay speakable`() {
        val text = (1..60).joinToString(" ") { "word$it" }
        for (seed in 0..20) {
            val chunks = Chunker.split(text, Random(seed))
            chunks.forEach { assertTrue("size ${it.words.size}", it.words.size in 1..4) }
            // Most chunks are the kenku's 2-3 words.
            val small = chunks.count { it.words.size in 2..3 }
            assertTrue("too many odd sizes", small >= chunks.size * 2 / 3)
        }
    }

    @Test
    fun `exactly one speech chunk is marked last`() {
        for (seed in 0..10) {
            val chunks = Chunker.split("Run quickly through the market and hide the map now", Random(seed))
            assertEquals(1, chunks.count { it.isLast })
            assertTrue(chunks.last().isLast)
        }
    }

    @Test
    fun `sound tags become their own chunks in order`() {
        val chunks = Chunker.split("Pay up [coins] or the guards [door creak] hear everything", Random(1))
        assertEquals(listOf("coins", "door creak"), chunks.filter { it.sound != null }.map { it.sound })
        assertEquals("Pay up or the guards hear everything".split(" "), speechWords(chunks))
        // A sound chunk is never the "last speech" chunk.
        assertTrue(chunks.filter { it.sound != null }.none { it.isLast })
    }

    @Test
    fun `tag-only and empty input do not crash`() {
        assertEquals(0, Chunker.split("", Random(1)).size)
        assertEquals(0, Chunker.split("   ", Random(1)).size)
        val only = Chunker.split("[coins]", Random(1))
        assertEquals(1, only.size)
        assertEquals("coins", only[0].sound)
    }

    @Test
    fun `short lines come out whole`() {
        assertEquals(listOf("Hello"), Chunker.split("Hello", Random(1))[0].words)
        assertEquals(3, Chunker.split("Hold the line", Random(1))[0].words.size)
    }

    @Test
    fun `spoken keeps existing punctuation and adds continuation commas`() {
        fun chunk(text: String, last: Boolean) = Chunker.Chunk(text.split(" "), last)
        assertEquals("said so.", chunk("said so.", false).spoken)
        assertEquals("gold,", chunk("gold,", false).spoken)
        assertEquals("bird,", chunk("bird", false).spoken)
        assertEquals("bird.", chunk("bird", true).spoken)
        assertEquals("now;", chunk("now;", false).spoken)
    }

    @Test
    fun `spoken sees punctuation hidden behind closing quotes`() {
        fun chunk(text: String, last: Boolean) = Chunker.Chunk(text.split(" "), last)
        // The old bug: 'bird."' became 'bird.".' (doubled punctuation for the voice model).
        assertEquals("bird.\"", chunk("bird.\"", true).spoken)
        assertEquals("gold,”", chunk("gold,”", false).spoken)
        // A bare quote with no punctuation still gets its continuation comma.
        assertEquals("\"gold\",", chunk("\"gold\"", false).spoken)
    }

    @Test
    fun `leaning words rarely end a chunk`() {
        val text = "I went to the market and bought a sack of red apples for my dear old mother before the long cold night"
        var violations = 0
        var boundaries = 0
        for (seed in 0..19) {
            val chunks = Chunker.split(text, Random(seed))
            chunks.dropLast(1).forEach { c ->
                boundaries++
                val lastWord = c.words.last().lowercase()
                if (lastWord in setOf("the", "a", "of", "to", "and", "for", "my", "before")) violations++
            }
        }
        // The split cost makes these cuts expensive; allow a tiny slip from randomness, no more.
        assertTrue("$violations of $boundaries boundaries end on a leaning word", violations <= boundaries / 20)
    }

    @Test
    fun `long input splits quickly`() {
        val text = (1..600).joinToString(" ") { "word$it" }
        val t0 = System.nanoTime()
        val chunks = Chunker.split(text, Random(1))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took $ms ms", ms < 1000)
        assertEquals(600, speechWords(chunks).size)
    }
}
