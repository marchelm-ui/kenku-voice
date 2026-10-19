package com.kenku.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Streams audio to the speaker as it is produced, so a line starts playing as soon as
 * the first snippet is ready instead of after the whole line is made.
 */
class StreamPlayer(private val onFinished: () -> Unit) {
    private val queue = LinkedBlockingQueue<FloatArray>()
    private val end = FloatArray(0)
    @Volatile private var stopped = false
    @Volatile private var startAllowed = false
    private var frames = 0L
    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(AudioDsp.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setBufferSizeInBytes(maxOf(AudioDsp.RATE * 4 / 2,
            AudioTrack.getMinBufferSize(AudioDsp.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT) * 2))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    private val thread = Thread({
        try {
            while (!startAllowed && !stopped) Thread.sleep(10)
            if (!stopped) track.play()
            while (!stopped) {
                val buf = queue.poll(50, TimeUnit.MILLISECONDS) ?: continue
                if (buf === end) break
                var off = 0
                while (off < buf.size && !stopped) {
                    val n = track.write(buf, off, buf.size - off, AudioTrack.WRITE_BLOCKING)
                    if (n <= 0) break
                    off += n
                }
                frames += buf.size
            }
            // Let the last audio drain before reporting the line finished.
            val deadline = SystemClock.uptimeMillis() + frames * 1000 / AudioDsp.RATE + 1500
            while (!stopped && track.playbackHeadPosition < frames && SystemClock.uptimeMillis() < deadline) Thread.sleep(20)
        } catch (e: Exception) {
        } finally {
            try { track.stop() } catch (e: Exception) {}
            track.release()
            onFinished()
        }
    }, "kenku-playback")

    init { thread.start() }

    fun push(samples: FloatArray) { if (samples.isNotEmpty()) queue.put(samples) }
    fun allowStart() { startAllowed = true }
    fun finish() { startAllowed = true; queue.put(end) }
    fun stop() { stopped = true; startAllowed = true; try { track.pause(); track.flush() } catch (e: Exception) {} }
}

/**
 * Performs one kenku line: splits it naturally, gives each snippet a different stolen
 * voice and style, blends the snippets together and streams them while generating.
 * Runs on the engine thread.
 */
class Performer(private val engine: VoiceEngine) {

    data class Options(val speakers: List<Speaker>, val styles: List<Style>, val smooth: Boolean)
    data class Result(val parts: List<String>, val file: File)

    @Volatile private var cancelled = false
    @Volatile private var player: StreamPlayer? = null

    fun cancel() { cancelled = true; player?.stop() }

    /** Blocking. [onStarted] fires when sound starts; [onPlaybackDone] when it stops playing. */
    fun perform(
        text: String,
        opts: Options,
        out: File,
        onProgress: (done: Int, total: Int) -> Unit,
        onStarted: () -> Unit,
        onPlaybackDone: () -> Unit,
    ): Result {
        cancelled = false
        val chunks = Chunker.split(text)
        if (chunks.isEmpty()) throw IllegalArgumentException("Nothing to say")
        val speakers = opts.speakers.ifEmpty { throw IllegalStateException("No voices selected") }
        val styles = opts.styles.ifEmpty { listOf(Catalog.styles.first()) }
        val totalWords = chunks.sumOf { it.words.size }
        val xf = AudioDsp.RATE * (if (opts.smooth) 40 else 12) / 1000

        val p = StreamPlayer(onPlaybackDone).also { player = it }
        val full = ArrayList<FloatArray>()
        val parts = ArrayList<String>()
        var tail: FloatArray? = null
        var started = false
        var genMs = 0L
        var audioSec = 0.0
        var bufferedSec = 0.0
        var words = 0
        var last: Speaker? = null
        var lastGroup: String? = null
        var lastStyle: Style? = null

        fun emit(a: FloatArray) { if (a.isNotEmpty()) { p.push(a); full.add(a); bufferedSec += a.size / AudioDsp.RATE.toDouble() } }

        try {
            for ((i, c) in chunks.withIndex()) {
                if (cancelled) break
                onProgress(i, chunks.size)
                val speaker = pick(speakers, last, lastGroup)
                val style = if (styles.size > 1) styles.filter { it != lastStyle }.random() else styles[0]
                last = speaker; lastGroup = groupOf(speaker); lastStyle = style

                val t0 = SystemClock.uptimeMillis()
                val s = engine.speak(c.spoken, speaker, style, opts.smooth)
                genMs += SystemClock.uptimeMillis() - t0
                if (cancelled) break
                if (s.isEmpty()) continue
                parts.add(if (style.key == "natural") speaker.label else "${speaker.label}, ${style.label.lowercase()}")

                // Natural pause after punctuation the user typed; otherwise blend straight into the next voice.
                val prevEnd = if (i > 0) chunks[i - 1].display.trimEnd().lastOrNull() else null
                val gapMs = when (prevEnd) { '.', '!', '?' -> 260; ',', ';', ':' -> 110; else -> 0 }
                val prev = tail
                if (prev == null) {
                    tail = s.copyOfRange(maxOf(0, s.size - xf), s.size)
                    emit(s.copyOfRange(0, maxOf(0, s.size - xf)))
                } else if (gapMs > 0) {
                    for (k in prev.indices) prev[k] *= AudioDsp.fadeOutGain(k, prev.size)
                    emit(prev)
                    emit(FloatArray(AudioDsp.RATE * gapMs / 1000))
                    tail = s.copyOfRange(maxOf(0, s.size - xf), s.size)
                    emit(s.copyOfRange(0, maxOf(0, s.size - xf)))
                } else {
                    val n = minOf(prev.size, s.size / 2)
                    val mixed = FloatArray(n) { k -> prev[prev.size - n + k] * AudioDsp.fadeOutGain(k, n) + s[k] * AudioDsp.fadeInGain(k, n) }
                    emit(prev.copyOfRange(0, prev.size - n))
                    emit(mixed)
                    val bodyEnd = maxOf(n, s.size - xf)
                    emit(s.copyOfRange(n, bodyEnd))
                    tail = s.copyOfRange(bodyEnd, s.size)
                }

                audioSec += s.size / AudioDsp.RATE.toDouble()
                words += c.words.size
                if (!started) {
                    // Start as soon as the rest can be generated before playback catches up.
                    val remWords = totalWords - words
                    val estRemAudio = audioSec / words * remWords
                    val estRemGen = (genMs / 1000.0) / audioSec * estRemAudio * 1.15
                    if (remWords == 0 || bufferedSec + estRemAudio - estRemGen >= 0.3) {
                        started = true; p.allowStart(); onStarted()
                    }
                }
            }
            tail?.let { t -> for (k in t.indices) t[k] *= AudioDsp.fadeOutGain(k, t.size); emit(t) }
            if (!started && !cancelled) onStarted()
            p.finish()
        } catch (e: Throwable) {
            p.stop(); throw e
        }
        onProgress(chunks.size, chunks.size)
        if (cancelled || full.isEmpty()) throw InterruptedException("Stopped")
        val all = FloatArray(full.sumOf { it.size })
        var o = 0
        for (a in full) { a.copyInto(all, o); o += a.size }
        AudioDsp.writeWav(all, out)
        return Result(parts, out)
    }

    private fun groupOf(s: Speaker) = when (s) { is Speaker.Builtin -> s.accent.key; is Speaker.Cloned -> "clone:" + s.clone.id }

    /**
     * A different speaker every snippet. Accents (and each clone) get an equal chance,
     * so the 20 American voices don't crowd out the rest; then a voice within it.
     */
    private fun pick(pool: List<Speaker>, last: Speaker?, lastGroup: String?): Speaker {
        if (pool.size == 1) return pool[0]
        val groups = pool.groupBy { groupOf(it) }
        val keys = groups.keys.filter { it != lastGroup }.ifEmpty { groups.keys.toList() }
        val members = groups.getValue(keys[Random.nextInt(keys.size)])
        val choices = members.filter { it != last }.ifEmpty { members }
        return choices[Random.nextInt(choices.size)]
    }
}
