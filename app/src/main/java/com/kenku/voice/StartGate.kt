package com.kenku.voice

/**
 * Decides when streamed playback may begin: as soon as the rest of the line can be
 * generated before playback catches up (plus headroom), so a line starts within
 * seconds but never stutters mid-sentence. This is the "replies within seconds"
 * promise — pure, so its edges live in tests instead of at the table.
 */
object StartGate {

    /** Generation-time estimates are optimistic on a warming phone; pad them. */
    const val HEADROOM = 1.15

    /** The buffer must stay at least this far ahead of the speaker. */
    const val MIN_LEAD_SEC = 0.3

    /**
     * @param wordsDone words generated so far (speech only)
     * @param totalWords words in the whole line
     * @param audioSec seconds of speech audio generated so far
     * @param bufferedSec seconds of audio handed to the player so far
     * @param genSec wall-clock seconds spent generating so far
     */
    fun shouldStart(wordsDone: Int, totalWords: Int, audioSec: Double, bufferedSec: Double, genSec: Double): Boolean {
        if (wordsDone <= 0) return false
        val remWords = totalWords - wordsDone
        if (remWords <= 0) return true
        if (audioSec <= 0.0) return false
        val estRemAudio = audioSec / wordsDone * remWords
        val estRemGen = genSec / audioSec * estRemAudio * HEADROOM
        return bufferedSec + estRemAudio - estRemGen >= MIN_LEAD_SEC
    }
}
