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

/**
 * Turns a line of text into one WAV file where every 2–3 words are spoken
 * by a different voice, accent, pitch and speed — the kenku's stolen voices.
 */
class KenkuEngine(context: Context, private val onReady: (Boolean) -> Unit) {

    private val latches = ConcurrentHashMap<String, CountDownLatch>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private var ready = false
    private val tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            onReady(ready)
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {}
            override fun onDone(id: String) { latches[id]?.countDown() }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String) { failed.add(id); latches[id]?.countDown() }
            override fun onError(id: String, code: Int) { failed.add(id); latches[id]?.countDown() }
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

    /** Voices installed on the phone: every English accent plus one voice per other language. */
    private fun voicePool(): List<Voice> {
        val all = try { tts.voices?.toList() ?: emptyList() } catch (e: Exception) { emptyList() }
        val usable = all.filter {
            !it.isNetworkConnectionRequired &&
                !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        }
        val english = usable.filter { it.locale.language == "en" }
        val foreign = usable.filter { it.locale.language != "en" }
            .groupBy { it.locale.language }
            .map { it.value.random() }
            .shuffled()
            .take(10)
        return english + foreign
    }

    private fun label(v: Voice?): String {
        if (v == null) return "default voice"
        val country = v.locale.getDisplayCountry(Locale.ENGLISH)
        val lang = v.locale.getDisplayLanguage(Locale.ENGLISH)
        return if (v.locale.language == "en" && country.isNotBlank()) "$country English" else lang.ifBlank { v.name }
    }

    /** Blocking: call off the main thread. */
    fun render(text: String, outFile: File, workDir: File): Result {
        check(ready) { "Voice engine not ready" }
        workDir.mkdirs()
        val parts = chunk(text)
        val pool = voicePool()
        var last: Voice? = null
        val clips = mutableListOf<File>()
        val labels = mutableListOf<Part>()

        parts.forEachIndexed { i, chunkText ->
            var v: Voice? = null
            if (pool.isNotEmpty()) {
                do { v = pool.random() } while (pool.size > 1 && v == last)
            }
            last = v
            v?.let { tts.setVoice(it) }
            tts.setPitch(Random.nextDouble(0.55, 1.75).toFloat())
            tts.setSpeechRate(Random.nextDouble(0.85, 1.2).toFloat())

            val id = "kenku_${System.nanoTime()}_$i"
            val clip = File(workDir, "$id.wav")
            val latch = CountDownLatch(1)
            latches[id] = latch
            val res = tts.synthesizeToFile(chunkText, Bundle(), clip, id)
            if (res == TextToSpeech.SUCCESS) latch.await(20, TimeUnit.SECONDS)
            latches.remove(id)
            if (res == TextToSpeech.SUCCESS && !failed.remove(id) && clip.length() > 44) {
                clips.add(clip)
                labels.add(Part(chunkText, label(v)))
            }
        }

        if (clips.isEmpty()) throw IllegalStateException("No audio was produced")
        Wav.join(clips, outFile, gapMs = 70)
        clips.forEach { it.delete() }
        return Result(outFile, labels)
    }

    fun shutdown() = tts.shutdown()
}

/** Minimal WAV reading, resampling and joining. */
object Wav {
    private const val OUT_RATE = 24000

    private class Pcm(val rate: Int, val samples: ShortArray)

    private fun read(file: File): Pcm? {
        val b = file.readBytes()
        if (b.size < 44 || String(b, 0, 4) != "RIFF") return null
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var channels = 1; var rate = OUT_RATE; var bits = 16
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4)
            var size = bb.getInt(pos + 4)
            val start = pos + 8
            if (id == "fmt ") {
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
                    out[f] = if (bits == 8) (((b[o].toInt() and 0xFF) - 128) shl 8).toShort()
                    else bb.getShort(o)
                }
                return Pcm(rate, out)
            }
            pos = start + size + (size and 1)
        }
        return null
    }

    private fun resample(p: Pcm): ShortArray {
        if (p.rate == OUT_RATE || p.rate <= 0) return p.samples
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
        val dataBytes = total * 2
        val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()); buf.putInt(36 + dataBytes); buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()); buf.putInt(16); buf.putShort(1); buf.putShort(1)
        buf.putInt(OUT_RATE); buf.putInt(OUT_RATE * 2); buf.putShort(2); buf.putShort(16)
        buf.put("data".toByteArray()); buf.putInt(dataBytes)
        pieces.forEach { arr -> arr.forEach { buf.putShort(it) } }
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { it.write(buf.array()) }
    }
}
