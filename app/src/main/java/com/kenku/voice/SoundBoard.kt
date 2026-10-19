package com.kenku.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
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

/**
 * Plays sound effects; several can overlap, any can be stopped, and any can loop.
 * Short sounds go through SoundPool, which keeps them decoded in memory so a tap plays
 * with almost no delay. Long sounds and loops stream through MediaPlayer.
 */
class SfxPlayer(private val context: Context) {
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private val pool = SoundPool.Builder().setMaxStreams(10).setAudioAttributes(attrs).build()
    private val handler = Handler(Looper.getMainLooper())

    private val loaded = HashMap<String, Int>()        // sound id -> SoundPool sample id
    private val ready = HashSet<Int>()                  // sample ids finished loading
    private val pendingPlay = HashMap<Int, Sfx>()       // sample id -> sound to play once loaded
    private val poolStreams = HashMap<String, Int>()    // sound id -> playing stream id
    private val players = LinkedHashMap<String, MediaPlayer>()
    private val looping = HashSet<String>()

    var onChange: (() -> Unit)? = null
    /** 0.0–1.0, applied to new and playing sounds. */
    var volume = 1f
        set(v) {
            field = v.coerceIn(0f, 1f)
            poolStreams.values.forEach { pool.setVolume(it, field, field) }
            players.values.forEach { try { it.setVolume(field, field) } catch (e: Exception) {} }
        }

    init {
        pool.setOnLoadCompleteListener { _, sample, status ->
            if (status == 0) ready.add(sample)
            pendingPlay.remove(sample)?.let { if (status == 0) startPool(it, sample) }
        }
    }

    fun isPlaying(id: String) = poolStreams.containsKey(id) || players.containsKey(id)
    fun isLooping(id: String) = id in looping
    val anyPlaying get() = poolStreams.isNotEmpty() || players.isNotEmpty()

    private fun usesPool(s: Sfx, loop: Boolean) = !loop && s.seconds <= 5f

    /** Decodes short sounds ahead of time (e.g. the category on screen) so the first tap is instant. */
    fun preload(sounds: List<Sfx>) {
        sounds.filter { usesPool(it, false) && it.id !in loaded }.forEach { s ->
            try { context.assets.openFd(s.file).use { fd -> loaded[s.id] = pool.load(fd, 1) } } catch (e: Exception) {}
        }
    }

    /** Starts the sound, or stops it if it is already playing (so long sounds can be cut off). */
    fun toggle(s: Sfx, loop: Boolean = false) {
        if (isPlaying(s.id)) { stop(s.id); return }
        if (poolStreams.size + players.size >= 10) (players.keys.firstOrNull() ?: poolStreams.keys.firstOrNull())?.let { stop(it) }
        if (usesPool(s, loop)) {
            val sample = loaded[s.id] ?: try {
                context.assets.openFd(s.file).use { fd -> pool.load(fd, 1) }.also { loaded[s.id] = it }
            } catch (e: Exception) { return }
            if (sample in ready) startPool(s, sample) else pendingPlay[sample] = s
        } else {
            startPlayer(s, loop)
        }
    }

    private fun startPool(s: Sfx, sample: Int) {
        val stream = pool.play(sample, volume, volume, 1, 0, 1f)
        if (stream == 0) return
        poolStreams[s.id] = stream
        onChange?.invoke()
        handler.postDelayed({
            if (poolStreams[s.id] == stream) { poolStreams.remove(s.id); onChange?.invoke() }
        }, (s.seconds * 1000).toLong() + 120)
    }

    private fun startPlayer(s: Sfx, loop: Boolean) {
        val mp = try {
            context.assets.openFd(s.file).use { fd ->
                MediaPlayer().apply {
                    setAudioAttributes(attrs)
                    setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                    isLooping = loop
                    setVolume(volume, volume)
                    prepare()
                }
            }
        } catch (e: Exception) { null } ?: return
        mp.setOnCompletionListener { done ->
            if (players[s.id] === done) { players.remove(s.id); looping.remove(s.id) }
            release(done); onChange?.invoke()
        }
        players[s.id] = mp
        if (loop) looping.add(s.id)
        mp.start()
        onChange?.invoke()
    }

    fun stop(id: String) {
        poolStreams.remove(id)?.let { pool.stop(it) }
        players.remove(id)?.let { release(it) }
        looping.remove(id)
        onChange?.invoke()
    }

    fun stopAll() {
        poolStreams.values.forEach { pool.stop(it) }
        poolStreams.clear()
        players.values.forEach { release(it) }
        players.clear()
        looping.clear()
        onChange?.invoke()
    }

    fun release() { stopAll(); pool.release() }

    private fun release(mp: MediaPlayer) { try { mp.stop() } catch (e: Exception) {}; try { mp.release() } catch (e: Exception) {} }
}
