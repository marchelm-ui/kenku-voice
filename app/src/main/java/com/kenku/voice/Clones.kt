package com.kenku.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs

/** A voice the user cloned from a recording. */
class Clone(val id: String, var name: String, var inMix: Boolean, private val dir: File) {
    val sampleFile get() = File(dir, "sample.wav")
    @Volatile private var cached: FloatArray? = null

    /** The example audio Pocket TTS imitates, at [AudioDsp.RATE]. */
    fun samples(): FloatArray = cached ?: run {
        val (s, r) = AudioDsp.readWav(sampleFile) ?: throw IllegalStateException("Missing sample for $name")
        AudioDsp.resample(s, r).also { cached = it }
    }

    val seconds: Float get() = (sampleFile.length() - 44).coerceAtLeast(0) / 2f / AudioDsp.RATE
}

/** Saved clones live in the app's private folder: clones/<id>/sample.wav + info.json. */
class CloneStore(context: Context) {
    private val root = File(context.filesDir, "clones").apply { mkdirs() }

    fun list(): List<Clone> = (root.listFiles() ?: emptyArray())
        .filter { it.isDirectory && File(it, "sample.wav").exists() }
        .mapNotNull { dir ->
            try {
                val info = JSONObject(File(dir, "info.json").readText())
                Clone(dir.name, info.optString("name", "Unnamed voice"), info.optBoolean("inMix", true), dir)
            } catch (e: Exception) { Clone(dir.name, "Unnamed voice", true, dir) }
        }
        .sortedBy { it.id }

    fun create(name: String, samples: FloatArray): Clone {
        val id = System.currentTimeMillis().toString()
        val dir = File(root, id).apply { mkdirs() }
        AudioDsp.writeWav(samples, File(dir, "sample.wav"))
        val c = Clone(id, name, true, dir)
        save(c)
        return c
    }

    fun save(c: Clone) {
        File(root, c.id).mkdirs()
        FileSafe.writeText(File(File(root, c.id), "info.json"), JSONObject().put("name", c.name).put("inMix", c.inMix).toString())
    }

    fun delete(c: Clone) { File(root, c.id).deleteRecursively() }

    companion object {
        /** Cleans up a raw recording into a good cloning example (trimmed, level-matched, ≤ 20 s). */
        fun prepare(samples: FloatArray): FloatArray {
            var s = AudioDsp.trim(samples, threshold = 0.02f, padMs = 120)
            if (s.size > AudioDsp.RATE * 20) s = s.copyOfRange(0, AudioDsp.RATE * 20)
            return AudioDsp.fadeEdges(AudioDsp.matchLoudness(s, 0.09f), 20)
        }

        // Speech-seconds measurement lives in SampleQuality.assess() — one authority.
    }
}

/** Records the microphone at 24 kHz mono. Call [start] from a background thread. */
class Recorder {
    @Volatile var running = false; private set
    @Volatile var level = 0f; private set
    private val data = ArrayList<FloatArray>()
    private var captureRate = AudioDsp.RATE

    @SuppressLint("MissingPermission") // The caller checks RECORD_AUDIO first.
    fun start(maxSeconds: Int = 25, onTick: (seconds: Float) -> Unit) {
        // Most phones record at 48 kHz natively; try the app's rate first, then common ones.
        var rec: AudioRecord? = null
        var rate = AudioDsp.RATE
        for (r in intArrayOf(AudioDsp.RATE, 48000, 44100, 16000)) {
            val minBuf = AudioRecord.getMinBufferSize(r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) continue
            val candidate = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, r, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, r))
            } catch (e: Exception) { null } ?: continue
            if (candidate.state == AudioRecord.STATE_INITIALIZED) { rec = candidate; rate = r; break }
            candidate.release()
        }
        if (rec == null) throw IllegalStateException("Microphone unavailable")
        data.clear()
        captureRate = rate
        val buf = ShortArray(rate / 10)
        running = true
        var total = 0
        try {
            rec.startRecording()
            while (running && total < rate * maxSeconds) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                val f = FloatArray(n) { buf[it] / 32768f }
                data.add(f)
                total += n
                var peak = 0f
                for (v in f) peak = maxOf(peak, abs(v))
                level = peak
                onTick(total / rate.toFloat())
            }
        } finally {
            running = false
            try { rec.stop() } catch (e: Exception) {}
            rec.release()
        }
    }

    fun stop() { running = false }

    fun result(): FloatArray {
        val out = FloatArray(data.sumOf { it.size })
        var o = 0
        for (a in data) { a.copyInto(out, o); o += a.size }
        return AudioDsp.resample(out, captureRate)
    }
}

/** Decodes any audio file the user picks (mp3, m4a, ogg, wav…) to 24 kHz mono, up to [maxSeconds]. */
object AudioImport {
    fun decode(context: Context, uri: Uri, maxSeconds: Int = 30): FloatArray {
        val ex = MediaExtractor()
        ex.setDataSource(context, uri, null)
        return decode(ex, maxSeconds)
    }

    /** Decodes a bundled asset (e.g. a sound effect) to 24 kHz mono. */
    fun decodeAsset(context: Context, path: String, maxSeconds: Int = 30): FloatArray {
        context.assets.openFd(path).use { fd ->
            val ex = MediaExtractor()
            ex.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            return decode(ex, maxSeconds)
        }
    }

    private fun decode(ex: MediaExtractor, maxSeconds: Int): FloatArray {
        val track = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run { ex.release(); throw IllegalArgumentException("No audio in that file") }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(fmt, null, null, 0)
        codec.start()
        val out = ArrayList<FloatArray>()
        var total = 0
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone && total < rate * maxSeconds) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val b = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(b, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    val b = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                    b.position(info.offset); b.limit(info.offset + info.size)
                    val shorts = b.asShortBuffer()
                    val frames = shorts.remaining() / channels
                    val f = FloatArray(frames) { k ->
                        var s = 0f
                        for (c in 0 until channels) s += shorts.get(k * channels + c) / 32768f
                        s / channels
                    }
                    out.add(f); total += frames
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            try { codec.stop() } catch (e: Exception) {}
            codec.release(); ex.release()
        }
        val all = FloatArray(out.sumOf { it.size })
        var p = 0
        for (a in out) { a.copyInto(all, p); p += a.size }
        return AudioDsp.resample(all, rate)
    }
}
