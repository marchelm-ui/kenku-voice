package com.kenku.voice

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Sounds tab is driven by assets/sfx/catalog.json. A catalog entry whose file
 * is missing plays nothing at the table with no error; an ogg nobody references is
 * dead weight in a 600 MB APK. This guard keeps catalog and files in lockstep.
 */
class CatalogSanityTest {

    private fun assetsDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val f = File(dir, "app/src/main/assets")
            if (f.exists()) return f
            val local = File(dir, "src/main/assets")
            if (local.exists()) return local
            dir = dir.parentFile
        }
        throw AssertionError("assets dir not found above ${System.getProperty("user.dir")}")
    }

    private data class Entry(val id: String, val label: String, val file: String, val dur: Double, val category: String)

    private fun load(): Pair<List<Entry>, List<String>> {
        val root = JSONObject(File(assetsDir(), "sfx/catalog.json").readText())
        val cats = root.getJSONArray("categories")
        val entries = ArrayList<Entry>()
        val catKeys = ArrayList<String>()
        for (i in 0 until cats.length()) {
            val c = cats.getJSONObject(i)
            catKeys.add(c.getString("key"))
            assertTrue("category ${c.getString("key")} needs an icon", c.getString("icon").isNotBlank())
            assertTrue("category ${c.getString("key")} needs a title", c.getString("title").isNotBlank())
            val sounds = c.getJSONArray("sounds")
            for (j in 0 until sounds.length()) {
                val s = sounds.getJSONObject(j)
                entries.add(Entry(s.getString("id"), s.getString("label"), s.getString("file"),
                    s.getDouble("dur"), c.getString("key")))
            }
        }
        return entries to catKeys
    }

    @Test
    fun `every catalog entry points at a real asset file`() {
        val (entries, _) = load()
        val assets = assetsDir()
        val missing = entries.filter { !File(assets, it.file).exists() }
        assertTrue("catalog references missing files: ${missing.take(5).map { it.file }}", missing.isEmpty())
        assertTrue("suspiciously small catalog (${entries.size})", entries.size >= 400)
    }

    @Test
    fun `every ogg on disk is referenced by the catalog — no dead weight`() {
        val (entries, _) = load()
        val assets = assetsDir()
        val referenced = entries.map { it.file }.toSet()
        val orphans = File(assets, "sfx").walkTopDown()
            .filter { it.isFile && it.extension == "ogg" }
            .map { it.relativeTo(assets).path.replace(File.separatorChar, '/') }
            .filter { it !in referenced }
            .toList()
        assertTrue("unreferenced oggs shipping in the APK: ${orphans.take(5)}", orphans.isEmpty())
    }

    @Test
    fun `ids are unique and entries are sane`() {
        val (entries, catKeys) = load()
        assertEquals("duplicate category keys", catKeys.size, catKeys.toSet().size)
        assertEquals("duplicate sound ids", entries.size, entries.map { it.id }.toSet().size)
        entries.forEach {
            assertTrue("blank label on ${it.id}", it.label.isNotBlank())
            assertTrue("odd duration ${it.dur}s on ${it.id}", it.dur > 0.05 && it.dur < 60.0)
            assertTrue("file outside sfx/: ${it.file}", it.file.startsWith("sfx/"))
        }
    }

    @Test
    fun `durations roughly match the files on disk`() {
        // An ogg's byte size per second is variable, but a 10x mismatch means the
        // catalog's dur (shown in the UI and used for loop decisions) is a lie.
        val (entries, _) = load()
        val assets = assetsDir()
        val suspicious = entries.filter {
            val bytes = File(assets, it.file).length()
            val bytesPerSec = bytes / it.dur
            // Sub-half-second clips are dominated by the ogg header, so only the
            // lower bound applies to them.
            bytesPerSec < 1_000 || (it.dur >= 0.5 && bytesPerSec > 40_000)
        }
        assertTrue("entries whose dur disagrees wildly with file size: " +
            suspicious.take(5).map { "${it.id} (${it.dur}s, ${File(assets, it.file).length()}B)" },
            suspicious.isEmpty())
    }
}
