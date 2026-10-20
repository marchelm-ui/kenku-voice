package com.kenku.voice

/**
 * Decides which decoded sounds to drop when a new category comes on screen: everything
 * not being shown and not currently playing. Pure, so the no-unbounded-growth and the
 * never-cut-a-playing-sound rules are pinned by tests.
 */
object SfxCache {
    fun toEvict(loaded: Set<String>, showing: Set<String>, playing: Set<String>): List<String> =
        loaded.filter { it !in showing && it !in playing }
}
