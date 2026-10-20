package com.kenku.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * Clones are the one thing the user cannot re-make without the original person at
 * the mic, so their persistence — create, list, rename state, delete, and survival
 * of a corrupted info.json — is pinned end to end on a real temp directory.
 */
class CloneStoreRoundTripTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = CloneStore(tmp.root)

    private fun voice(seconds: Int = 2) =
        FloatArray(AudioDsp.RATE * seconds) { (0.2 * sin(2 * PI * 150.0 * it / AudioDsp.RATE)).toFloat() }

    @Test
    fun `create then list round-trips name, mix state and audio`() {
        val s = store()
        val made = s.create("Captain Bram", voice())
        val listed = s.list()
        assertEquals(1, listed.size)
        assertEquals("Captain Bram", listed[0].name)
        assertTrue(listed[0].inMix)
        assertEquals(made.id, listed[0].id)
        assertEquals(2f, listed[0].seconds, 0.01f)
        assertEquals(AudioDsp.RATE * 2, listed[0].samples().size)
    }

    @Test
    fun `save persists changed name and mix state`() {
        val s = store()
        val c = s.create("Mira", voice())
        c.name = "Mira the Bard"
        c.inMix = false
        s.save(c)
        val back = store().list().single()
        assertEquals("Mira the Bard", back.name)
        assertFalse(back.inMix)
    }

    @Test
    fun `a corrupted info json keeps the voice, losing only its name`() {
        val s = store()
        val c = s.create("Bram", voice())
        File(File(tmp.root, "clones/${c.id}"), "info.json").writeText("{not json")
        val back = store().list().single()
        assertEquals("Unnamed voice", back.name)
        assertTrue(back.inMix)
        assertEquals(AudioDsp.RATE * 2, back.samples().size)
    }

    @Test
    fun `delete removes the clone and its files`() {
        val s = store()
        val keep = s.create("Keep", voice())
        Thread.sleep(2) // distinct millisecond ids
        val drop = s.create("Drop", voice())
        s.delete(drop)
        assertEquals(listOf(keep.id), s.list().map { it.id })
        assertFalse(File(tmp.root, "clones/${drop.id}").exists())
    }

    @Test
    fun `a directory without a sample is not listed`() {
        val s = store()
        File(tmp.root, "clones/stray").mkdirs()
        File(tmp.root, "clones/stray/info.json").writeText("""{"name":"Ghost","inMix":true}""")
        assertTrue(s.list().isEmpty())
    }
}
