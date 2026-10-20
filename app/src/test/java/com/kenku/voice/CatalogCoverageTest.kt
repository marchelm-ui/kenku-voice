package com.kenku.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The voice catalog maps 54 speaker ids (voices.bin order) into accent groups. The
 * ARM gate proves each sid produces sound; this guard proves the Kotlin side offers
 * every voice exactly once — a reorder, typo or forgotten prefix would silently
 * scramble or drop voices.
 */
class CatalogCoverageTest {

    @Test
    fun `all 54 voices exist with sequential sids and unique ids`() {
        assertEquals(54, Catalog.voices.size)
        assertEquals((0 until 54).toList(), Catalog.voices.map { it.sid })
        assertEquals(54, Catalog.voices.map { it.id }.toSet().size)
        Catalog.voices.forEach {
            assertTrue("odd id ${it.id}", Regex("^[a-z]{2}_[a-z]+$").matches(it.id))
            assertTrue("unhumanized name ${it.name}", Regex("^[A-Z].* \\((woman|man)\\)$").matches(it.name))
        }
    }

    @Test
    fun `accents partition the voices — every voice in exactly one group, none lost`() {
        val grouped = Catalog.accents.flatMap { it.voices }
        assertEquals("a voice appears in two accents", grouped.size, grouped.toSet().size)
        assertEquals("voices missing from every accent", Catalog.voices.toSet(), grouped.toSet())
    }

    @Test
    fun `accent groups are sound: unique keys, labels, non-empty, gender in names honest`() {
        assertEquals(Catalog.accents.size, Catalog.accents.map { it.key }.toSet().size)
        Catalog.accents.forEach { a ->
            assertTrue("empty accent ${a.key}", a.voices.isNotEmpty())
            assertTrue("blank label for ${a.key}", a.label.isNotBlank() && a.detail.isNotBlank())
            a.voices.forEach { v ->
                val saysWoman = v.name.endsWith("(woman)")
                assertEquals("gender mismatch for ${v.id}", v.id[1] == 'f', saysWoman)
            }
        }
    }
}
