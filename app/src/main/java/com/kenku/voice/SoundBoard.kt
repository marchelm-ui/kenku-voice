package com.kenku.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A sound effect. [file] is an asset path, or an absolute path for sounds the user recorded. */
data class Sfx(val id: String, val label: String, val file: String, val seconds: Float, val category: String) {
    val isUserFile get() = file.startsWith("/")
}

/**
 * Kenku Mimicry: real sounds the user records at the table (a laugh, a door, a friend's
 * catchphrase), saved as playable sounds and usable as [tags] in lines.
 */
object MimicStore {
    const val CATEGORY = "mimic"
    private fun dir(base: File) = File(base, "mimic").apply { mkdirs() }
    private fun index(base: File) = File(dir(base), "index.json")

    fun list(base: File): List<Sfx> {
        val indexed = try {
            val arr = JSONArray(index(base).readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Sfx(o.getString("id"), o.getString("label"), File(dir(base), o.getString("id") + ".wav").absolutePath,
                    o.optDouble("dur", 1.0).toFloat(), CATEGORY)
            }.filter { File(it.file).exists() }
        } catch (e: Exception) { emptyList() }
        // Recordings the index forgot (a crash mid-save, a corrupted index) are
        // recovered from their wav files rather than silently vanishing.
        val orphans = orphanEntries(indexed.map { it.id }.toSet(), dir(base).listFiles()?.toList() ?: emptyList())
        if (orphans.isEmpty()) return indexed
        val all = indexed + orphans
        try { save(base, all) } catch (e: Exception) {}
        return all
    }

    /** Entries for wav files missing from the index: recovered with a plain name, never deleted. */
    fun orphanEntries(knownIds: Set<String>, files: List<File>): List<Sfx> = files
        .filter { it.isFile && it.extension == "wav" && it.nameWithoutExtension !in knownIds }
        .sortedBy { it.name }
        .mapIndexed { i, f ->
            Sfx(f.nameWithoutExtension, "Recovered sound ${i + 1}", f.absolutePath,
                (f.length() - 44).coerceAtLeast(0) / 2f / AudioDsp.RATE, CATEGORY)
        }

    private fun save(base: File, items: List<Sfx>) {
        val arr = JSONArray()
        // One entry per id, newest label wins — a second line of defense against duplicates.
        items.associateBy { it.id }.values.forEach { arr.put(JSONObject().put("id", it.id).put("label", it.label).put("dur", it.seconds.toDouble())) }
        FileSafe.writeText(index(base), arr.toString())
    }

    fun add(base: File, label: String, samples: FloatArray): Sfx {
        // Snapshot the list BEFORE writing the wav: list() recovers orphaned wavs,
        // and a just-written file must not be "recovered" as a duplicate entry.
        val existing = list(base)
        val id = "mimic_" + System.currentTimeMillis()
        val f = File(dir(base), "$id.wav")
        AudioDsp.writeWav(samples, f)
        val s = Sfx(id, label, f.absolutePath, samples.size / AudioDsp.RATE.toFloat(), CATEGORY)
        save(base, existing + s)
        return s
    }

    fun rename(base: File, s: Sfx, label: String) = save(base, list(base).map { if (it.id == s.id) it.copy(label = label) else it })

    fun delete(base: File, s: Sfx) {
        File(s.file).delete()
        save(base, list(base).filter { it.id != s.id })
    }
}
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
        val cats = listOf(SfxCategory(MimicStore.CATEGORY, "My Mimicry", "🦜", "", MimicStore.list(context.filesDir))) + categories(context)
        return SfxMatch.find(cats, tag)
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

    /**
     * Decodes short sounds ahead of time (the category on screen) so the first tap is
     * instant — and drops decoded sounds from other screens, so browsing all eleven
     * categories can never pile hundreds of PCM buffers in memory next to the voice model.
     */
    fun preload(sounds: List<Sfx>) {
        val showing = sounds.map { it.id }.toSet()
        for (id in SfxCache.toEvict(loaded.keys, showing, poolStreams.keys)) {
            loaded.remove(id)?.let { sample ->
                pool.unload(sample)
                ready.remove(sample)
                pendingPlay.remove(sample)
            }
        }
        sounds.filter { usesPool(it, false) && it.id !in loaded }.forEach { s ->
            try { loaded[s.id] = load(s) } catch (e: Exception) {}
        }
    }

    /** Starts the sound, or stops it if it is already playing (so long sounds can be cut off). */
    fun toggle(s: Sfx, loop: Boolean = false) {
        if (isPlaying(s.id)) { stop(s.id); return }
        if (poolStreams.size + players.size >= 10) (players.keys.firstOrNull() ?: poolStreams.keys.firstOrNull())?.let { stop(it) }
        if (usesPool(s, loop)) {
            val sample = loaded[s.id] ?: try { load(s).also { loaded[s.id] = it } } catch (e: Exception) { return }
            if (sample in ready) startPool(s, sample) else pendingPlay[sample] = s
        } else {
            startPlayer(s, loop)
        }
    }

    private fun load(s: Sfx): Int =
        if (s.isUserFile) pool.load(s.file, 1) else context.assets.openFd(s.file).use { fd -> pool.load(fd, 1) }

    /** Forget a cached sound (after the user deletes or re-records it). */
    fun forget(id: String) { stop(id); loaded.remove(id)?.let { pool.unload(it); ready.remove(it) } }

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
            MediaPlayer().apply {
                setAudioAttributes(attrs)
                if (s.isUserFile) setDataSource(s.file)
                else context.assets.openFd(s.file).use { fd -> setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
                isLooping = loop
                setVolume(volume, volume)
                prepare()
            }
        } catch (e: Exception) { null } ?: return
        mp.setOnCompletionListener { done ->
            if (players[s.id] === done) { players.remove(s.id); looping.remove(s.id) }
            release(done); onChange?.invoke()
        }
        mp.setOnErrorListener { bad, _, _ ->
            if (players[s.id] === bad) { players.remove(s.id); looping.remove(s.id) }
            release(bad); onChange?.invoke(); true
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
