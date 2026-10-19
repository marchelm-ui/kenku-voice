package com.kenku.voice

import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import java.io.File
import kotlin.concurrent.thread

/**
 * Clones tab: record (or import) a few seconds of someone's voice, name it, and your
 * kenku can mimic that person. Each clone can be tested, renamed, removed, and
 * switched in or out of the kenku's voice mix.
 */
class ClonesTab(private val act: MainActivity) {
    private val ui = act.ui
    val view: View

    private lateinit var list: LinearLayout
    private lateinit var panel: LinearLayout
    private lateinit var newButtons: LinearLayout
    private lateinit var recButton: Button
    private lateinit var timer: TextView
    private lateinit var meter: ProgressBar
    private lateinit var afterRec: LinearLayout
    private lateinit var nameField: EditText
    private lateinit var hint: TextView

    private var recorder: Recorder? = null
    private var captured: FloatArray? = null
    private var testing = false

    private val pickAudio = act.registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) importFrom(uri)
    }

    init { view = build() }

    private fun build(): View {
        val content = ui.column().apply { setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(24)) }
        content.addView(ui.title("Voice Clones"))
        content.addView(ui.body("Record a few seconds of someone talking and your kenku can mimic them. Each clone gets its own name. Only clone people who are happy for you to.").apply { setPadding(0, ui.dp(4), 0, ui.dp(12)) })

        newButtons = ui.row()
        newButtons.addView(ui.button("＋ Record a new clone", Ui.Kind.PRIMARY) { openPanel() }, ui.weight())
        newButtons.addView(ui.button("Import audio") { pickAudio.launch("audio/*") }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = ui.dp(8) })
        content.addView(newButtons, ui.fill(12))

        panel = ui.card(highlight = true).apply { visibility = View.GONE }
        panel.addView(ui.heading("New clone"))
        panel.addView(ui.body("Hold the phone near the speaker in a quiet room. Read this out loud in a normal voice for about 10 seconds:", 14f).apply { setPadding(0, ui.dp(4), 0, ui.dp(6)) })
        panel.addView(ui.body("“The old road winds past the river and up into the hills. Travelers stop at the inn for warm bread, cold ale, and a story or two before the long night falls.”", 16f, R.color.ink).apply {
            setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8))
            background = ui.rounded(ui.c(R.color.bg))
        })
        val recRow = ui.row().apply { setPadding(0, ui.dp(10), 0, 0) }
        recButton = ui.button("● Start recording", Ui.Kind.PRIMARY) { toggleRecording() }.apply { minHeight = ui.dp(56) }
        recRow.addView(recButton, ui.weight())
        timer = ui.body("0.0 s", 18f, R.color.ink).apply { setPadding(ui.dp(12), 0, 0, 0) }
        recRow.addView(timer)
        panel.addView(recRow)
        meter = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0 }
        panel.addView(meter, ui.fill(4))
        hint = ui.body("", 13f, R.color.muted)
        panel.addView(hint)

        afterRec = ui.column().apply { visibility = View.GONE; setPadding(0, ui.dp(8), 0, 0) }
        val listenRow = ui.row()
        listenRow.addView(ui.button("▶ Listen back") { captured?.let { playSamples(it) } }, ui.wrap())
        listenRow.addView(ui.button("Record again") { resetPanel(); toggleRecording() }, ui.wrap())
        afterRec.addView(listenRow, ui.fill(8))
        afterRec.addView(ui.body("Name this voice", 14f, R.color.ink))
        nameField = EditText(act).apply {
            id = View.generateViewId()
            hint = "e.g. Captain Bram, Mira the bard"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine(true)
            textSize = 17f
            setTextColor(ui.c(R.color.ink)); setHintTextColor(ui.c(R.color.muted))
        }
        afterRec.addView(nameField, ui.fill(6))
        val saveRow = ui.row()
        saveRow.addView(ui.button("Save clone", Ui.Kind.PRIMARY) { saveClone() }, ui.weight())
        afterRec.addView(saveRow)
        panel.addView(afterRec)
        panel.addView(ui.button("Cancel", Ui.Kind.QUIET) { closePanel() }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END; topMargin = ui.dp(6) })
        content.addView(panel, ui.fill(14))

        content.addView(ui.heading("Your clones").apply { setPadding(0, ui.dp(8), 0, ui.dp(6)) })
        list = ui.column()
        content.addView(list)
        return ScrollView(act).apply { addView(content); isFillViewport = true }
    }

    fun refresh() {
        list.removeAllViews()
        val all = act.clones.list()
        if (all.isEmpty()) {
            list.addView(ui.body("No clones yet. Tap “Record a new clone” to make your first one.", 15f).apply {
                background = ui.rounded(ui.c(R.color.bg), ui.c(R.color.line))
                setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))
            })
            return
        }
        all.forEach { c ->
            val box = ui.card()
            box.addView(ui.heading(c.name))
            box.addView(ui.body("%.0f-second sample".format(c.seconds), 12f, R.color.brass))
            val mix = CheckBox(act).apply {
                text = "Use in the kenku's voice mix"; textSize = 15f; setTextColor(ui.c(R.color.ink))
                isChecked = c.inMix
                setOnCheckedChangeListener { _, on -> c.inMix = on; act.clones.save(c) }
            }
            box.addView(mix)
            val row = ui.row().apply { setPadding(0, ui.dp(6), 0, 0) }
            row.addView(ui.button("▶ Test", Ui.Kind.PRIMARY) { test(c) }, ui.wrap())
            row.addView(ui.button("Rename") { rename(c) }, ui.wrap())
            row.addView(ui.button("Delete", Ui.Kind.DANGER) { delete(c) }, ui.wrap())
            box.addView(android.widget.HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(row) })
            list.addView(box, ui.fill())
        }
    }

    // ---------- recording ----------

    private fun openPanel() {
        resetPanel()
        panel.visibility = View.VISIBLE
        newButtons.visibility = View.GONE
    }

    private fun closePanel() {
        recorder?.stop()
        panel.visibility = View.GONE
        newButtons.visibility = View.VISIBLE
        captured = null
    }

    private fun resetPanel() {
        captured = null
        afterRec.visibility = View.GONE
        timer.text = "0.0 s"
        meter.progress = 0
        hint.text = "Aim for 8–15 seconds. Recording stops by itself at 25 seconds."
        nameField.setText("")
        recButton.text = "● Start recording"
    }

    private fun toggleRecording() {
        recorder?.let { if (it.running) { it.stop(); return } }
        act.withMic { granted ->
            if (!granted) { hint.text = "Kenku Voice needs the microphone to record a clone. You can also use Import audio instead."; return@withMic }
            val r = Recorder().also { recorder = it }
            recButton.text = "■ Stop recording"
            afterRec.visibility = View.GONE
            act.stopPlayback()
            thread(name = "kenku-recorder") {
                val error = try {
                    r.start { secs -> act.main.post { timer.text = "%.1f s".format(secs); meter.progress = (r.level * 140).toInt().coerceIn(0, 100) } }
                    null
                } catch (e: Throwable) { e }
                val raw = r.result()
                act.main.post {
                    if (!act.alive || recorder !== r) return@post
                    recButton.text = "● Start recording"
                    meter.progress = 0
                    if (error != null) { hint.text = "The microphone couldn't start. Close other apps using it and try again."; return@post }
                    acceptSample(raw)
                }
            }
        }
    }

    private fun acceptSample(raw: FloatArray) {
        val speech = CloneStore.speechSeconds(raw)
        if (speech < 3f) {
            hint.text = "That was too short or too quiet (%.0f s of speech). Get closer and talk for about 10 seconds.".format(speech)
            return
        }
        captured = CloneStore.prepare(raw)
        hint.text = if (speech < 6f) "Got %.0f seconds of speech. It will work, but 8 seconds or more sounds more like them.".format(speech)
        else "Got %.0f seconds of speech. Listen back, give it a name and save.".format(speech)
        afterRec.visibility = View.VISIBLE
        nameField.requestFocus()
    }

    private fun importFrom(uri: Uri) {
        openPanel()
        hint.text = "Reading the audio file…"
        act.ioThread.execute {
            val result = try { AudioImport.decode(act, uri) } catch (e: Throwable) { null }
            act.main.post {
                if (!act.alive) return@post
                if (result == null || result.isEmpty()) hint.text = "That file couldn't be read. Try an MP3, M4A, OGG or WAV recording."
                else { timer.text = "%.1f s".format(result.size / AudioDsp.RATE.toFloat()); acceptSample(result) }
            }
        }
    }

    private fun saveClone() {
        val samples = captured ?: return
        val name = nameField.text.toString().trim().take(40).ifBlank { "Voice ${act.clones.list().size + 1}" }
        act.ioThread.execute {
            val ok = try { act.clones.create(name, samples); true } catch (e: Throwable) { false }
            act.main.post {
                if (!act.alive) return@post
                if (ok) {
                    act.toast("Saved “$name”")
                    closePanel(); refresh()
                    // Get the cloning model ready in the background so the first test is quick.
                    if (act.voicesReady) act.engineThread.execute { act.engine.loadPocket() }
                }
                else hint.text = "Couldn't save the clone. Check your phone has free storage."
            }
        }
    }

    private fun playSamples(s: FloatArray) {
        val f = File(act.cacheDir, "clone-listen.wav")
        act.ioThread.execute {
            AudioDsp.writeWav(s, f)
            act.main.post { if (act.alive) act.playFile(f) }
        }
    }

    // ---------- saved clones ----------

    private fun test(c: Clone) {
        if (!act.voicesReady) { act.toast("Voices are still loading"); return }
        if (testing) return
        testing = true
        act.toast("Making a test line as ${c.name}… the first clone use takes a few seconds")
        val out = File(act.cacheDir, "clone-test.wav")
        act.engineThread.execute {
            val ok = try {
                AudioDsp.writeWav(act.engine.speak("Squawk! Listen closely. I can sound just like ${c.name} now.", Speaker.Cloned(c), Catalog.styles.first(), true), out)
                true
            } catch (e: Throwable) { false }
            act.main.post {
                testing = false
                if (!act.alive) return@post
                if (ok) act.playFile(out) else act.toast("This clone couldn't be voiced. Try recording it again.")
            }
        }
    }

    private fun rename(c: Clone) {
        val field = EditText(act).apply {
            setText(c.name); setSelection(c.name.length)
            setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val wrap = ui.column().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0); addView(field) }
        AlertDialog.Builder(act)
            .setTitle("Rename voice")
            .setView(wrap)
            .setPositiveButton("Save") { _, _ ->
                val n = field.text.toString().trim().take(40)
                if (n.isNotEmpty()) { c.name = n; act.clones.save(c); refresh() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun delete(c: Clone) {
        AlertDialog.Builder(act)
            .setMessage("Delete the clone “${c.name}”? This can't be undone.")
            .setPositiveButton("Delete") { _, _ -> act.clones.delete(c); refresh() }
            .setNegativeButton("Keep", null)
            .show()
    }

    fun onDestroy() { recorder?.stop() }
}
