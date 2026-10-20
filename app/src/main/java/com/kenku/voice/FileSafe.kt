package com.kenku.voice

import java.io.File
import java.io.IOException

/**
 * Crash-safe small-file writes: write to a sibling temp file, then rename into place.
 * A crash mid-write leaves the old file intact instead of a half-written one — which,
 * for an index, used to mean every recording it listed silently "disappearing".
 */
object FileSafe {
    fun writeText(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            // Some filesystems refuse to rename onto an existing file.
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Couldn't replace ${target.name}")
        }
    }
}
