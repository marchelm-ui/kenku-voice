package com.kenku.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LineBookTest {

    private fun line(i: Int, starred: Boolean = false) = Line("id$i", "text $i", "v$i", starred)

    @Test
    fun `json round-trip keeps every field`() {
        val before = mutableListOf(line(1, starred = true), line(2), Line("id3", "quote \"inside\" and ’fancy’", "", false))
        val after = LineBook.fromJson(LineBook.toJson(before))
        assertEquals(before, after)
    }

    @Test
    fun `corrupt json loads as an empty list, never crashes`() {
        assertTrue(LineBook.fromJson("not json at all").isEmpty())
        assertTrue(LineBook.fromJson("[{\"id\":\"x\"}]").isEmpty()) // missing required field
        assertTrue(LineBook.fromJson("").isEmpty())
    }

    @Test
    fun `eviction drops only the oldest unpinned lines and reports their ids`() {
        // Newest first; ids 0..89, every 10th pinned.
        val lines = MutableList(90) { line(it, starred = it % 10 == 0) }
        val dropped = LineBook.evict(lines, cap = 20)
        assertEquals(81 - 20, dropped.size)
        assertEquals(20, lines.count { !it.starred })
        // The survivors are the newest unpinned ones.
        assertTrue(lines.filter { !it.starred }.all { it.id.removePrefix("id").toInt() < 23 })
        // The dropped ids are exactly the ones gone from the list.
        dropped.forEach { id -> assertTrue(lines.none { it.id == id }) }
    }

    @Test
    fun `a pinned line is never evicted, even with cap zero`() {
        val lines = MutableList(30) { line(it, starred = true) }
        val dropped = LineBook.evict(lines, cap = 0)
        assertTrue(dropped.isEmpty())
        assertEquals(30, lines.size)
    }

    @Test
    fun `eviction at the cap exactly drops nothing`() {
        val lines = MutableList(LineBook.UNPINNED_CAP) { line(it) }
        assertTrue(LineBook.evict(lines).isEmpty())
        assertEquals(LineBook.UNPINNED_CAP, lines.size)
    }

    @Test
    fun `visible matches every query word in any order and case`() {
        val lines = mutableListOf(
            Line("1", "You owe us gold", "", false),
            Line("2", "The tavern keeper said so", "", false),
            Line("3", "Gold for the tavern", "", true),
        )
        assertEquals(listOf("3", "2"), LineBook.visible(lines, "TAVERN").map { it.id })
        assertEquals(listOf("3"), LineBook.visible(lines, "gold tavern").map { it.id })
        assertEquals(listOf("3", "1", "2"), LineBook.visible(lines, "").map { it.id })
        assertTrue(LineBook.visible(lines, "dragon").isEmpty())
    }

    @Test
    fun `visible keeps pinned lines first and saved order within groups`() {
        val lines = mutableListOf(line(1), line(2, starred = true), line(3), line(4, starred = true))
        assertEquals(listOf("id2", "id4", "id1", "id3"), LineBook.visible(lines, "text").map { it.id })
    }

    @Test
    fun `renderKey changes when anything a card shows changes`() {
        val lines = mutableListOf(line(1), line(2))
        val before = LineBook.renderKey(LineBook.visible(lines, ""))
        lines[0].voices = "changed"
        val afterVoices = LineBook.renderKey(LineBook.visible(lines, ""))
        lines[1].starred = true
        val afterPin = LineBook.renderKey(LineBook.visible(lines, ""))
        assertTrue(before != afterVoices && afterVoices != afterPin)
        assertEquals(afterPin, LineBook.renderKey(LineBook.visible(lines, "")))
    }

    @Test
    fun `pinning an existing line stars it and keeps its cached audio id`() {
        val lines = mutableListOf(line(1), line(2))
        val pinned = LineBook.pin(lines, "text 2", newId = "new")
        assertSame(lines[1], pinned)
        assertTrue(pinned.starred)
        assertEquals("id2", pinned.id)
        assertEquals(2, lines.size)
    }

    @Test
    fun `pinning a new phrase inserts a starred line at the front`() {
        val lines = mutableListOf(line(1))
        val pinned = LineBook.pin(lines, "Roll for initiative!", newId = "fresh")
        assertSame(lines[0], pinned)
        assertTrue(pinned.starred)
        assertEquals("fresh", pinned.id)
        assertEquals("", pinned.voices)
        assertEquals(2, lines.size)
    }
}
