package com.kenku.voice

/**
 * Pure assembly of a kenku line: crossfades voice snippets into each other, inserts
 * breath pauses after punctuation, splices in raw sounds, and fades the final tail.
 * Every produced piece goes to [emit], in playback order. No audio devices, no
 * threads — this is the math that makes the voice switches seamless, and it is
 * covered by unit tests so a clicky or mis-timed join can never ship quietly.
 */
class Stitcher(private val crossfadeSamples: Int, private val emit: (FloatArray) -> Unit) {

    /** The yet-unemitted end of the previous snippet, kept back so the next one can blend into it. */
    private var tail: FloatArray? = null

    /** Adds a spoken snippet. [gapMs] > 0 means "finish the previous voice, breathe, then start fresh". */
    fun addSpeech(s: FloatArray, gapMs: Int) {
        if (s.isEmpty()) return
        val prev = tail
        when {
            prev == null -> holdTail(s, 0)
            gapMs > 0 -> {
                fadeOutAndEmit(prev)
                emit(FloatArray(AudioDsp.RATE * gapMs / 1000))
                holdTail(s, 0)
            }
            else -> {
                val n = minOf(prev.size, s.size / 2)
                emit(prev.copyOfRange(0, prev.size - n))
                emit(FloatArray(n) { k ->
                    prev[prev.size - n + k] * AudioDsp.fadeOutGain(k, n) + s[k] * AudioDsp.fadeInGain(k, n)
                })
                holdTail(s, n)
            }
        }
    }

    /** Splices a non-voice sound (a mimicked effect): close the current voice, pad, play, pad. */
    fun addSound(sound: FloatArray, preMs: Int = 60, postMs: Int = 80) {
        tail?.let { fadeOutAndEmit(it) }
        tail = null
        emit(FloatArray(AudioDsp.RATE * preMs / 1000))
        emit(AudioDsp.fadeEdges(sound.copyOf()))
        emit(FloatArray(AudioDsp.RATE * postMs / 1000))
    }

    /** Fades whatever is still held out to silence; call once after the last snippet. */
    fun finish() {
        tail?.let { fadeOutAndEmit(it) }
        tail = null
    }

    /** Emits the snippet's body and keeps its last [crossfadeSamples] back for the next join. */
    private fun holdTail(s: FloatArray, from: Int) {
        val bodyEnd = maxOf(from, s.size - crossfadeSamples)
        emit(s.copyOfRange(from, bodyEnd))
        tail = s.copyOfRange(bodyEnd, s.size)
    }

    private fun fadeOutAndEmit(t: FloatArray) {
        for (k in t.indices) t[k] *= AudioDsp.fadeOutGain(k, t.size)
        emit(t)
    }
}
