package com.kenku.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.random.Random

/** One Kokoro voice: its speaker id in voices.bin and a friendly name. */
data class KVoice(val sid: Int, val id: String, val name: String)

/** A family of voices sharing one accent. */
data class Accent(val key: String, val label: String, val detail: String, val voices: List<KVoice>)

/**
 * A way of speaking. Pitch is applied after synthesis by resampling, with the
 * generation speed adjusted so the final speed still matches [rate].
 */
data class Style(val key: String, val label: String, val pitch: Float, val rate: Float)

object Catalog {
    // Speaker ids for kokoro-multi-lang-v1_0 (voices.bin order).
    private val ids = listOf(
        "af_alloy", "af_aoede", "af_bella", "af_heart", "af_jessica", "af_kore", "af_nicole", "af_nova",
        "af_river", "af_sarah", "af_sky", "am_adam", "am_echo", "am_eric", "am_fenrir", "am_liam",
        "am_michael", "am_onyx", "am_puck", "am_santa", "bf_alice", "bf_emma", "bf_isabella", "bf_lily",
        "bm_daniel", "bm_fable", "bm_george", "bm_lewis", "ef_dora", "em_alex", "ff_siwis", "hf_alpha",
        "hf_beta", "hm_omega", "hm_psi", "if_sara", "im_nicola", "jf_alpha", "jf_gongitsune", "jf_nezumi",
        "jf_tebukuro", "jm_kumo", "pf_dora", "pm_alex", "pm_santa", "zf_xiaobei", "zf_xiaoni", "zf_xiaoxiao",
        "zf_xiaoyi", "zm_yunjian", "zm_yunxi", "zm_yunxia", "zm_yunyang", "em_santa",
    )

    val voices: List<KVoice> = ids.mapIndexed { sid, id ->
        val raw = id.substringAfter('_')
        val gender = if (id[1] == 'f') "woman" else "man"
        KVoice(sid, id, raw.replaceFirstChar { it.uppercase() } + " ($gender)")
    }

    private fun group(prefix: Char) = voices.filter { it.id[0] == prefix }

    val accents = listOf(
        Accent("us", "American", "Native American English", group('a')),
        Accent("gb", "British", "Native British English", group('b')),
        Accent("in", "Indian accent", "English with a Hindi voice", group('h')),
        Accent("zh", "Chinese accent", "English with a Mandarin voice", group('z')),
        Accent("ja", "Japanese accent", "English with a Japanese voice", group('j')),
        Accent("es", "Spanish accent", "English with a Spanish voice", group('e')),
        Accent("fr", "French accent", "English with a French voice", group('f')),
        Accent("it", "Italian accent", "English with an Italian voice", group('i')),
        Accent("br", "Brazilian accent", "English with a Brazilian Portuguese voice", group('p')),
    )

    val styles = listOf(
        Style("natural", "Natural", 1.0f, 1.0f),
        Style("giant", "Giant", 0.78f, 0.9f),
        Style("menacing", "Menacing", 0.86f, 0.85f),
        Style("gravelly", "Old & gravelly", 0.92f, 0.8f),
        Style("drawl", "Slow drawl", 1.0f, 0.74f),
        Style("bright", "Bright", 1.1f, 1.05f),
        Style("excited", "Excited", 1.1f, 1.28f),
        Style("hasty", "Fast talker", 1.0f, 1.45f),
        Style("child", "Child", 1.28f, 1.05f),
        Style("squeaky", "Squeaky", 1.5f, 1.1f),
    )
}

/**
 * Kokoro, a free neural voice model, running fully on the phone through sherpa-onnx.
 * All calls must happen on one background thread (the model is not thread-safe).
 */
class KokoroEngine(private val context: Context) {

    private var tts: OfflineTts? = null
    private var rate = 24000

    val isLoaded get() = tts != null

    /** Blocking. Copies the pronunciation data on first launch, then loads the model. */
    fun load() {
        if (tts != null) return
        val dataDir = prepareEspeakData()
        val dir = MODEL_DIR
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = "$dir/model.int8.onnx",
                    voices = "$dir/voices.bin",
                    tokens = "$dir/tokens.txt",
                    dataDir = dataDir,
                    lexicon = "$dir/lexicon-us-en.txt,$dir/lexicon-zh.txt",
                ),
                numThreads = threads(),
                debug = false,
                provider = "cpu",
            ),
        )
        val engine = OfflineTts(assetManager = context.assets, config = config)
        rate = engine.sampleRate()
        tts = engine
    }

    private fun threads(): Int = Runtime.getRuntime().availableProcessors().let { if (it >= 8) 4 else 2 }

    /** espeak-ng data must live on disk; copy it out of the app once per app version. */
    private fun prepareEspeakData(): String {
        val target = File(context.filesDir, "$MODEL_DIR/espeak-ng-data")
        val marker = File(context.filesDir, "$MODEL_DIR/.copied-$DATA_VERSION")
        if (!marker.exists()) {
            File(context.filesDir, MODEL_DIR).deleteRecursively()
            copyAssetDir("$MODEL_DIR/espeak-ng-data", target)
            marker.parentFile?.mkdirs()
            marker.writeText("ok")
        }
        return target.absolutePath
    }

    private fun copyAssetDir(assetPath: String, dest: File) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input -> FileOutputStream(dest).use { input.copyTo(it) } }
        } else {
            dest.mkdirs()
            children.forEach { copyAssetDir("$assetPath/$it", File(dest, it)) }
        }
    }

    data class Part(val text: String, val label: String)
    data class Result(val file: File, val parts: List<Part>)

    fun chunk(text: String): List<String> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val out = mutableListOf<String>()
        var i = 0
        while (i < words.size) {
            val n = if (Random.nextBoolean()) 2 else 3
            out.add(words.subList(i, minOf(i + n, words.size)).joinToString(" "))
            i += n
        }
        return out
    }

    /** Synthesizes one piece of text in one voice and style, at the engine's sample rate. */
    private fun speak(text: String, voice: KVoice, style: Style, jitter: Boolean): FloatArray {
        val engine = tts ?: throw IllegalStateException("Voices not loaded")
        val j = if (jitter) Random.nextDouble(0.95, 1.05).toFloat() else 1f
        val pitch = (style.pitch * j).coerceIn(0.6f, 1.7f)
        // Resampling by `pitch` also speeds playback up by `pitch`, so generate slower to compensate.
        val speed = (style.rate / pitch).coerceIn(0.5f, 2.0f)
        val audio = engine.generate(text = text, sid = voice.sid, speed = speed)
        return Audio.pitchShift(audio.samples, pitch)
    }

    /** Blocking: builds the full kenku line as one WAV file. */
    fun render(
        text: String,
        accents: List<Accent>,
        styles: List<Style>,
        out: File,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Result {
        val pool = accents.filter { it.voices.isNotEmpty() }.ifEmpty { Catalog.accents }
        val stylePool = styles.ifEmpty { listOf(Catalog.styles.first()) }
        val chunks = chunk(text)
        val pieces = mutableListOf<FloatArray>()
        val parts = mutableListOf<Part>()
        var lastAccent: Accent? = null
        var lastVoice: KVoice? = null

        chunks.forEachIndexed { i, piece ->
            onProgress(i, chunks.size)
            // A new accent each chunk when possible, and never the same voice twice in a row.
            var a: Accent
            do { a = pool.random() } while (pool.size > 1 && a == lastAccent)
            var v: KVoice
            do { v = a.voices.random() } while (a.voices.size > 1 && v == lastVoice)
            val s = stylePool.random()
            lastAccent = a; lastVoice = v

            val samples = speak(piece, v, s, jitter = true)
            if (samples.isNotEmpty()) {
                pieces.add(Audio.trim(samples))
                pieces.add(FloatArray(rate * 60 / 1000))
                val who = "${v.name.substringBefore(" (")} · ${a.label.removeSuffix(" accent")}"
                parts.add(Part(piece, if (s.key == "natural") who else "$who, ${s.label.lowercase()}"))
            }
        }
        onProgress(chunks.size, chunks.size)
        if (parts.isEmpty()) throw IllegalStateException("No audio was produced")
        Audio.writeWav(pieces, rate, out)
        return Result(out, parts)
    }

    /** Blocking: a short sample of one accent or style, written to [out]. */
    fun preview(accent: Accent, style: Style, text: String, out: File): File {
        val v = accent.voices.random()
        Audio.writeWav(listOf(speak(text, v, style, jitter = false)), rate, out)
        return out
    }

    fun release() { try { tts?.release() } catch (e: Throwable) {}; tts = null }

    companion object {
        const val MODEL_DIR = "kokoro"
        const val DATA_VERSION = "v1_0-1"
    }
}

/** Small audio helpers: pitch shifting, trimming silence and writing WAV files. */
object Audio {
    fun pitchShift(src: FloatArray, factor: Float): FloatArray {
        if (src.isEmpty() || factor == 1f) return src
        val n = (src.size / factor).toInt().coerceAtLeast(1)
        return FloatArray(n) { i ->
            val x = i * factor
            val a = x.toInt().coerceAtMost(src.size - 1)
            val b = (a + 1).coerceAtMost(src.size - 1)
            val t = x - a
            src[a] * (1 - t) + src[b] * t
        }
    }

    /** Removes leading/trailing near-silence so chunks flow like one speaker interrupting another. */
    fun trim(src: FloatArray, threshold: Float = 0.01f): FloatArray {
        var s = 0
        while (s < src.size && kotlin.math.abs(src[s]) < threshold) s++
        var e = src.size - 1
        while (e > s && kotlin.math.abs(src[e]) < threshold) e--
        if (s >= e) return src
        val pad = 480
        return src.copyOfRange((s - pad).coerceAtLeast(0), (e + pad).coerceAtMost(src.size - 1) + 1)
    }

    fun writeWav(pieces: List<FloatArray>, rate: Int, out: File) {
        val total = pieces.sumOf { it.size }
        if (total == 0) throw IllegalStateException("Empty audio")
        val dataBytes = total * 2
        val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII)); buf.putInt(36 + dataBytes)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII)); buf.putInt(16); buf.putShort(1); buf.putShort(1)
        buf.putInt(rate); buf.putInt(rate * 2); buf.putShort(2); buf.putShort(16)
        buf.put("data".toByteArray(Charsets.US_ASCII)); buf.putInt(dataBytes)
        pieces.forEach { arr ->
            arr.forEach { v -> buf.putShort((v.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()) }
        }
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, out.name + ".tmp")
        FileOutputStream(tmp).use { it.write(buf.array()) }
        if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
    }
}
