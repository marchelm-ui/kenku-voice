package com.kenku.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SfxCacheTest {

    @Test
    fun `switching category drops the old one and keeps the new`() {
        val evict = SfxCache.toEvict(loaded = setOf("d1", "d2", "c1"), showing = setOf("c1", "c2"), playing = emptySet())
        assertEquals(setOf("d1", "d2"), evict.toSet())
    }

    @Test
    fun `a sound that is still playing is never evicted`() {
        val evict = SfxCache.toEvict(loaded = setOf("d1", "d2"), showing = setOf("c1"), playing = setOf("d2"))
        assertEquals(listOf("d1"), evict)
    }

    @Test
    fun `the cache is bounded by what is shown plus what is playing`() {
        // Browse every category: after each switch, only that category (plus anything
        // playing) may remain — the invariant that stops unbounded memory growth.
        var loaded = setOf<String>()
        for (cat in 1..11) {
            val showing = (1..40).map { "s$cat-$it" }.toSet()
            loaded = loaded - SfxCache.toEvict(loaded, showing, playing = setOf("keep")).toSet() + showing
            assertTrue("cache grew past one category: ${loaded.size}", loaded.size <= 41)
        }
    }

    @Test
    fun `re-showing the same category evicts nothing`() {
        val showing = setOf("a", "b", "c")
        assertTrue(SfxCache.toEvict(showing, showing, emptySet()).isEmpty())
    }
}
