package com.kenku.voice

import kotlin.random.Random

/**
 * Splits a line into the 2–3 word snippets a kenku "stole", choosing break points
 * the way a person would: after punctuation and between phrases, never leaving a
 * dangling "the", "of" or "to" at the end of a snippet. Picks vary a little each
 * time so the same line never comes out identically.
 */
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
                val last = t.lastOrNull() ?: return t
                return when {
                    last in ".!?" -> t
                    last in ",;:—-" -> t.trimEnd(',', ';', ':', '—', '-') + ","
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
    fun split(text: String, random: Random = Random.Default): List<Chunk> {
        val out = mutableListOf<Chunk>()
        var pos = 0
        for (m in tagRegex.findAll(text)) {
            out += splitSpeech(text.substring(pos, m.range.first), random)
            val tag = m.groupValues[1].trim()
            if (tag.isNotEmpty()) out += Chunk(emptyList(), false, tag)
            pos = m.range.last + 1
        }
        out += splitSpeech(text.substring(pos), random)
        // Only the final speech snippet should sound like the end of a sentence.
        val lastSpeech = out.indexOfLast { it.sound == null }
        return out.mapIndexed { i, c -> if (c.sound == null) c.copy(isLast = i == lastSpeech) else c }
    }

    private fun splitSpeech(text: String, random: Random): List<Chunk> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        if (words.size <= 3) return listOf(Chunk(words, true))

        // Cost of breaking right after word i (lower is better).
        val breakCost = DoubleArray(words.size) { i ->
            val w = words[i]
            val bare = w.lowercase().trim('"', '\'', '(', ')', '“', '”', '‘', '’', '*')
            val end = w.trimEnd('"', '\'', ')', '”', '’', '*').lastOrNull() ?: ' '
            var c = when {
                end in ".!?" -> -4.0
                end in ",;:" || w.endsWith("—") || w.endsWith("-") -> -3.0
                bare.trimEnd('.', ',', '!', '?', ';', ':') in leaners -> 3.0
                else -> 0.0
            }
            // A little randomness so splits differ from one recording to the next.
            c += random.nextDouble(-0.6, 0.6)
            c
        }

        // Dynamic programming over snippet sizes 2 or 3 (a single word only if forced).
        val n = words.size
        val best = DoubleArray(n + 1) { Double.MAX_VALUE }
        val from = IntArray(n + 1) { -1 }
        best[0] = 0.0
        for (end in 1..n) {
            for (size in intArrayOf(2, 3, 1, 4)) {
                val start = end - size
                if (start < 0 || best[start] == Double.MAX_VALUE) continue
                val sizePenalty = when (size) { 1 -> 6.0; 4 -> 2.2; else -> 0.0 }
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
}
