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
 * Mimic recordings are user data recorded live at the table. The store's whole
 * lifecycle — add, rename, delete, and cycle 27's recover-from-a-corrupted-index
 * path — runs here on a real temp directory, end to end.
 */
class MimicStoreRoundTripTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun clip(seconds: Double = 0.8) =
        FloatArray((AudioDsp.RATE * seconds).toInt()) { (0.3 * sin(2 * PI * 500.0 * it / AudioDsp.RATE)).toFloat() }

    @Test
    fun `add then list round-trips label, duration and category`() {
        val s = MimicStore.add(tmp.root, "Bram's laugh", clip())
        val listed = MimicStore.list(tmp.root)
        assertEquals(1, listed.size)
        assertEquals("Bram's laugh", listed[0].label)
        assertEquals(s.id, listed[0].id)
        assertEquals(0.8f, listed[0].seconds, 0.05f)
        assertEquals(MimicStore.CATEGORY, listed[0].category)
        assertTrue(listed[0].isUserFile)
        assertTrue(File(listed[0].file).exists())
    }

    @Test
    fun `rename persists and delete removes file and entry`() {
        val a = MimicStore.add(tmp.root, "Door", clip())
        Thread.sleep(2)
        val b = MimicStore.add(tmp.root, "Laugh", clip())
        MimicStore.rename(tmp.root, a, "Tavern door")
        assertEquals(setOf("Tavern door", "Laugh"), MimicStore.list(tmp.root).map { it.label }.toSet())
        MimicStore.delete(tmp.root, b)
        assertEquals(listOf("Tavern door"), MimicStore.list(tmp.root).map { it.label })
        assertFalse(File(b.file).exists())
    }

    @Test
    fun `a corrupted index recovers every recording instead of hiding it`() {
        MimicStore.add(tmp.root, "One", clip())
        Thread.sleep(2)
        MimicStore.add(tmp.root, "Two", clip())
        File(tmp.root, "mimic/index.json").writeText("{corrupt")
        val recovered = MimicStore.list(tmp.root)
        assertEquals(2, recovered.size)
        recovered.forEach { assertTrue(it.label.startsWith("Recovered sound")) }
        // And recovery heals the index: the next list reads it normally.
        assertEquals(2, MimicStore.list(tmp.root).size)
        assertTrue(File(tmp.root, "mimic/index.json").readText().contains("Recovered sound"))
    }

    @Test
    fun `a recording missing from the index is recovered alongside indexed ones`() {
        val kept = MimicStore.add(tmp.root, "Kept", clip())
        File(tmp.root, "mimic/mimic_000_orphan.wav").writeBytes(ByteArray(44 + 24000))
        val listed = MimicStore.list(tmp.root)
        assertEquals(2, listed.size)
        assertEquals(setOf("Kept", "Recovered sound 1"), listed.map { it.label }.toSet())
        assertEquals(kept.id, listed.first { it.label == "Kept" }.id)
    }
}
