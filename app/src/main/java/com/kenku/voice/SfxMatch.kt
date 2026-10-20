package com.kenku.voice

import kotlin.random.Random

/**
 * Finds a sound for a tag typed into a line, like [coins], [door creak] or [goblin].
 * A tag that matches nothing plays silence at the table, so matching is tried in
 * four passes, strict to loose: exact label, all-words-in-label, whole category,
 * then any-word. Pure, so the whole ladder is unit-tested against the real catalog.
 */
object SfxMatch {

    fun find(cats: List<SfxCategory>, tag: String, rnd: Random = Random.Default): Sfx? {
        val words = tag.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 }
            .map { it.removeSuffix("s").ifEmpty { it } }
        if (words.isEmpty()) return null
        val exact = cats.flatMap { it.sounds }.filter { s -> s.label.lowercase().removeSuffix("s").trim() == tag.lowercase().removeSuffix("s").trim() }
        if (exact.isNotEmpty()) return exact.random(rnd)
        val byName = cats.flatMap { it.sounds }.filter { s -> val l = s.label.lowercase(); words.all { l.contains(it) } }
        if (byName.isNotEmpty()) return byName.random(rnd)
        // Only categories that actually hold sounds: [mimicry] with nothing recorded
        // used to crash here, and an empty match should fall through to the loose pass.
        val byCat = cats.filter { c -> c.sounds.isNotEmpty() && words.all { ((c.key + " " + c.title + " " + c.desc).lowercase()).contains(it) } }
        if (byCat.isNotEmpty()) return byCat.random(rnd).sounds.random(rnd)
        val loose = cats.flatMap { it.sounds }.filter { s -> val l = s.label.lowercase(); words.any { l.contains(it) } }
        return loose.randomOrNull(rnd)
    }
}
