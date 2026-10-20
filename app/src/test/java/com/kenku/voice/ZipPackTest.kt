package com.kenku.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.PI
import kotlin.math.sin

class ZipPackTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun clip() = FloatArray(AudioDsp.RATE / 2) { (0.2 * sin(2 * PI * 300.0 * it / AudioDsp.RATE)).toFloat() }

    @Test
    fun `the pack holds exactly what the backup rules protect`() {
        val root = tmp.newFolder("files")
        CloneStore(root).create("Bram", clip())
        MimicStore.add(root, "Laugh", clip())
        File(root, "lines.json").writeText("[]")
        File(root, "lines").mkdirs()
        File(root, "lines/123.wav").writeBytes(ByteArray(100)) // regenerable bulk: stays out
        File(root, "mimic/index.json.tmp").writeText("half")   // temp residue: stays out

        val out = File(tmp.root, "pack.zip")
        val count = ZipPack.build(root, out)
        ZipFile(out).use { zip ->
            val names = zip.entries().toList().map { it.name }.toSet()
            assertEquals(count, names.size)
            assertTrue("missing clone sample", names.any { it.startsWith("clones/") && it.endsWith("sample.wav") })
            assertTrue("missing clone info", names.any { it.startsWith("clones/") && it.endsWith("info.json") })
            assertTrue("missing mimic wav", names.any { it.startsWith("mimic/") && it.endsWith(".wav") })
            assertTrue("missing mimic index", "mimic/index.json" in names)
            assertTrue("missing lines index", "lines.json" in names)
            assertTrue("bulk line audio leaked in", names.none { it.startsWith("lines/") })
            assertTrue("temp residue leaked in", names.none { it.endsWith(".tmp") })
        }
    }

    @Test
    fun `zip contents round-trip byte for byte`() {
        val root = tmp.newFolder("files")
        MimicStore.add(root, "Door", clip())
        val out = File(tmp.root, "pack.zip")
        ZipPack.build(root, out)
        ZipFile(out).use { zip ->
            for ((path, file) in ZipPack.entries(root)) {
                val entry = zip.getEntry(path) ?: throw AssertionError("missing $path")
                assertTrue("bytes differ for $path",
                    zip.getInputStream(entry).readBytes().contentEquals(file.readBytes()))
            }
        }
    }

    @Test
    fun `restore into an empty phone brings everything back`() {
        val root = tmp.newFolder("files")
        val clone = CloneStore(root).create("Bram", clip())
        MimicStore.add(root, "Laugh", clip())
        File(root, "lines.json").writeText(LineBook.toJson(listOf(Line("1", "You owe us gold", "", true))))
        val pack = File(tmp.root, "pack.zip")
        ZipPack.build(root, pack)

        val fresh = tmp.newFolder("newphone")
        val n = ZipPack.restore(pack, fresh)
        assertEquals(ZipPack.entries(root).size, n)
        assertEquals(listOf("Bram"), CloneStore(fresh).list().map { it.name })
        assertEquals(clone.id, CloneStore(fresh).list().single().id)
        assertEquals(listOf("Laugh"), MimicStore.list(fresh).map { it.label })
        assertEquals("You owe us gold", LineBook.fromJson(File(fresh, "lines.json").readText()).single().text)
    }

    @Test
    fun `restore merges without clobbering what the phone already has`() {
        val old = tmp.newFolder("oldphone")
        MimicStore.add(old, "Old laugh", clip())
        val pack = File(tmp.root, "pack.zip")
        ZipPack.build(old, pack)

        val now = tmp.newFolder("nowphone")
        Thread.sleep(2)
        val mine = MimicStore.add(now, "My new door", clip())
        ZipPack.restore(pack, now)
        val labels = MimicStore.list(now).map { it.label }.toSet()
        assertEquals(setOf("Old laugh", "My new door"), labels)
        // Restoring the same pack again changes nothing.
        val before = MimicStore.list(now).map { it.id }.toSet()
        ZipPack.restore(pack, now)
        assertEquals(before, MimicStore.list(now).map { it.id }.toSet())
        assertEquals("My new door", MimicStore.list(now).first { it.id == mine.id }.label)
    }

    @Test
    fun `hostile entries are skipped, never written`() {
        val evil = File(tmp.root, "evil.zip")
        java.util.zip.ZipOutputStream(evil.outputStream()).use { z ->
            for (name in listOf("../escape.txt", "clones/../../escape2.txt", "/abs.txt", "random.bin", "mimic/sneaky.exe")) {
                z.putNextEntry(java.util.zip.ZipEntry(name)); z.write(1); z.closeEntry()
            }
        }
        val root = tmp.newFolder("victim")
        assertEquals(0, ZipPack.restore(evil, root))
        assertTrue(tmp.root.walkTopDown().none { it.name.startsWith("escape") || it.name == "abs.txt" || it.name == "sneaky.exe" })
    }

    @Test
    fun `an empty data dir packs an empty zip without crashing`() {
        val root = tmp.newFolder("files")
        assertEquals(0, ZipPack.build(root, File(tmp.root, "empty.zip")))
    }
}
