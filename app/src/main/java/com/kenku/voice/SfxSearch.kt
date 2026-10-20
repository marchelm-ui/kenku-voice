package com.kenku.voice

/**
 * The Sounds tab's search: every query word must appear in the sound's label or its
 * category title, any case. Pure, so the matching rules live under tests.
 */
object SfxSearch {
    fun filter(sounds: List<Sfx>, categoryTitles: Map<String, String>, query: String): List<Sfx> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return sounds
        return sounds.filter { s ->
            val hay = (s.label + " " + (categoryTitles[s.category] ?: "")).lowercase()
            words.all { hay.contains(it) }
        }
    }
}
