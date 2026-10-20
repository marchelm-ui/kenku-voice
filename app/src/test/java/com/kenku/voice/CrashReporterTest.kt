package com.kenku.voice

import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReporterTest {

    @Test
    fun `report carries everything needed to debug`() {
        val cause = IllegalStateException("model missing")
        val e = RuntimeException("line failed", cause)
        val r = CrashReporter.format("engine-thread", e, "4.3.99", "Android 15 (SDK 35) · Google Pixel 8a", "now")
        assertTrue(r.contains("Mocking Bird crash report"))
        assertTrue(r.contains("4.3.99"))
        assertTrue(r.contains("Pixel 8a"))
        assertTrue(r.contains("engine-thread"))
        assertTrue(r.contains("RuntimeException"))
        assertTrue(r.contains("line failed"))
        assertTrue("cause missing", r.contains("model missing"))
        assertTrue(r.contains("CrashReporterTest")) // a real stack frame
    }

    @Test
    fun `report is capped so it always fits a share intent`() {
        val deep = RuntimeException("x".repeat(100_000))
        val r = CrashReporter.format("t", deep, "v", "d", "now")
        assertTrue(r.length <= CrashReporter.MAX_CHARS)
    }
}
