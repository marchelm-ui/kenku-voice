package com.kenku.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SfxSearchTest {

    private val titles = mapOf("doors" to "Doors & Locks", "coins" to "Coins & Treasure")
    private fun sfx(id: String, label: String, cat: String) = Sfx(id, label, "sfx/$cat/$id.ogg", 1f, cat)
    private val sounds = listOf(
        sfx("d1", "Heavy door slam", "doors"),
        sfx("d2", "Rusty lock click", "doors"),
        sfx("c1", "Coin toss", "coins"),
    )

    @Test
    fun `matches label words in any case`() {
        assertEquals(listOf("d1"), SfxSearch.filter(sounds, titles, "DOOR slam").map { it.id })
    }

    @Test
    fun `matches through the category title too`() {
        // "lock" appears in d2's label; "locks" also matches d1 via the category title.
        assertEquals(setOf("d1", "d2"), SfxSearch.filter(sounds, titles, "locks").map { it.id }.toSet())
        assertEquals(setOf("c1"), SfxSearch.filter(sounds, titles, "treasure").map { it.id }.toSet())
    }

    @Test
    fun `all query words must match somewhere`() {
        assertTrue(SfxSearch.filter(sounds, titles, "door coin").isEmpty())
        assertEquals(listOf("d2"), SfxSearch.filter(sounds, titles, "rusty lock").map { it.id })
    }

    @Test
    fun `a blank query returns everything, an unknown category crashes nothing`() {
        assertEquals(3, SfxSearch.filter(sounds, titles, "  ").size)
        val stray = sounds + sfx("x1", "Mystery hum", "unknowncat")
        assertEquals(listOf("x1"), SfxSearch.filter(stray, titles, "mystery").map { it.id })
    }
}
