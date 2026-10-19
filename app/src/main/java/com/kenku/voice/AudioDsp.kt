package com.kenku.voice

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** Audio helpers used to make stolen-voice snippets flow together without seams. */
object AudioDsp {
    const val RATE = 24000

    /**
     * Changes pitch (and length) by resampling; callers compensate speed beforehand.
     * Uses a band-limited (windowed-sinc) resampler: plain linear interpolation dulls
     * consonants like s, t and k by up to 3 dB and adds harsh aliasing when pitching up.
     */
    fun pitchShift(src: FloatArray, factor: Float): FloatArray {
        if (src.isEmpty() || abs(factor - 1f) < 0.005f) return src
        val n = (src.size / factor).toInt().coerceAtLeast(1)
        val cutoff = minOf(1.0, 1.0 / factor) * 0.92
        val half = ceil(10.0 / cutoff).toInt()
        val os = 256
        val size = half * os
        val table = FloatArray(size + 2) { j ->
            val t = j.toDouble() / os
            if (j > size) 0f else {
                val x = cutoff * t * Math.PI
                val sinc = if (x == 0.0) 1.0 else sin(x) / x
                (sinc * (0.5 + 0.5 * cos(Math.PI * t / half))).toFloat()
            }
        }
        val out = FloatArray(n)
        val last = src.size - 1
        for (i in 0 until n) {
            val x = i * factor.toDouble()
            val c = x.toInt()
            var sum = 0.0
            var wsum = 0.0
            var k = maxOf(0, c - half + 1)
            val end = minOf(last, c + half)
            while (k <= end) {
                val t = abs(x - k) * os
                val j = t.toInt()
                if (j < size) {
                    val w = table[j] + (table[j + 1] - table[j]) * (t - j).toFloat()
                    sum += src[k] * w
                    wsum += w
                }
                k++
            }
            out[i] = if (wsum != 0.0) (sum / wsum).toFloat() else 0f
        }
        return out
    }

    /** Band-limited resample to [RATE]. */
    fun resample(src: FloatArray, fromRate: Int): FloatArray =
        if (fromRate == RATE || fromRate <= 0) src else pitchShift(src, fromRate.toFloat() / RATE)

    /** Cuts leading/trailing near-silence, leaving a few milliseconds of breath. */
    fun trim(src: FloatArray, threshold: Float = 0.012f, padMs: Int = 18): FloatArray {
        if (src.isEmpty()) return src
        val win = 120
        fun energetic(i: Int): Boolean {
            var m = 0f
            for (k in i until minOf(i + win, src.size)) m = maxOf(m, abs(src[k]))
            return m > threshold
        }
        var s = 0
        while (s < src.size && !energetic(s)) s += win
        var e = src.size - 1
        while (e > s && !energetic(maxOf(0, e - win))) e -= win
        if (s >= e) return src
        val pad = RATE * padMs / 1000
        return src.copyOfRange((s - pad).coerceAtLeast(0), (e + pad).coerceAtMost(src.size))
    }

    /** RMS of the louder (voiced) parts, ignoring quiet gaps. */
    private fun voicedRms(src: FloatArray): Float {
        val win = 480
        val frames = mutableListOf<Float>()
        var i = 0
        while (i < src.size) {
            var sum = 0.0
            val end = minOf(i + win, src.size)
            for (k in i until end) sum += src[k] * src[k]
            frames.add(sqrt(sum / (end - i)).toFloat())
            i += win
        }
        if (frames.isEmpty()) return 0f
        frames.sort()
        val top = frames.subList(frames.size / 3, frames.size)
        return top.average().toFloat()
    }

    /**
     * Brings a snippet to a common loudness so one voice is never noticeably louder than
     * the next, with a soft limiter instead of hard clipping.
     */
    fun matchLoudness(src: FloatArray, target: Float = 0.085f): FloatArray {
        val rms = voicedRms(src)
        if (rms < 1e-4f) return src
        val gain = (target / rms).coerceIn(0.35f, 3.5f)
        return FloatArray(src.size) { i -> softClip(src[i] * gain) }
    }

    private fun softClip(x: Float): Float = if (abs(x) < 0.85f) x else (tanh(x.toDouble() * 1.1) / tanh(1.1)).toFloat().coerceIn(-0.98f, 0.98f)

    /** One second-order filter section (RBJ audio-EQ cookbook). */
    private fun biquad(x: FloatArray, b0: Double, b1: Double, b2: Double, a1: Double, a2: Double): FloatArray {
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        return FloatArray(x.size) { i ->
            val v = x[i].toDouble()
            val o = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = v; y2 = y1; y1 = o
            o.toFloat()
        }
    }

    private fun highPass(x: FloatArray, fc: Double, q: Double = 0.707): FloatArray {
        val w = 2 * Math.PI * fc / RATE; val al = sin(w) / (2 * q); val c = cos(w); val a0 = 1 + al
        return biquad(x, (1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - al) / a0)
    }

    private fun peak(x: FloatArray, fc: Double, q: Double, db: Double): FloatArray {
        val a = 10.0.pow(db / 40); val w = 2 * Math.PI * fc / RATE; val al = sin(w) / (2 * q); val c = cos(w); val a0 = 1 + al / a
        return biquad(x, (1 + al * a) / a0, -2 * c / a0, (1 - al * a) / a0, -2 * c / a0, (1 - al / a) / a0)
    }

    /** Gentle peak compressor: evens out loud syllables so quiet words stay audible. */
    private fun compress(x: FloatArray, threshold: Float = 0.25f, ratio: Float = 3f): FloatArray {
        val ga = exp(-1.0 / (0.003 * RATE)).toFloat()
        val gr = exp(-1.0 / (0.06 * RATE)).toFloat()
        var env = 0f
        return FloatArray(x.size) { i ->
            val a = abs(x[i])
            env = if (a > env) ga * env + (1 - ga) * a else gr * env + (1 - gr) * a
            val g = if (env > threshold) threshold * (env / threshold).pow(1f / ratio) / env else 1f
            x[i] * g
        }
    }

    /**
     * Clarity chain for every spoken snippet: removes low rumble, adds a touch of
     * presence where consonants live (~3 kHz), evens out loudness and brings each
     * voice to the same comfortable, table-friendly level.
     */
    fun polish(src: FloatArray, target: Float = 0.105f): FloatArray {
        if (src.isEmpty()) return src
        var x = highPass(src, 80.0)
        x = peak(x, 3000.0, 0.9, 2.5)
        x = matchLoudness(x, 0.09f)
        x = compress(x)
        return matchLoudness(x, target)
    }

    /** Short fades at both ends to remove clicks. */
    fun fadeEdges(src: FloatArray, ms: Int = 6): FloatArray {
        val n = minOf(RATE * ms / 1000, src.size / 2)
        for (i in 0 until n) {
            val g = i.toFloat() / n
            src[i] *= g
            src[src.size - 1 - i] *= g
        }
        return src
    }

    /** Equal-power crossfade curves for the overlap between two snippets. */
    fun fadeOutGain(i: Int, n: Int): Float = cos((i.toDouble() / n) * Math.PI / 2).toFloat()
    fun fadeInGain(i: Int, n: Int): Float = sin((i.toDouble() / n) * Math.PI / 2).toFloat()

    fun writeWav(samples: FloatArray, out: File, rate: Int = RATE) {
        if (samples.isEmpty()) throw IllegalStateException("Empty audio")
        val dataBytes = samples.size * 2
        val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII)); buf.putInt(36 + dataBytes)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII)); buf.putInt(16); buf.putShort(1); buf.putShort(1)
        buf.putInt(rate); buf.putInt(rate * 2); buf.putShort(2); buf.putShort(16)
        buf.put("data".toByteArray(Charsets.US_ASCII)); buf.putInt(dataBytes)
        for (v in samples) buf.putShort((v.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, out.name + ".tmp")
        FileOutputStream(tmp).use { it.write(buf.array()) }
        if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
    }

    /** Reads a 16-bit PCM mono/stereo WAV written by this app (or a simple recorder). */
    fun readWav(file: File): Pair<FloatArray, Int>? {
        val b = try { file.readBytes() } catch (e: Exception) { return null }
        if (b.size < 44 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF") return null
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var channels = 1; var rate = RATE; var bits = 16
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.US_ASCII)
            var size = bb.getInt(pos + 4)
            val start = pos + 8
            if (id == "fmt " && start + 16 <= b.size) {
                channels = bb.getShort(start + 2).toInt().coerceAtLeast(1)
                rate = bb.getInt(start + 4)
                bits = bb.getShort(start + 14).toInt()
            } else if (id == "data") {
                if (bits != 16) return null
                if (size <= 0 || start + size > b.size) size = b.size - start
                val frames = size / (2 * channels)
                val out = FloatArray(frames) { f ->
                    var s = 0f
                    for (c in 0 until channels) s += bb.getShort(start + (f * channels + c) * 2) / 32768f
                    s / channels
                }
                return out to rate
            }
            if (size < 0) return null
            pos = start + size + (size and 1)
        }
        return null
    }
}
