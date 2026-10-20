package com.kenku.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * tools/benchmark_chunks.txt is what the CI voice benchmark speaks. This guard keeps
 * that fixture honest against the real Kotlin Chunker, so the deleted Python mirror
 * can never silently drift back into unrealism: the fixture must say exactly what the
 * benchmark text says, shaped exactly the way the app would shape it.
 */
class FixtureSanityTest {

    private fun fixtureFile(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val f = File(dir, "tools/benchmark_chunks.txt")
            if (f.exists()) return f
            dir = dir.parentFile
        }
        throw AssertionError("tools/benchmark_chunks.txt not found above ${System.getProperty("user.dir")}")
    }

    private fun strip(word: String) = word.trim().trimEnd('.', ',', '!', '?', ';', ':')

    @Test
    fun `fixture says exactly the benchmark text, in order`() {
        val lines = fixtureFile().readLines().map { it.trim() }
        val text = lines.first { it.startsWith("# text:") }.removePrefix("# text:").trim()
        val chunks = lines.filter { it.isNotEmpty() && !it.startsWith("#") }
        val spokenWords = chunks.flatMap { it.split(" ") }.map(::strip).filter { it.isNotEmpty() }
        val textWords = text.split(" ").map(::strip).filter { it.isNotEmpty() }
        assertEquals(textWords, spokenWords)
    }

    @Test
    fun `fixture chunks are shaped like real Chunker output`() {
        val lines = fixtureFile().readLines().map { it.trim() }
        val chunks = lines.filter { it.isNotEmpty() && !it.startsWith("#") }
        chunks.forEachIndexed { i, c ->
            assertTrue("chunk too big: $c", c.split(" ").size in 1..4)
            val last = c.trimEnd('"', '\'', '”', '’', ')', ']').lastOrNull()
            assertTrue("chunk lacks the continuation/sentence punctuation the app sends: $c",
                last != null && (last in ".!?,;:" || last == '—' || last == '-'))
            if (i == chunks.lastIndex) assertTrue("final chunk must close the sentence: $c", last in listOf('.', '!', '?'))
        }
        assertTrue("unrealistically few chunks", chunks.size in 15..25)
    }

    @Test
    fun `the real Chunker agrees with the fixture's text and shaping rules`() {
        val lines = fixtureFile().readLines().map { it.trim() }
        val text = lines.first { it.startsWith("# text:") }.removePrefix("# text:").trim()
        val textWords = text.split(" ").map(::strip).filter { it.isNotEmpty() }
        for (seed in 0..9) {
            val real = Chunker.split(text, Random(seed))
            assertEquals(textWords, real.flatMap { it.words }.map(::strip))
            real.forEach { c ->
                val last = c.spoken.trimEnd('"', '\'', '”', '’', ')', ']').lastOrNull()
                assertTrue("real chunk missing punctuation: ${c.spoken}",
                    last != null && (last in ".!?,;:" || last == '—' || last == '-'))
            }
        }
    }
}
