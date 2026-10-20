package com.kenku.voice

import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Packs the user's irreplaceable data — voice clones, mimic recordings, the saved-lines
 * index — into one zip the user keeps themselves: insurance that doesn't depend on
 * Google's backup quota or schedule. Pure file work, tested on the JVM.
 */
object ZipPack {

    /** What goes in the pack, as zip-path → file, mirroring the backup rules. */
    fun entries(filesDir: File): List<Pair<String, File>> {
        val out = ArrayList<Pair<String, File>>()
        for (dirName in listOf("clones", "mimic")) {
            val dir = File(filesDir, dirName)
            dir.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.sortedBy { it.path }.forEach {
                out.add("$dirName/${it.relativeTo(dir).path.replace(File.separatorChar, '/')}" to it)
            }
        }
        File(filesDir, "lines.json").takeIf { it.exists() }?.let { out.add("lines.json" to it) }
        return out
    }

    /** Only these shapes may come OUT of a pack — anything else (zip-slip, strays) is skipped. */
    private val allowed = listOf(
        Regex("""clones/[A-Za-z0-9_-]+/sample\.wav"""),
        Regex("""clones/[A-Za-z0-9_-]+/info\.json"""),
        Regex("""mimic/[A-Za-z0-9_. '-]+\.wav"""),
        Regex("""mimic/index\.json"""),
        Regex("""lines\.json"""),
    )

    /**
     * Restores a pack into [filesDir], merging with what's already there: existing
     * files are never overwritten, and the two shared indexes (mimic, lines) are
     * merged by id with the existing entry winning. Returns how many files landed.
     */
    fun restore(zip: File, filesDir: File): Int {
        var restored = 0
        ZipFile(zip).use { z ->
            for (entry in z.entries().toList().sortedBy { it.name }) {
                if (entry.isDirectory) continue
                val name = entry.name
                if (allowed.none { it.matches(name) }) continue
                val target = File(filesDir, name)
                if (!target.canonicalPath.startsWith(filesDir.canonicalPath + File.separator)) continue
                when {
                    name == "lines.json" || name == "mimic/index.json" -> {
                        target.parentFile?.mkdirs()
                        val incoming = z.getInputStream(entry).readBytes().decodeToString()
                        val merged = mergeIndex(if (target.exists()) target.readText() else "[]", incoming)
                        FileSafe.writeText(target, merged)
                        restored++
                    }
                    target.exists() -> {} // never clobber what's already on the phone
                    else -> {
                        target.parentFile?.mkdirs()
                        z.getInputStream(entry).use { ins -> FileOutputStream(target).use { ins.copyTo(it) } }
                        restored++
                    }
                }
            }
        }
        return restored
    }

    /** Union of two JSON-array indexes by "id"; entries already present win. */
    fun mergeIndex(existing: String, incoming: String): String {
        fun parse(s: String): List<org.json.JSONObject> = try {
            val arr = JSONArray(s)
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } catch (e: Exception) { emptyList() }
        val out = LinkedHashMap<String, org.json.JSONObject>()
        for (o in parse(existing) + parse(incoming)) {
            val id = o.optString("id")
            if (id.isNotEmpty() && id !in out) out[id] = o
        }
        val arr = JSONArray()
        out.values.forEach { arr.put(it) }
        return arr.toString()
    }

    /** Writes the pack; returns how many files went in. */
    fun build(filesDir: File, out: File): Int {
        val all = entries(filesDir)
        if (all.isEmpty()) { out.writeBytes(ByteArray(0)); return 0 }
        ZipOutputStream(FileOutputStream(out).buffered()).use { zip ->
            for ((path, file) in all) {
                zip.putNextEntry(ZipEntry(path))
                FileInputStream(file).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return all.size
    }
}
