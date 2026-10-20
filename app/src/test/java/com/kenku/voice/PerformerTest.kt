package com.kenku.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * The whole performance — split, voice each piece, splice sounds, stitch, stream,
 * save — run end to end on the JVM against fake engine/sink seams. This is the
 * orchestration no unit of it can test alone: ordering, labels, clone fallback,
 * cancellation, and the file on disk at the end.
 */
class PerformerTest {

    private class FakeSink : AudioSink {
        val pushed = ArrayList<FloatArray>()
        var startAllowed = false
        var finished = false
        var stopped = false
        override fun push(samples: FloatArray) { pushed.add(samples) }
        override fun allowStart() { startAllowed = true }
        override fun finish() { finished = true }
        override fun stop() { stopped = true }
    }

    private fun tone(hz: Double, seconds: Double = 0.12) =
        FloatArray((AudioDsp.RATE * seconds).toInt()) { (0.25 * sin(2 * PI * hz * it / AudioDsp.RATE)).toFloat() }

    private fun builtin(i: Int) =
        Speaker.Builtin(KVoice(i, "v$i", "Voice $i"), Accent("accent$i", "Accent $i", "", emptyList()))

    private val clone = Speaker.Cloned(Clone("c1", "Bram", true, File("unused")))

    /** Speaks a tone whose frequency depends on the speaker; throws for clones when asked to. */
    private fun source(cloneFails: Boolean = false) = object : TtsSource {
        override fun speak(text: String, speaker: Speaker, style: Style, smooth: Boolean, wild: Boolean): FloatArray {
            if (speaker is Speaker.Cloned && cloneFails) throw IllegalStateException("Cloning unavailable")
            return tone(200.0 + 50 * ((speaker as? Speaker.Builtin)?.voice?.sid ?: 9))
        }
    }

    private fun performer(src: TtsSource, sink: FakeSink): Performer {
        var t = 0L
        return Performer(src, sinkFactory = { sink }, now = { t += 7; t })
    }

    private fun opts(vararg speakers: Speaker) = Performer.Options(
        speakers = speakers.toList(), styles = listOf(Catalog.styles.first()), smooth = true)

    private fun outFile() = File.createTempFile("performer-test", ".wav").apply { deleteOnExit() }

    @Test
    fun `a line is voiced, streamed, finished and saved`() {
        val sink = FakeSink()
        var startedCalls = 0
        var lastProgress = -1 to -1
        val out = outFile()
        val res = performer(source(), sink).perform(
            "You owe us gold, the tavern keeper said so.", opts(builtin(0), builtin(1)), out,
            onProgress = { d, t -> lastProgress = d to t }, onStarted = { startedCalls++ }, onPlaybackDone = {})
        assertTrue("no audio streamed", sink.pushed.isNotEmpty())
        assertTrue(sink.startAllowed)
        assertTrue(sink.finished)
        assertFalse(sink.stopped)
        assertEquals(1, startedCalls)
        assertEquals(lastProgress.second, lastProgress.first)
        assertTrue("parts: ${res.parts}", res.parts.isNotEmpty())
        val wav = AudioDsp.readWav(out)
        assertNotNull("saved wav unreadable", wav)
        assertEquals(sink.pushed.sumOf { it.size }, wav!!.first.size)
    }

    @Test
    fun `a failing clone falls back to builtin voices instead of ruining the line`() {
        val sink = FakeSink()
        val res = performer(source(cloneFails = true), sink).perform(
            "Pay the toll little bird or face the watch tonight.", opts(clone, builtin(0)), outFile(),
            onProgress = { _, _ -> }, onStarted = {}, onPlaybackDone = {})
        assertTrue(res.parts.isNotEmpty())
        res.parts.forEach { assertFalse("clone leaked into parts: $it", it.contains("(clone)")) }
        assertTrue(sink.finished)
    }

    @Test
    fun `cancelling mid-line stops the sink and raises Stopped`() {
        val sink = FakeSink()
        val p = performer(source(), sink)
        try {
            p.perform("One two three four five six seven eight nine ten eleven twelve.", opts(builtin(0), builtin(1)), outFile(),
                onProgress = { d, _ -> if (d >= 1) p.cancel() }, onStarted = {}, onPlaybackDone = {})
            fail("expected InterruptedException")
        } catch (e: InterruptedException) {
            assertTrue(sink.stopped)
        }
    }

    @Test
    fun `a sound tag is spliced in and credited in the parts`() {
        val sink = FakeSink()
        val res = performer(source(), sink).perform(
            "Pay up [coins] right now friend.", Performer.Options(
                speakers = listOf(builtin(0), builtin(1)), styles = listOf(Catalog.styles.first()), smooth = true,
                soundFor = { tag -> if (tag == "coins") tone(600.0, 0.3) to "Coin toss" else null }),
            outFile(), onProgress = { _, _ -> }, onStarted = {}, onPlaybackDone = {})
        assertTrue("parts: ${res.parts}", res.parts.any { it == "[Coin toss]" })
    }

    @Test
    fun `a missing sound is reported, not fatal`() {
        val sink = FakeSink()
        val res = performer(source(), sink).perform(
            "Listen [nosuchsound] closely now.", opts(builtin(0), builtin(1)), outFile(),
            onProgress = { _, _ -> }, onStarted = {}, onPlaybackDone = {})
        assertTrue(res.parts.any { it.contains("no matching sound") })
    }

    @Test
    fun `word cadence voices every single word`() {
        val sink = FakeSink()
        val text = "Pay the toll little bird"
        val res = performer(source(), sink).perform(
            text, Performer.Options(listOf(builtin(0), builtin(1)), listOf(Catalog.styles.first()),
                smooth = true, cadence = Cadence.WORD),
            outFile(), onProgress = { _, _ -> }, onStarted = {}, onPlaybackDone = {})
        assertEquals(text.split(" ").size, res.parts.size)
    }

    @Test
    fun `sentence cadence gives one voice per sentence`() {
        val sink = FakeSink()
        val res = performer(source(), sink).perform(
            "Pay the toll. Or face the watch. Run!",
            Performer.Options(listOf(builtin(0), builtin(1)), listOf(Catalog.styles.first()),
                smooth = true, cadence = Cadence.SENTENCE),
            outFile(), onProgress = { _, _ -> }, onStarted = {}, onPlaybackDone = {})
        assertEquals(3, res.parts.size)
    }

    @Test
    fun `an empty line is refused up front`() {
        try {
            performer(source(), FakeSink()).perform("   ", opts(builtin(0)), outFile(),
                onProgress = { _, _ -> }, onStarted = {}, onPlaybackDone = {})
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {}
    }
}
