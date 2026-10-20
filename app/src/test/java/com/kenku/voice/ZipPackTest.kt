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
    fun `an empty data dir packs an empty zip without crashing`() {
        val root = tmp.newFolder("files")
        assertEquals(0, ZipPack.build(root, File(tmp.root, "empty.zip")))
    }
}
