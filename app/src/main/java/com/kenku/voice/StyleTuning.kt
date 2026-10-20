package com.kenku.voice

import kotlin.random.Random

/**
 * Turns a style into the actual pitch / rate / generation-speed numbers. This is the
 * intelligibility contract of the whole app: Marcus's one field-reported fault was
 * voices pitched or sped beyond understanding, and these clamps are what fixed it.
 * Pure and tested, so no future style can ship past them.
 */
object StyleTuning {

    // Clear mode: every voice stays easy to understand.
    const val CLEAR_PITCH_MIN = 0.88f
    const val CLEAR_PITCH_MAX = 1.16f
    const val CLEAR_RATE_MIN = 0.86f
    const val CLEAR_RATE_MAX = 1.12f

    // Wild styles (opt-in): exaggerated, but still bounded away from noise.
    const val WILD_PITCH_MIN = 0.7f
    const val WILD_PITCH_MAX = 1.6f
    const val WILD_RATE_MIN = 0.75f
    const val WILD_RATE_MAX = 1.35f

    // What the model is asked for, after pitch/rate are combined.
    const val SPEED_MIN = 0.6f
    const val SPEED_MAX = 1.6f

    data class Tuning(val pitch: Float, val rate: Float, val speed: Float)

    /**
     * [smooth] narrows the random jitter so blended snippets switch subtly;
     * [wild] widens the allowed range. [rnd] is injectable for deterministic tests.
     */
    fun tune(style: Style, smooth: Boolean, wild: Boolean, rnd: Random = Random.Default): Tuning {
        val basePitch = if (wild) style.wildPitch else style.pitch
        val baseRate = if (wild) style.wildRate else style.rate
        val jitter = if (smooth) 0.015 else 0.03
        val pitch = (basePitch * rnd.nextDouble(1 - jitter, 1 + jitter).toFloat())
            .let {
                if (wild) it.coerceIn(WILD_PITCH_MIN, WILD_PITCH_MAX)
                else it.coerceIn(CLEAR_PITCH_MIN, CLEAR_PITCH_MAX)
            }
        val rate = if (wild) baseRate.coerceIn(WILD_RATE_MIN, WILD_RATE_MAX)
        else baseRate.coerceIn(CLEAR_RATE_MIN, CLEAR_RATE_MAX)
        val speed = (rate / pitch).coerceIn(SPEED_MIN, SPEED_MAX)
        return Tuning(pitch, rate, speed)
    }
}
