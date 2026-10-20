package com.kenku.voice

import kotlin.random.Random

/**
 * Chooses the next stolen voice — the app's founding promise: a different speaker
 * every snippet. Accents (and each clone) get an equal chance regardless of how many
 * voices they hold, so the twenty American voices don't crowd out the rest; then a
 * voice within the group. Pure, with the fairness and no-repeat rules pinned by tests.
 */
object VoicePick {

    fun groupOf(s: Speaker) = when (s) {
        is Speaker.Builtin -> s.accent.key
        is Speaker.Cloned -> "clone:" + s.clone.id
    }

    fun pick(pool: List<Speaker>, last: Speaker?, lastGroup: String?, rnd: Random = Random.Default): Speaker {
        if (pool.size == 1) return pool[0]
        val groups = pool.groupBy { groupOf(it) }
        val keys = groups.keys.filter { it != lastGroup }.ifEmpty { groups.keys.toList() }
        val members = groups.getValue(keys[rnd.nextInt(keys.size)])
        val choices = members.filter { it != last }.ifEmpty { members }
        return choices[rnd.nextInt(choices.size)]
    }
}
