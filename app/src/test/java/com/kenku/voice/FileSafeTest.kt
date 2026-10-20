package com.kenku.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileSafeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `creates a new file and replaces an existing one`() {
        val f = File(tmp.root, "index.json")
        FileSafe.writeText(f, "first")
        assertEquals("first", f.readText())
        FileSafe.writeText(f, "second")
        assertEquals("second", f.readText())
        assertFalse("temp file left behind", File(tmp.root, "index.json.tmp").exists())
    }

    @Test
    fun `an interrupted write leaves the old content intact`() {
        val f = File(tmp.root, "index.json")
        FileSafe.writeText(f, "precious")
        // A crash mid-write is a temp file that never got renamed; the target is untouched.
        File(tmp.root, "index.json.tmp").writeText("half-writ")
        assertEquals("precious", f.readText())
        // And the next successful write wins over the stale temp file.
        FileSafe.writeText(f, "newer")
        assertEquals("newer", f.readText())
    }

    @Test
    fun `orphaned mimic wavs are recovered, never dropped`() {
        val known = setOf("mimic_1")
        val files = listOf(
            File(tmp.root, "mimic_1.wav").apply { writeBytes(ByteArray(44 + 48000)) },
            File(tmp.root, "mimic_2.wav").apply { writeBytes(ByteArray(44 + 24000)) },
            File(tmp.root, "index.json").apply { writeText("[]") },
        )
        val orphans = MimicStore.orphanEntries(known, files)
        assertEquals(1, orphans.size)
        assertEquals("mimic_2", orphans[0].id)
        assertTrue(orphans[0].label.startsWith("Recovered sound"))
        assertEquals(0.5f, orphans[0].seconds, 0.01f)
        assertEquals(MimicStore.CATEGORY, orphans[0].category)
    }

    @Test
    fun `recovery ignores non-wav files and known ids`() {
        val files = listOf(
            File(tmp.root, "a.wav").apply { writeBytes(ByteArray(44)) },
            File(tmp.root, "notes.txt").apply { writeText("x") },
        )
        assertTrue(MimicStore.orphanEntries(setOf("a"), files).isEmpty())
    }
}
