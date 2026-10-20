package com.kenku.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Streams audio to the speaker as it is produced, so a line starts playing as soon as
 * the first snippet is ready instead of after the whole line is made.
 */
class StreamPlayer(private val onFinished: () -> Unit) : AudioSink {
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

    override fun push(samples: FloatArray) { if (samples.isNotEmpty()) queue.put(samples) }
    override fun allowStart() { startAllowed = true }
    override fun finish() { startAllowed = true; queue.put(end) }
    override fun stop() { stopped = true; startAllowed = true; try { track.pause(); track.flush() } catch (e: Exception) {} }
}

/** What the Performer needs from a voice engine; [VoiceEngine] is the real one. */
interface TtsSource {
    fun speak(text: String, speaker: Speaker, style: Style, smooth: Boolean, wild: Boolean = false): FloatArray
}

/** Where performed audio goes; [StreamPlayer] is the real one. */
interface AudioSink {
    fun push(samples: FloatArray)
    fun allowStart()
    fun finish()
    fun stop()
}

/**
 * Performs one kenku line: splits it naturally, gives each snippet a different stolen
 * voice and style, blends the snippets together and streams them while generating.
 * Runs on the engine thread. The engine, sink and clock are seams, so the whole
 * orchestration — ordering, gaps, clone fallback, cancellation — runs under JVM tests.
 */
class Performer(
    private val engine: TtsSource,
    private val sinkFactory: (onFinished: () -> Unit) -> AudioSink = { StreamPlayer(it) },
    private val now: () -> Long = { SystemClock.uptimeMillis() },
) {

    data class Options(
        val speakers: List<Speaker>,
        val styles: List<Style>,
        val smooth: Boolean,
        /** Exaggerated pitch/speed styles (fun, but harder to understand). Off by default. */
        val wild: Boolean = false,
        /** Atmosphere on the whole line (a LineEffect.choices key). */
        val effect: String = "none",
        /** How many words each voice speaks before the next takes over. */
        val cadence: Cadence = Cadence.SHORT,
        /** Finds audio for a [sound] tag such as "coins" (24 kHz), plus its friendly name. */
        val soundFor: (String) -> Pair<FloatArray, String>? = { null },
    )
    data class Result(val parts: List<String>, val file: File)

    @Volatile private var cancelled = false
    @Volatile private var player: AudioSink? = null

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
        val chunks = Chunker.split(text, cadence = opts.cadence)
        if (chunks.isEmpty()) throw IllegalArgumentException("Nothing to say")
        var speakers = opts.speakers.ifEmpty { throw IllegalStateException("No voices selected") }
        val styles = opts.styles.ifEmpty { listOf(Catalog.styles.first()) }
        val totalWords = chunks.sumOf { it.words.size }
        val xf = AudioDsp.RATE * (if (opts.smooth) 40 else 12) / 1000

        val p = sinkFactory(onPlaybackDone).also { player = it }
        val full = ArrayList<FloatArray>()
        val parts = ArrayList<String>()
        var started = false
        var genMs = 0L
        var audioSec = 0.0
        var bufferedSec = 0.0
        var words = 0
        var last: Speaker? = null
        var lastGroup: String? = null
        var lastStyle: Style? = null

        val fx = LineEffect(opts.effect)
        fun emit(raw: FloatArray) {
            if (raw.isEmpty()) return
            val a = fx.process(raw)
            p.push(a); full.add(a); bufferedSec += a.size / AudioDsp.RATE.toDouble()
        }
        val stitcher = Stitcher(xf) { emit(it) }

        try {
            for ((i, c) in chunks.withIndex()) {
                if (cancelled) break
                onProgress(i, chunks.size)

                // A mimicked sound effect: cut cleanly to it and back.
                if (c.sound != null) {
                    val found = opts.soundFor(c.sound)
                    if (found == null) { parts.add("[${c.sound}: no matching sound]"); continue }
                    val (soundSamples, name) = found
                    stitcher.addSound(soundSamples)
                    parts.add("[$name]")
                    audioSec += soundSamples.size / AudioDsp.RATE.toDouble()
                    continue
                }

                var speaker = pick(speakers, last, lastGroup)
                val style = if (styles.size > 1) styles.filter { it != lastStyle }.random() else styles[0]

                val t0 = now()
                val s = try {
                    engine.speak(c.spoken, speaker, style, opts.smooth, opts.wild)
                } catch (e: Throwable) {
                    // A clone that can't be voiced shouldn't ruin the line: drop clones and use a built-in voice.
                    val builtin = speakers.filterIsInstance<Speaker.Builtin>()
                    if (speaker !is Speaker.Cloned || builtin.isEmpty()) throw e
                    speakers = builtin
                    speaker = pick(speakers, last, lastGroup)
                    engine.speak(c.spoken, speaker, style, opts.smooth, opts.wild)
                }
                last = speaker; lastGroup = groupOf(speaker); lastStyle = style
                genMs += now() - t0
                if (cancelled) break
                if (s.isEmpty()) continue
                parts.add(if (style.key == "natural") speaker.label else "${speaker.label}, ${style.label.lowercase()}")

                // Natural pause after punctuation the user typed; otherwise blend straight into the next voice.
                val prevEnd = if (i > 0) chunks[i - 1].display.trimEnd().lastOrNull() else null
                val gapMs = when (prevEnd) { '.', '!', '?' -> 260; ',', ';', ':' -> 110; else -> 0 }
                stitcher.addSpeech(s, gapMs)

                audioSec += s.size / AudioDsp.RATE.toDouble()
                words += c.words.size
                if (!started && StartGate.shouldStart(words, totalWords, audioSec, bufferedSec, genMs / 1000.0)) {
                    started = true; p.allowStart(); onStarted()
                }
            }
            stitcher.finish()
            if (fx.active && !cancelled) fx.tail().takeIf { it.isNotEmpty() }?.let { t -> p.push(t); full.add(t) }
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

    private fun groupOf(s: Speaker) = VoicePick.groupOf(s)

    private fun pick(pool: List<Speaker>, last: Speaker?, lastGroup: String?) = VoicePick.pick(pool, last, lastGroup)
}
