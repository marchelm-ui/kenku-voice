package com.kenku.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig
import java.io.File
import java.io.FileOutputStream
import kotlin.random.Random

/** One built-in Kokoro voice: its speaker id in voices.bin and a friendly name. */
data class KVoice(val sid: Int, val id: String, val name: String)

/** A family of built-in voices sharing one accent. */
data class Accent(val key: String, val label: String, val detail: String, val voices: List<KVoice>)

/**
 * A way of speaking. Pitch is applied after synthesis by resampling, with the
 * generation speed adjusted so the final speed still matches [rate].
 */
data class Style(val key: String, val label: String, val pitch: Float, val rate: Float)

/** Who speaks one snippet: a built-in voice or one of the user's clones. */
sealed class Speaker {
    abstract val label: String
    data class Builtin(val voice: KVoice, val accent: Accent) : Speaker() {
        override val label get() = "${voice.name.substringBefore(" (")} · ${accent.label.removeSuffix(" accent")}"
    }
    data class Cloned(val clone: Clone) : Speaker() {
        override val label get() = "${clone.name} (clone)"
    }
}

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
        Style("giant", "Giant", 0.8f, 0.92f),
        Style("menacing", "Menacing", 0.87f, 0.88f),
        Style("gravelly", "Old & gravelly", 0.92f, 0.84f),
        Style("drawl", "Slow drawl", 1.0f, 0.8f),
        Style("bright", "Bright", 1.08f, 1.04f),
        Style("excited", "Excited", 1.1f, 1.18f),
        Style("hasty", "Fast talker", 1.0f, 1.3f),
        Style("child", "Child", 1.26f, 1.04f),
        Style("squeaky", "Squeaky", 1.45f, 1.08f),
    )
}

/**
 * The two on-device voice models, both run by sherpa-onnx:
 *  - Kokoro: 54 built-in human-sounding voices.
 *  - Pocket TTS: speaks in any voice from a few seconds of example audio (cloning).
 * Every call must happen on one background thread (the models are not thread-safe).
 */
class VoiceEngine(private val context: Context) {

    private var kokoro: OfflineTts? = null
    private var pocket: OfflineTts? = null
    private var pocketFailed = false
    val isLoaded get() = kokoro != null

    /** Blocking. Copies pronunciation data on first launch, then loads the built-in voices. */
    fun load() {
        if (kokoro != null) return
        val dataDir = prepareEspeakData()
        val d = KOKORO_DIR
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = "$d/model.onnx",
                    voices = "$d/voices.bin",
                    tokens = "$d/tokens.txt",
                    dataDir = dataDir,
                    lexicon = "$d/lexicon-us-en.txt,$d/lexicon-zh.txt",
                ),
                numThreads = threads(),
                debug = false,
                provider = "cpu",
            ),
        )
        kokoro = OfflineTts(assetManager = context.assets, config = config)
    }

    /**
     * Blocking. Loads the cloning model the first time a clone is used. Its files are copied
     * from the app package to private storage once, so the engine reads them as plain files.
     */
    fun loadPocket(): Boolean {
        if (pocket != null) return true
        if (pocketFailed) return false
        val d = try { preparePocketFiles() } catch (e: Throwable) { pocketFailed = true; return false }
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                pocket = OfflineTtsPocketModelConfig(
                    lmFlow = "$d/lm_flow.int8.onnx",
                    lmMain = "$d/lm_main.int8.onnx",
                    encoder = "$d/encoder.onnx",
                    decoder = "$d/decoder.int8.onnx",
                    textConditioner = "$d/text_conditioner.onnx",
                    vocabJson = "$d/vocab.json",
                    tokenScoresJson = "$d/token_scores.json",
                ),
                numThreads = threads(),
                debug = false,
                provider = "cpu",
            ),
        )
        return try {
            pocket = OfflineTts(assetManager = null, config = config); true
        } catch (e: Throwable) {
            pocketFailed = true; false
        }
    }

    private val pocketFiles = listOf("lm_flow.int8.onnx", "lm_main.int8.onnx", "encoder.onnx", "decoder.int8.onnx",
        "text_conditioner.onnx", "vocab.json", "token_scores.json")

    private fun preparePocketFiles(): String {
        val dir = File(context.filesDir, POCKET_DIR)
        val marker = File(dir, ".copied-$POCKET_VERSION")
        if (!marker.exists()) {
            dir.deleteRecursively(); dir.mkdirs()
            for (f in pocketFiles) {
                val tmp = File(dir, "$f.tmp")
                context.assets.open("$POCKET_DIR/$f").use { input -> FileOutputStream(tmp).use { input.copyTo(it, 1 shl 20) } }
                if (!tmp.renameTo(File(dir, f))) throw IllegalStateException("Couldn't store $f")
            }
            marker.writeText("ok")
        }
        return dir.absolutePath
    }

    private fun threads(): Int = Runtime.getRuntime().availableProcessors().let { if (it >= 8) 4 else 2 }

    /** espeak-ng data must live on disk; copy it out of the app once per data version. */
    private fun prepareEspeakData(): String {
        val root = File(context.filesDir, KOKORO_DIR)
        val target = File(root, "espeak-ng-data")
        val marker = File(root, ".copied-$DATA_VERSION")
        if (!marker.exists()) {
            root.deleteRecursively()
            copyAssetDir("$KOKORO_DIR/espeak-ng-data", target)
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

    /**
     * Synthesizes one snippet and returns it cleaned up for joining: trimmed, pitch/speed
     * styled and loudness-matched, at [AudioDsp.RATE].
     */
    fun speak(text: String, speaker: Speaker, style: Style, smooth: Boolean): FloatArray {
        // When blending smoothly, styles lean toward the natural voice so switches stay subtle.
        val pitch = (if (smooth) 1f + (style.pitch - 1f) * 0.75f else style.pitch) * Random.nextDouble(0.97, 1.03).toFloat()
        val rate = if (smooth) 1f + (style.rate - 1f) * 0.6f else style.rate
        val speed = (rate / pitch).coerceIn(0.55f, 1.9f)
        val raw: FloatArray = when (speaker) {
            is Speaker.Builtin -> {
                val tts = kokoro ?: throw IllegalStateException("Voices not loaded")
                val a = tts.generate(text = text, sid = speaker.voice.sid, speed = speed)
                AudioDsp.resample(a.samples, a.sampleRate)
            }
            is Speaker.Cloned -> {
                val tts = pocket ?: if (loadPocket()) pocket!! else throw IllegalStateException("Cloning unavailable")
                val ref = speaker.clone.samples()
                val cfg = GenerationConfig(speed = speed, referenceAudio = ref, referenceSampleRate = AudioDsp.RATE, numSteps = 5)
                val a = tts.generateWithConfig(text, cfg)
                AudioDsp.resample(a.samples, a.sampleRate)
            }
        }
        val shaped = AudioDsp.pitchShift(AudioDsp.trim(raw), pitch)
        return AudioDsp.fadeEdges(AudioDsp.matchLoudness(shaped))
    }

    fun release() {
        try { kokoro?.release() } catch (e: Throwable) {}
        try { pocket?.release() } catch (e: Throwable) {}
        kokoro = null; pocket = null
    }

    companion object {
        const val KOKORO_DIR = "kokoro"
        const val POCKET_DIR = "pocket"
        const val DATA_VERSION = "v1_0-fp32-1"
        const val POCKET_VERSION = "int8-2026-01-26"

        @Volatile private var shared: VoiceEngine? = null

        /**
         * One engine for the whole app process, so rotating the phone or switching between
         * light and dark mode never reloads the 300 MB voice model.
         */
        fun get(context: Context): VoiceEngine = shared ?: synchronized(this) {
            shared ?: VoiceEngine(context.applicationContext).also { shared = it }
        }

        /** The single thread every model call runs on, shared like the engine. */
        val thread: java.util.concurrent.ExecutorService by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }
    }
}
