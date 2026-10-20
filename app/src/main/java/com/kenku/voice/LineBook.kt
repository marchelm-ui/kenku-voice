package com.kenku.voice

import org.json.JSONArray
import org.json.JSONObject

/** One saved line on the Speak tab. Starred lines are the ⚡ Quick lines. */
data class Line(val id: String, val text: String, var voices: String, var starred: Boolean = false)

/**
 * Pure bookkeeping for the saved-lines list: JSON round-trip, pinning a phrase,
 * and evicting old lines. Kept free of Android so every rule here is unit-tested —
 * above all the one that matters most: a pinned line is never evicted.
 */
object LineBook {

    /** Old unpinned lines beyond this are dropped; pinned lines don't count against it. */
    const val UNPINNED_CAP = 80

    fun fromJson(s: String): MutableList<Line> = try {
        val arr = JSONArray(s)
        MutableList(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Line(o.getString("id"), o.getString("text"), o.optString("voices"), o.optBoolean("starred"))
        }
    } catch (e: Exception) {
        mutableListOf()
    }

    fun toJson(lines: List<Line>): String {
        val arr = JSONArray()
        lines.forEach { arr.put(JSONObject().put("id", it.id).put("text", it.text).put("voices", it.voices).put("starred", it.starred)) }
        return arr.toString()
    }

    /**
     * Drops the oldest unpinned lines (list order is newest-first) until at most
     * [cap] unpinned remain. Returns the dropped ids so their cached audio files
     * can be deleted. Pinned lines are never touched.
     */
    fun evict(lines: MutableList<Line>, cap: Int = UNPINNED_CAP): List<String> {
        val dropped = ArrayList<String>()
        var unpinned = lines.count { !it.starred }
        while (unpinned > cap) {
            val old = lines.last { !it.starred }
            dropped.add(old.id)
            lines.remove(old)
            unpinned--
        }
        return dropped
    }

    /**
     * The lines the Speak tab shows for a search query: every query word must appear
     * in the line (any order, any case), pinned lines first, saved order kept within
     * each group. A blank query shows everything.
     */
    fun visible(lines: List<Line>, query: String): List<Line> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val shown = if (words.isEmpty()) lines else lines.filter { l -> val t = l.text.lowercase(); words.all { t.contains(it) } }
        return shown.filter { it.starred } + shown.filter { !it.starred }
    }

    /** A cheap fingerprint of what a rendered card list would look like, for skipping no-op rebuilds. */
    fun renderKey(visible: List<Line>): String = visible.joinToString("\n") { "${it.id}|${it.starred}|${it.voices}|${it.text}" }

    /**
     * Pins a phrase as a Quick line: stars the existing line with the same text
     * (so its cached audio is kept), or inserts a new starred line at the front.
     */
    fun pin(lines: MutableList<Line>, text: String, newId: String): Line {
        lines.firstOrNull { it.text == text }?.let { it.starred = true; return it }
        val line = Line(newId, text, "", starred = true)
        lines.add(0, line)
        return line
    }
}
