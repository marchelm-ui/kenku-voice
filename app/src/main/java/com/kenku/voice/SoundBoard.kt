package com.kenku.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import org.json.JSONObject

data class Sfx(val id: String, val label: String, val file: String, val seconds: Float, val category: String)
data class SfxCategory(val key: String, val title: String, val icon: String, val desc: String, val sounds: List<Sfx>)

/** The medieval sound library bundled in assets/sfx (all CC0). */
object SfxLibrary {
    @Volatile private var cache: List<SfxCategory>? = null

    fun categories(context: Context): List<SfxCategory> = cache ?: synchronized(this) {
        cache ?: load(context).also { cache = it }
    }

    private fun load(context: Context): List<SfxCategory> {
        val json = context.assets.open("sfx/catalog.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(json).getJSONArray("categories")
        return (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i)
            val key = c.getString("key")
            val s = c.getJSONArray("sounds")
            SfxCategory(key, c.getString("title"), c.getString("icon"), c.getString("desc"),
                (0 until s.length()).map { j ->
                    val o = s.getJSONObject(j)
                    Sfx(o.getString("id"), o.getString("label"), o.getString("file"), o.optDouble("dur", 1.0).toFloat(), key)
                })
        }
    }

    fun all(context: Context) = categories(context).flatMap { it.sounds }

    /**
     * Finds a sound for a tag typed into a line, like [coins], [door creak] or [goblin].
     * Matches sound names first, then whole categories; picks one at random so repeats vary.
     */
    fun find(context: Context, tag: String): Sfx? {
        val words = tag.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 }
            .map { it.removeSuffix("s").ifEmpty { it } }
        if (words.isEmpty()) return null
        val cats = categories(context)
        val exact = cats.flatMap { it.sounds }.filter { s -> s.label.lowercase().removeSuffix("s").trim() == tag.lowercase().removeSuffix("s").trim() }
        if (exact.isNotEmpty()) return exact.random()
        val byName = cats.flatMap { it.sounds }.filter { s -> val l = s.label.lowercase(); words.all { l.contains(it) } }
        if (byName.isNotEmpty()) return byName.random()
        val byCat = cats.filter { c -> val t = (c.key + " " + c.title + " " + c.desc).lowercase(); words.all { t.contains(it) } }
        if (byCat.isNotEmpty()) return byCat.random().sounds.random()
        val loose = cats.flatMap { it.sounds }.filter { s -> val l = s.label.lowercase(); words.any { l.contains(it) } }
        return loose.randomOrNull()
    }
}

/** Plays sound effects instantly; several can overlap, and any can be stopped. */
class SfxPlayer(private val context: Context) {
    private val playing = LinkedHashMap<String, MediaPlayer>()
    var onChange: (() -> Unit)? = null

    fun isPlaying(id: String) = playing.containsKey(id)
    val anyPlaying get() = playing.isNotEmpty()

    /** Starts the sound, or stops it if it is already playing (so long sounds can be cut off). */
    fun toggle(s: Sfx) {
        playing.remove(s.id)?.let { release(it); onChange?.invoke(); return }
        if (playing.size >= 8) playing.entries.first().let { release(it.value); playing.remove(it.key) }
        val mp = try {
            context.assets.openFd(s.file).use { fd ->
                MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                    prepare()
                }
            }
        } catch (e: Exception) { null } ?: return
        mp.setOnCompletionListener { done ->
            if (playing[s.id] === done) playing.remove(s.id)
            release(done); onChange?.invoke()
        }
        playing[s.id] = mp
        mp.start()
        onChange?.invoke()
    }

    fun stopAll() {
        playing.values.forEach { release(it) }
        playing.clear()
        onChange?.invoke()
    }

    private fun release(mp: MediaPlayer) { try { mp.stop() } catch (e: Exception) {}; try { mp.release() } catch (e: Exception) {} }
}
