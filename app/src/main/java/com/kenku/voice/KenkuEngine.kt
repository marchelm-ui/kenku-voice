package com.kenku.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** A way of speaking: pitch and speed. */
data class Style(val key: String, val label: String, val pitch: Float, val rate: Float)

/** One accent, made of every installed phone voice for that language/region. */
data class Accent(
    val key: String,
    val label: String,
    val detail: String,
    val native: Boolean,
    val installed: Boolean,
    val voices: List<Voice>,
)

object Catalog {
    val styles = listOf(
        Style("natural", "Natural", 1.0f, 1.0f),
        Style("giant", "Giant", 0.5f, 0.82f),
        Style("menacing", "Menacing", 0.62f, 0.78f),
        Style("gravelly", "Old & gravelly", 0.72f, 0.8f),
        Style("calm", "Slow drawl", 0.92f, 0.7f),
        Style("bright", "Bright", 1.25f, 1.05f),
        Style("excited", "Excited", 1.4f, 1.3f),
        Style("hasty", "Fast talker", 1.08f, 1.55f),
        Style("child", "Child", 1.7f, 1.1f),
        Style("squeaky", "Squeaky", 2.0f, 1.15f),
    )

    private val englishRegions = mapOf(
        "US" to "American", "GB" to "British", "AU" to "Australian", "IN" to "Indian English",
        "NG" to "Nigerian English", "CA" to "Canadian", "IE" to "Irish", "ZA" to "South African",
        "NZ" to "New Zealand", "SG" to "Singaporean English", "PH" to "Filipino English",
        "KE" to "Kenyan English", "GH" to "Ghanaian English", "PK" to "Pakistani English",
        "JM" to "Jamaican English", "SCOTLAND" to "Scottish",
    )

    // Accent names for voices of other languages reading English text.
    private val languageAccents = mapOf(
        "hi" to "Indian (Hindi)", "bn" to "Bengali", "ta" to "Tamil", "te" to "Telugu",
        "mr" to "Marathi", "gu" to "Gujarati", "kn" to "Kannada", "ml" to "Malayalam",
        "pa" to "Punjabi", "ur" to "Pakistani (Urdu)", "ne" to "Nepali", "si" to "Sri Lankan",
        "zh" to "Chinese (Mandarin)", "cmn" to "Chinese (Mandarin)", "yue" to "Cantonese",
        "ja" to "Japanese", "ko" to "Korean", "vi" to "Vietnamese", "th" to "Thai",
        "id" to "Indonesian", "ms" to "Malaysian", "fil" to "Filipino", "tl" to "Filipino",
        "km" to "Cambodian", "jv" to "Javanese", "su" to "Sundanese", "my" to "Burmese",
        "fr" to "French", "de" to "German", "es" to "Spanish", "it" to "Italian",
        "pt" to "Portuguese", "ru" to "Russian", "uk" to "Ukrainian", "pl" to "Polish",
        "nl" to "Dutch", "sv" to "Swedish", "nb" to "Norwegian", "no" to "Norwegian",
        "da" to "Danish", "fi" to "Finnish", "el" to "Greek", "tr" to "Turkish",
        "ar" to "Arabic", "he" to "Israeli (Hebrew)", "iw" to "Israeli (Hebrew)", "fa" to "Persian",
        "cs" to "Czech", "sk" to "Slovak", "hu" to "Hungarian", "ro" to "Romanian",
        "bg" to "Bulgarian", "hr" to "Croatian", "sr" to "Serbian", "lt" to "Lithuanian",
        "lv" to "Latvian", "et" to "Estonian", "is" to "Icelandic", "ca" to "Catalan",
        "eu" to "Basque", "gl" to "Galician", "cy" to "Welsh", "sw" to "Swahili",
        "yo" to "Yoruba", "af" to "Afrikaans", "am" to "Ethiopian (Amharic)", "zu" to "Zulu",
        "xh" to "Xhosa", "sq" to "Albanian", "bs" to "Bosnian", "mk" to "Macedonian",
        "sl" to "Slovenian", "hy" to "Armenian", "ka" to "Georgian", "kk" to "Kazakh",
        "uz" to "Uzbek", "mn" to "Mongolian", "lo" to "Lao",
    )

    private val regionTweaks = mapOf(
        "es-US" to "Latin American (Spanish)", "es-MX" to "Mexican (Spanish)",
        "pt-BR" to "Brazilian (Portuguese)", "pt-PT" to "Portuguese (Portugal)",
        "fr-CA" to "French Canadian", "zh-TW" to "Taiwanese (Mandarin)", "zh-HK" to "Hong Kong",
        "nl-BE" to "Belgian (Flemish)", "de-AT" to "Austrian", "de-CH" to "Swiss German",
        "ar-EG" to "Egyptian (Arabic)", "ar-SA" to "Saudi (Arabic)",
    )

    fun accentKey(l: Locale): String = if (l.language == "en") "en-${l.country}" else {
        val k = "${l.language}-${l.country}"
        if (regionTweaks.containsKey(k)) k else l.language
    }

    fun accentLabel(l: Locale): Pair<String, String> {
        if (l.language == "en") {
            val name = englishRegions[l.country.uppercase()]
                ?: (l.getDisplayCountry(Locale.ENGLISH).ifBlank { "Other" } + " English")
            return name to "Native English"
        }
        val tweak = regionTweaks["${l.language}-${l.country}"]
        val name = tweak ?: languageAccents[l.language]
            ?: l.getDisplayLanguage(Locale.ENGLISH).ifBlank { l.language }
        return "$name accent" to "English spoken by a ${l.getDisplayLanguage(Locale.ENGLISH)} voice"
    }
}

/**
 * Turns a line of text into one WAV file where every 2–3 words are spoken by a
 * different accent and speaking style — the kenku's stolen voices.
 */
class KenkuEngine(context: Context, private val onReady: (Boolean) -> Unit) {

    private val latches = ConcurrentHashMap<String, CountDownLatch>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var ready = false
    private val tts: TextToSpeech

    init {
        val cb = TextToSpeech.OnInitListener { status ->
            ready = status == TextToSpeech.SUCCESS
            onReady(ready)
        }
        // Prefer Google's engine (built into Pixel phones); falls back to the phone's default.
        tts = TextToSpeech(context.applicationContext, cb, GOOGLE_TTS)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {}
            override fun onDone(id: String) { latches[id]?.countDown() }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String) { failed.add(id); latches[id]?.countDown() }
            override fun onError(id: String, code: Int) { failed.add(id); latches[id]?.countDown() }
            override fun onStop(id: String, interrupted: Boolean) { failed.add(id); latches[id]?.countDown() }
        })
    }

    val isReady get() = ready

    data class Part(val text: String, val voiceLabel: String)
    data class Result(val file: File, val parts: List<Part>)

    /** Splits text into random chunks of 2 or 3 words. */
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

    /** Every accent the engine knows, installed ones first, native English first. */
    fun accents(): List<Accent> {
        val all = try { tts.voices?.toList() ?: emptyList() } catch (e: Exception) { emptyList() }
        return all
            .filter { !it.isNetworkConnectionRequired }
            .groupBy { Catalog.accentKey(it.locale) }
            .map { (key, vs) ->
                val (label, detail) = Catalog.accentLabel(vs.first().locale)
                val installed = vs.filter { !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                Accent(key, label, detail, vs.first().locale.language == "en", installed.isNotEmpty(), installed)
            }
            .sortedWith(compareByDescending<Accent> { it.installed }.thenByDescending { it.native }.thenBy { it.label })
    }

    private fun apply(v: Voice?, style: Style, jitter: Boolean) {
        v?.let { tts.setVoice(it) }
        val j = if (jitter) Random.nextDouble(0.92, 1.08).toFloat() else 1f
        tts.setPitch((style.pitch * j).coerceIn(0.3f, 2.2f))
        tts.setSpeechRate((style.rate * j).coerceIn(0.5f, 2.0f))
    }

    /** Speaks a sample right away so the user can hear an accent or style. */
    fun preview(accent: Accent?, style: Style, text: String) {
        if (!ready) return
        apply(accent?.voices?.randomOrNull(), style, jitter = false)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "preview_${System.nanoTime()}")
    }

    fun stopPreview() { if (ready) tts.stop() }

    /** Blocking: call off the main thread. */
    fun render(text: String, accents: List<Accent>, styles: List<Style>, outFile: File, workDir: File): Result {
        check(ready) { "Voice engine not ready" }
        workDir.mkdirs()
        val pool = accents.filter { it.installed && it.voices.isNotEmpty() }
        val stylePool = styles.ifEmpty { listOf(Catalog.styles.first()) }
        var lastAccent: Accent? = null
        var lastStyle: Style? = null
        val clips = mutableListOf<File>()
        val labels = mutableListOf<Part>()

        chunk(text).forEachIndexed { i, chunkText ->
            // Pick a different accent and style from the previous chunk whenever possible.
            var a: Accent? = null
            if (pool.isNotEmpty()) { do { a = pool.random() } while (pool.size > 1 && a == lastAccent) }
            var s: Style
            do { s = stylePool.random() } while (stylePool.size > 1 && s == lastStyle && pool.size <= 1)
            lastAccent = a; lastStyle = s

            apply(a?.voices?.random(), s, jitter = true)
            val id = "kenku_${System.nanoTime()}_$i"
            val clip = File(workDir, "$id.wav")
            val latch = CountDownLatch(1)
            latches[id] = latch
            val res = tts.synthesizeToFile(chunkText, Bundle(), clip, id)
            if (res == TextToSpeech.SUCCESS) latch.await(20, TimeUnit.SECONDS)
            latches.remove(id)
            val ok = res == TextToSpeech.SUCCESS && !failed.remove(id) && clip.length() > 44
            if (ok) {
                clips.add(clip)
                val who = a?.label?.removeSuffix(" accent") ?: "Default voice"
                labels.add(Part(chunkText, if (s.key == "natural") who else "$who, ${s.label.lowercase()}"))
            } else clip.delete()
        }

        if (clips.isEmpty()) throw IllegalStateException("No audio was produced")
        Wav.join(clips, outFile, gapMs = 70)
        clips.forEach { it.delete() }
        return Result(outFile, labels)
    }

    fun shutdown() { try { tts.stop(); tts.shutdown() } catch (e: Exception) {} }

    companion object { const val GOOGLE_TTS = "com.google.android.tts" }
}

/** Minimal WAV reading, resampling and joining. */
object Wav {
    private const val OUT_RATE = 24000

    private class Pcm(val rate: Int, val samples: ShortArray)

    private fun read(file: File): Pcm? {
        val b = try { file.readBytes() } catch (e: Exception) { return null }
        if (b.size < 44 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF") return null
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var channels = 1; var rate = OUT_RATE; var bits = 16
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.US_ASCII)
            var size = bb.getInt(pos + 4)
            val start = pos + 8
            if (id == "fmt " && start + 16 <= b.size) {
                channels = bb.getShort(start + 2).toInt().coerceAtLeast(1)
                rate = bb.getInt(start + 4)
                bits = bb.getShort(start + 14).toInt()
            } else if (id == "data") {
                if (size <= 0 || start + size > b.size) size = b.size - start
                val frameBytes = ((bits / 8) * channels).coerceAtLeast(1)
                val frames = size / frameBytes
                val out = ShortArray(frames)
                for (f in 0 until frames) {
                    val o = start + f * frameBytes
                    out[f] = when {
                        bits == 8 -> (((b[o].toInt() and 0xFF) - 128) shl 8).toShort()
                        o + 1 < b.size -> bb.getShort(o)
                        else -> 0
                    }
                }
                return Pcm(rate, out)
            }
            if (size < 0) return null
            pos = start + size + (size and 1)
        }
        return null
    }

    private fun resample(p: Pcm): ShortArray {
        if (p.rate == OUT_RATE || p.rate <= 0 || p.samples.isEmpty()) return p.samples
        val ratio = p.rate.toDouble() / OUT_RATE
        val n = (p.samples.size / ratio).toInt()
        return ShortArray(n) { i ->
            val x = i * ratio
            val a = x.toInt().coerceAtMost(p.samples.size - 1)
            val c = (a + 1).coerceAtMost(p.samples.size - 1)
            val t = x - a
            (p.samples[a] * (1 - t) + p.samples[c] * t).toInt().toShort()
        }
    }

    fun join(files: List<File>, out: File, gapMs: Int) {
        val gap = ShortArray(OUT_RATE * gapMs / 1000)
        val pieces = mutableListOf<ShortArray>()
        files.forEach { f -> read(f)?.let { pieces.add(resample(it)); pieces.add(gap) } }
        val total = pieces.sumOf { it.size }
        if (total == 0) throw IllegalStateException("Empty audio")
        val dataBytes = total * 2
        val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII)); buf.putInt(36 + dataBytes); buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII)); buf.putInt(16); buf.putShort(1); buf.putShort(1)
        buf.putInt(OUT_RATE); buf.putInt(OUT_RATE * 2); buf.putShort(2); buf.putShort(16)
        buf.put("data".toByteArray(Charsets.US_ASCII)); buf.putInt(dataBytes)
        pieces.forEach { arr -> arr.forEach { buf.putShort(it) } }
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, out.name + ".tmp")
        FileOutputStream(tmp).use { it.write(buf.array()) }
        if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
    }
}
