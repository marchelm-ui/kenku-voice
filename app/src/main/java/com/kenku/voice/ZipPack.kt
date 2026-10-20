package com.kenku.voice

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
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
