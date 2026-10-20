package com.kenku.voice

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/** Ready-made kenku lines: things a kenku might have overheard, grouped by situation. */
object Phrasebook {
    val sections: List<Pair<String, List<String>>> = listOf(
        "⚔️ Combat" to listOf(
            "Roll for initiative!", "Behind you!", "Hold the line!", "Fall back, fall back!",
            "For the king!", "Is that all you've got?", "Take the shot!", "Protect the healer!",
            "They're flanking us!", "Get down!", "I'll hold them off. Run!", "Finish him!",
        ),
        "⚠️ Warnings" to listOf(
            "Danger. Run. Run now.", "Someone's coming!", "It's a trap!", "Don't touch that!",
            "The guards are here!", "Stay quiet. Something is listening.", "Not that door!",
            "The floor is moving!", "Too late. They've seen us.", "Hide!",
        ),
        "🍺 Tavern" to listOf(
            "Another round for my friends!", "Barkeep! Your finest ale!", "Who wants to hear a story?",
            "That's my seat.", "Cheers!", "Keep the change.", "Have you heard the rumor about the old mine?",
            "I'll have what he's having.", "Rooms are two silver a night.",
        ),
        "💰 Trading" to listOf(
            "How much for the shiny?", "That's daylight robbery!", "Ten gold. Final offer.",
            "I'll take it.", "Do you take copper?", "Shiny. Want the shiny.", "No refunds.",
            "What a bargain!", "Pleasure doing business.",
        ),
        "👋 Greetings" to listOf(
            "Hello, friend!", "Well met, traveler.", "Good morning!", "Thank you, kind stranger.",
            "Safe travels.", "Farewell!", "Nice to meet you.", "Help! Help! Over here!",
        ),
        "😈 Taunts" to listOf(
            "You call that a sword?", "Missed me!", "Hold still, little bird.", "Is that the best you can do?",
            "Run home to your mother!", "You fight like a farmer.", "Come and get me!",
        ),
        "❓ Questions" to listOf(
            "Who goes there?", "Where is the treasure?", "What was that noise?", "Can you keep a secret?",
            "Are we there yet?", "Which way to the castle?", "Did you hear that?",
        ),
        "🕵️ Sneaking" to listOf(
            "Shh. Follow me.", "Stay low and stay close.", "The guard is turning. Move!",
            "Wait for my signal.", "Not a sound, not a word.", "Through the shadows, quickly.",
            "Someone's at the door. Act natural.", "We were never here.",
        ),
        "🙏 Pleading" to listOf(
            "Please, I beg you!", "Mercy! I'm only a poor bird!", "Take the gold, not my life!",
            "I know things. Useful things!", "Spare me and I'll serve you!", "It wasn't me, I swear it!",
            "Have pity on a humble creature.",
        ),
        "🎭 Mimicked voices" to listOf(
            "Halt! In the name of the crown!", "The master is not home.", "Fresh bread! Two coppers!",
            "Long live the king!", "Stand and deliver!", "You are under arrest!",
            "The ritual begins at midnight.", "Mine. The shiny is mine, forever.",
        ),
    )
}

/** A stateful filter section, so effects run smoothly across the streamed pieces of a line. */
private class Biquad(var b0: Double, var b1: Double, var b2: Double, var a1: Double, var a2: Double) {
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
    fun step(v: Double): Double {
        val o = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = v; y2 = y1; y1 = o
        return o
    }

    companion object {
        private fun w(fc: Double) = 2 * PI * fc / AudioDsp.RATE
        fun lowPass(fc: Double, q: Double = 0.707): Biquad {
            val w = w(fc); val al = sin(w) / (2 * q); val c = cos(w); val a0 = 1 + al
            return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - al) / a0)
        }
        fun highPass(fc: Double, q: Double = 0.707): Biquad {
            val w = w(fc); val al = sin(w) / (2 * q); val c = cos(w); val a0 = 1 + al
            return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - al) / a0)
        }
    }
}

/**
 * Optional atmosphere on a whole line, applied while it streams:
 *  - Cave echo: a stone-hall echo.
 *  - Distant: muffled, as if heard through a wall or from across a room.
 *  - Old memory: thin and slightly crackly, like a sound the kenku is replaying from long ago.
 */
class LineEffect(val kind: String) {
    private val delay = FloatArray(AudioDsp.RATE)        // 1 s shared ring buffer
    private var pos = 0
    private var t = 0L
    private val rnd = Random(2026) // instance-seeded: streaming-stable and test-deterministic

    private val lp1 = Biquad.lowPass(when (kind) { "distant" -> 2200.0; "underwater" -> 650.0; else -> 3400.0 })
    private val lp2 = Biquad.lowPass(when (kind) { "distant" -> 2200.0; "underwater" -> 650.0; else -> 3400.0 })
    private val hp = Biquad.highPass(when (kind) { "memory" -> 320.0; "radio" -> 500.0; else -> 60.0 })
    private val fbLp = Biquad.lowPass(2500.0)
    private val lpRadio = Biquad.lowPass(2800.0)

    private val delaySamples = when (kind) { "cave" -> AudioDsp.RATE * 240 / 1000; "distant" -> AudioDsp.RATE * 55 / 1000; else -> 1 }
    private val feedback = when (kind) { "cave" -> 0.38; "distant" -> 0.25; else -> 0.0 }
    private val wet = when (kind) { "cave" -> 0.42; "distant" -> 0.3; else -> 0.0 }

    val active get() = kind != "none" && choices.any { it.first == kind }

    fun process(x: FloatArray): FloatArray {
        if (!active) return x
        return FloatArray(x.size) { i ->
            var v = x[i].toDouble()
            when (kind) {
                "distant" -> { v = lp2.step(lp1.step(v)) * 0.85 }
                "memory" -> {
                    v = lp2.step(lp1.step(hp.step(v)))
                    v = tanh(v * 1.6) / 1.4 + rnd.nextDouble(-0.004, 0.004)
                    if (rnd.nextInt(9000) == 0) v += rnd.nextDouble(-0.08, 0.08) // a rare crackle
                }
                "glitch" -> v = glitch(v)
                "phantom" -> v = phantom(v)
                "radio" -> v = radio(v)
                "underwater" -> v = underwater(v)
                "clockwork" -> v = clockwork(v)
            }
            if (feedback > 0) {
                val read = (pos - delaySamples + delay.size) % delay.size
                val echo = delay[read].toDouble()
                delay[pos] = (v + fbLp.step(echo) * feedback).toFloat()
                pos = (pos + 1) % delay.size
                v = v * (1 - wet * 0.3) + echo * wet
            }
            t++
            v.toFloat().coerceIn(-0.98f, 0.98f)
        }
    }

    /** Linear-interpolated read [msBack] behind the write head; the modulated taps stay click-free. */
    private fun tap(msBack: Double): Double {
        val idx = pos - 1 - msBack * AudioDsp.RATE / 1000.0
        val i0 = kotlin.math.floor(idx).toInt()
        val frac = idx - i0
        val a = delay[((i0 % delay.size) + delay.size) % delay.size]
        val b = delay[(((i0 + 1) % delay.size) + delay.size) % delay.size]
        return a + (b - a) * frac
    }

    // Glitched: stutter-repeats, brief dropouts and bit-crushed moments, like a scratched recording.
    private var rptLeft = 0; private var rptLen = 1; private var rptStart = 0; private var rptPlayed = 0
    private var muteLeft = 0; private var crushLeft = 0
    private fun glitch(vIn: Double): Double {
        delay[pos] = vIn.toFloat(); pos = (pos + 1) % delay.size
        var v = vIn
        when {
            rptLeft > 0 -> { v = delay[((rptStart + rptPlayed % rptLen) % delay.size + delay.size) % delay.size].toDouble(); rptPlayed++; rptLeft-- }
            muteLeft > 0 -> { v *= 0.03; muteLeft-- }
            crushLeft > 0 -> { v = Math.round(v * 12.0) / 12.0; crushLeft-- }
            else -> when (rnd.nextInt(26000)) {
                0 -> { rptLen = 720 + rnd.nextInt(720); rptStart = pos - rptLen - 1; rptPlayed = 0; rptLeft = rptLen * (2 + rnd.nextInt(2)) }
                1 -> muteLeft = 360 + rnd.nextInt(600)
                2, 3 -> crushLeft = 960 + rnd.nextInt(1440)
            }
        }
        return v
    }

    // Phantom chorus: two slowly detuned ghost copies shimmer around the voice (the closest a mono speaker gets to a stereogram).
    private fun phantom(vIn: Double): Double {
        delay[pos] = vIn.toFloat(); pos = (pos + 1) % delay.size
        val s = t / AudioDsp.RATE.toDouble()
        val g1 = tap(9.0 + 3.0 * sin(2 * PI * 0.42 * s))
        val g2 = tap(16.0 + 4.0 * sin(2 * PI * 0.61 * s + 1.7))
        return vIn * 0.55 + g1 * 0.38 + g2 * 0.38
    }

    // Old radio: narrow band, broadcast squash, AM flutter, hiss and the odd pop.
    private fun radio(vIn: Double): Double {
        var v = lpRadio.step(hp.step(vIn))
        v = tanh(v * 2.2) / 1.8
        v *= 0.82 + 0.18 * sin(2 * PI * 1.3 * t / AudioDsp.RATE)
        v += (rnd.nextDouble() - 0.5) * 0.012
        if (rnd.nextInt(9000) == 0) v += (rnd.nextDouble() - 0.5) * 0.12
        return v
    }

    // Underwater: heavily muffled with a slow pitch wobble and a gentle swell.
    private fun underwater(vIn: Double): Double {
        delay[pos] = lp2.step(lp1.step(vIn)).toFloat(); pos = (pos + 1) % delay.size
        val s = t / AudioDsp.RATE.toDouble()
        return tap(18.0 + 7.0 * sin(2 * PI * 0.5 * s)) * (0.8 + 0.2 * sin(2 * PI * 0.31 * s)) * 1.15
    }

    // Clockwork: ring modulation plus a tight comb, a construct of brass and gears.
    private fun clockwork(vIn: Double): Double {
        delay[pos] = vIn.toFloat(); pos = (pos + 1) % delay.size
        val ring = vIn * sin(2 * PI * 52.0 * t / AudioDsp.RATE)
        return ring * 0.75 + vIn * 0.3 + tap(4.2) * 0.35
    }

    /** The echo that rings on after the last word. */
    fun tail(): FloatArray {
        if (feedback <= 0) return FloatArray(0)
        val ms = if (kind == "cave") 900 else 300
        return process(FloatArray(AudioDsp.RATE * ms / 1000))
    }

    companion object {
        val choices = listOf(
            "none" to "None",
            "cave" to "Cave echo",
            "distant" to "Distant (through a wall)",
            "memory" to "Old memory (lo-fi)",
            "glitch" to "Glitched (stutters & dropouts)",
            "phantom" to "Phantom chorus (shimmering double)",
            "radio" to "Old radio (crackly broadcast)",
            "underwater" to "Underwater (deep & wobbly)",
            "clockwork" to "Clockwork (metal construct)",
        )
    }
}
