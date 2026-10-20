package com.kenku.voice

import kotlin.random.Random

/**
 * Splits a line into the 2–3 word snippets a kenku "stole", choosing break points
 * the way a person would: after punctuation and between phrases, never leaving a
 * dangling "the", "of" or "to" at the end of a snippet. Picks vary a little each
 * time so the same line never comes out identically.
 */
/** How many words each stolen voice speaks before the next takes over. */
enum class Cadence(val key: String, val label: String, val detail: String) {
    WORD("word", "Every word", "Maximum mimicry — a new voice on every single word"),
    SHORT("short", "Every 2–3 words", "The classic kenku patter"),
    LONG("long", "Every 4–6 words", "Calmer switches, easier to follow"),
    SENTENCE("sentence", "Every sentence", "One voice speaks each whole sentence");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: SHORT
    }
}

object Chunker {

    /** A snippet of speech, or (when [sound] is set) a mimicked sound effect like [coins]. */
    data class Chunk(val words: List<String>, val isLast: Boolean, val sound: String? = null) {
        val display: String get() = words.joinToString(" ")

        /**
         * Text sent to the voice model. Mid-line snippets end on a comma so the voice keeps
         * a "still talking" rise instead of dropping like the end of a sentence; that is what
         * lets one stolen voice hand over smoothly to the next.
         */
        val spoken: String
            get() {
                val t = display.trimEnd()
                if (t.isEmpty()) return t
                // Look through closing quotes/brackets so `bird."` is seen as ending in `.`
                val core = t.trimEnd('"', '\'', '”', '’', ')', ']', '*')
                val last = core.lastOrNull() ?: return if (isLast) "$t." else "$t,"
                return when {
                    last in ".!?,;:" || last == '—' || last == '-' || last == '…' -> t
                    isLast -> "$t."
                    else -> "$t,"
                }
            }
    }

    // Words that should not end a snippet; they lean on the word after them.
    private val leaners = setOf(
        "a", "an", "the", "my", "your", "his", "her", "its", "our", "their", "this", "that", "these", "those",
        "of", "to", "in", "on", "at", "for", "with", "from", "by", "into", "onto", "upon", "about", "over",
        "under", "and", "or", "but", "nor", "so", "if", "than", "as", "is", "are", "was", "were", "be", "been",
        "am", "will", "would", "can", "could", "shall", "should", "must", "may", "might", "do", "does", "did",
        "not", "no", "very", "too", "i", "we", "you", "he", "she", "they", "it", "i'm", "you're", "we're",
        "they're", "it's", "don't", "can't", "won't", "some", "any", "every", "each", "all", "one", "two",
        "three", "who", "what", "where", "when", "why", "how", "which", "whose", "has", "have", "had", "just",
        "before", "after", "behind", "through", "across", "against", "between", "without", "within", "around",
        "toward", "towards", "near", "like", "since", "until", "till", "while", "because", "via", "per",
        "really", "quite", "more", "most", "less", "such",
    )

    private val tagRegex = Regex("\\[([^\\]]{1,40})]")

    /** Splits a line, keeping [sound] tags as their own snippets in place. */
    fun split(text: String, random: Random = Random.Default, cadence: Cadence = Cadence.SHORT): List<Chunk> {
        val out = mutableListOf<Chunk>()
        var pos = 0
        for (m in tagRegex.findAll(text)) {
            out += splitSpeech(text.substring(pos, m.range.first), random, cadence)
            val tag = m.groupValues[1].trim()
            if (tag.isNotEmpty()) out += Chunk(emptyList(), false, tag)
            pos = m.range.last + 1
        }
        out += splitSpeech(text.substring(pos), random, cadence)
        // Only the final speech snippet should sound like the end of a sentence.
        val lastSpeech = out.indexOfLast { it.sound == null }
        return out.mapIndexed { i, c -> if (c.sound == null) c.copy(isLast = i == lastSpeech) else c }
    }

    private fun splitSpeech(text: String, random: Random, cadence: Cadence): List<Chunk> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        when (cadence) {
            Cadence.WORD -> return words.map { Chunk(listOf(it), true) }
            Cadence.SENTENCE -> return bySentence(words)
            else -> {}
        }
        // SHORT prefers 2–3 word snippets; LONG prefers 4–6.
        val plainMax = if (cadence == Cadence.LONG) 6 else 3
        val sizes = if (cadence == Cadence.LONG) intArrayOf(4, 5, 3, 6, 2) else intArrayOf(2, 3, 1, 4)
        val sizePenalties = if (cadence == Cadence.LONG) mapOf(3 to 2.0, 6 to 1.0, 2 to 5.0)
        else mapOf(1 to 6.0, 4 to 2.2)
        if (words.size <= plainMax) return listOf(Chunk(words, true))

        // Cost of breaking right after word i (lower is better).
        val breakCost = DoubleArray(words.size) { i ->
            val w = words[i]
            val bare = w.lowercase().trim('"', '\'', '(', ')', '“', '”', '‘', '’', '*')
            val end = w.trimEnd('"', '\'', ')', '”', '’', '*').lastOrNull() ?: ' '
            var c = when {
                end in ".!?" || end == '…' -> -4.0
                end in ",;:" || w.endsWith("—") || w.endsWith("-") -> -3.0
                bare.trimEnd('.', ',', '!', '?', ';', ':') in leaners -> 3.0
                else -> 0.0
            }
            // A little randomness so splits differ from one recording to the next.
            c += random.nextDouble(-0.6, 0.6)
            c
        }

        // Dynamic programming over the cadence's preferred snippet sizes.
        val n = words.size
        val best = DoubleArray(n + 1) { Double.MAX_VALUE }
        val from = IntArray(n + 1) { -1 }
        best[0] = 0.0
        for (end in 1..n) {
            for (size in sizes) {
                val start = end - size
                if (start < 0 || best[start] == Double.MAX_VALUE) continue
                val sizePenalty = sizePenalties[size] ?: 0.0
                val cut = if (end == n) 0.0 else breakCost[end - 1]
                val cost = best[start] + cut + sizePenalty
                if (cost < best[end]) { best[end] = cost; from[end] = start }
            }
        }
        val bounds = mutableListOf<Int>()
        var e = n
        while (e > 0) { bounds.add(e); e = from[e] }
        bounds.reverse()
        val chunks = mutableListOf<Chunk>()
        var s = 0
        for ((i, b) in bounds.withIndex()) {
            chunks.add(Chunk(words.subList(s, b), i == bounds.lastIndex))
            s = b
        }
        return chunks
    }

    /** One chunk per sentence: a new chunk after every word ending in sentence punctuation. */
    private fun bySentence(words: List<String>): List<Chunk> {
        val out = mutableListOf<Chunk>()
        val cur = mutableListOf<String>()
        for (w in words) {
            cur.add(w)
            val end = w.trimEnd('"', '\'', '”', '’', ')', ']', '*').lastOrNull()
            if (end != null && (end in ".!?" || end == '…')) { out.add(Chunk(cur.toList(), true)); cur.clear() }
        }
        if (cur.isNotEmpty()) out.add(Chunk(cur.toList(), true))
        return out
    }
}
