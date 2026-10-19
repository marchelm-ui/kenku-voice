package com.kenku.voice

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var engine: KokoroEngine
    // Every model call runs on this one thread.
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    @Volatile private var busy = false
    @Volatile private var loaded = false
    private var previewing = false

    private lateinit var list: LinearLayout
    private lateinit var input: EditText
    private lateinit var say: Button
    private lateinit var status: TextView
    private lateinit var castInfo: TextView

    private val prefs by lazy { getSharedPreferences("kenku", MODE_PRIVATE) }
    private val linesDir by lazy { File(filesDir, "lines").apply { mkdirs() } }
    private val indexFile by lazy { File(filesDir, "lines.json") }

    data class Line(val id: String, val text: String, var voices: String, var starred: Boolean = false)
    private val lines = mutableListOf<Line>()
    private val accents: List<Accent> = Catalog.accents

    private fun c(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        loadLines()
        renderList()
        updateCastInfo()
        setBusy(true, "Loading the voices… the first launch takes a little longer.")
        engine = KokoroEngine(applicationContext)
        worker.execute {
            val error = try { engine.load(); null } catch (e: Throwable) { e }
            main.post {
                if (isFinishing || isDestroyed) return@post
                if (error == null) { loaded = true; setBusy(false, "") }
                else setBusy(true, "The voices couldn't load (${error.javaClass.simpleName}). Close other apps and reopen Kenku Voice.")
            }
        }
    }

    override fun onStop() {
        super.onStop()
        player?.let { try { if (it.isPlaying) it.pause() } catch (e: Exception) {} }
    }

    override fun onDestroy() {
        player?.release(); player = null
        // Free the model on the same thread that uses it, after any line in progress.
        if (::engine.isInitialized) worker.execute { engine.release() }
        worker.shutdown()
        super.onDestroy()
    }

    // ---------- voice choices ----------

    private fun offSet(key: String): Set<String> = prefs.getStringSet(key, emptySet()) ?: emptySet()
    private fun setOff(key: String, v: Set<String>) { prefs.edit().putStringSet(key, HashSet(v)).apply() }

    private fun activeAccents(): List<Accent> {
        val off = offSet("off_accents")
        return accents.filter { it.key !in off }.ifEmpty { accents }
    }

    private fun activeStyles(): List<Style> {
        val off = offSet("off_styles")
        return Catalog.styles.filter { it.key !in off }.ifEmpty { listOf(Catalog.styles.first()) }
    }

    private fun updateCastInfo() {
        val a = activeAccents()
        castInfo.text = "${a.sumOf { it.voices.size }} voices · ${a.size} accents · ${activeStyles().size} styles"
    }

    // ---------- UI ----------

    private fun rounded(fill: Int, stroke: Int?, radius: Int = 12) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun button(text: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 15f
        minHeight = dp(48); minimumHeight = dp(48)
        minWidth = 0; minimumWidth = 0
        setPadding(dp(14), 0, dp(14), 0)
        setTextColor(if (primary) c(R.color.accent_ink) else c(R.color.ink))
        background = rounded(if (primary) c(R.color.accent) else c(R.color.surface), if (primary) null else c(R.color.line))
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c(R.color.bg))
            fitsSystemWindows = true
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(6))
        }
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(this).apply {
            text = "Kenku Voice"
            textSize = 28f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            setTextColor(c(R.color.ink))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(button("Voices") { openVoices() }.apply { textSize = 14f })
        header.addView(titleRow)
        header.addView(TextView(this).apply {
            text = "Type and press Enter. Every 2–3 words come out in a new stolen voice. Tap a saved line to hear it again."
            textSize = 14f
            setTextColor(c(R.color.muted))
            setPadding(0, dp(4), 0, 0)
        })
        castInfo = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(c(R.color.brass))
            setPadding(0, dp(4), 0, 0)
        }
        header.addView(castInfo)
        status = TextView(this).apply {
            textSize = 14f
            setTextColor(c(R.color.accent))
            setPadding(0, dp(6), 0, 0)
            visibility = View.GONE
        }
        header.addView(status)
        root.addView(header)

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(16))
        }
        root.addView(ScrollView(this).apply { addView(list); isFillViewport = true },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(c(R.color.surface))
        }
        input = EditText(this).apply {
            id = View.generateViewId()
            hint = "What does your kenku say?"
            textSize = 18f
            setTextColor(c(R.color.ink))
            setHintTextColor(c(R.color.muted))
            background = rounded(c(R.color.bg), c(R.color.line))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            maxLines = 4
            setHorizontallyScrolling(false)
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                    submit(); true
                } else false
            }
        }
        bar.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        say = button("Say", primary = true) { submit() }.apply { minWidth = dp(76); minHeight = dp(52) }
        bar.addView(say, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        root.addView(bar)

        setContentView(root)
    }

    private fun setBusy(isBusy: Boolean, message: String) {
        busy = isBusy
        say.isEnabled = !isBusy
        say.alpha = if (isBusy) 0.5f else 1f
        status.text = message
        status.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
    }

    private fun renderList() {
        list.removeAllViews()
        if (lines.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Your lines will show up here.\n\nTry: You owe us gold. The tavern keeper said so."
                textSize = 15f
                setTextColor(c(R.color.muted))
                background = rounded(c(R.color.bg), c(R.color.line))
                setPadding(dp(16), dp(16), dp(16), dp(16))
            })
            return
        }
        val sorted = lines.filter { it.starred } + lines.filter { !it.starred }
        sorted.forEach { list.addView(card(it), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }) }
    }

    private fun card(line: Line): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(c(R.color.surface), if (line.starred) c(R.color.brass) else c(R.color.line))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        box.addView(TextView(this).apply {
            text = if (line.starred) "★ ${line.text}" else line.text
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(c(R.color.ink))
            setOnClickListener { play(line) }
        })
        if (line.voices.isNotBlank()) box.addView(TextView(this).apply {
            text = line.voices
            textSize = 12f
            setTextColor(c(R.color.brass))
            setPadding(0, dp(4), 0, 0)
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        val lp = { LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) } }
        row.addView(button("▶ Play", primary = true) { play(line) }, lp())
        row.addView(button("New voices") { remake(line) }, lp())
        row.addView(button("Share") { share(line) }, lp())
        row.addView(button(if (line.starred) "Unpin" else "Pin") { line.starred = !line.starred; saveLines(); renderList() }, lp())
        row.addView(button("Delete") { confirmDelete(line) }, lp())
        box.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        return box
    }

    // ---------- voices screen ----------

    private fun openVoices() {
        if (!loaded) { Toast.makeText(this, "Voices are still loading", Toast.LENGTH_SHORT).show(); return }
        if (busy) { Toast.makeText(this, "One moment, still recording a line", Toast.LENGTH_SHORT).show(); return }
        val offA = offSet("off_accents").toMutableSet()
        val offS = offSet("off_styles").toMutableSet()

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        fun heading(t: String, sub: String) {
            body.addView(TextView(this).apply { text = t; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setTextColor(c(R.color.ink)); setPadding(0, dp(12), 0, 0) })
            body.addView(TextView(this).apply { text = sub; textSize = 13f; setTextColor(c(R.color.muted)); setPadding(0, dp(2), 0, dp(6)) })
        }
        fun row(label: String, detail: String, checked: Boolean, onToggle: (Boolean) -> Unit, onHear: () -> Unit) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply { text = label; textSize = 16f; setTextColor(c(R.color.ink)) })
            texts.addView(TextView(this).apply { text = detail; textSize = 12f; setTextColor(c(R.color.muted)) })
            val cb = CheckBox(this).apply { isChecked = checked; setOnCheckedChangeListener { _, v -> onToggle(v) }; contentDescription = label }
            texts.setOnClickListener { cb.isChecked = !cb.isChecked }
            r.addView(cb)
            r.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(button("Hear") { onHear() }.apply { textSize = 13f; minHeight = dp(40); minimumHeight = dp(40) })
            body.addView(r)
        }

        val sample = "Squawk! You owe us gold, friend."
        heading("Accents", "Untick any you don't want. Hear plays a random voice from that accent.")
        accents.forEach { a ->
            val names = a.voices.joinToString(", ") { it.name.substringBefore(" (") }
            row("${a.label} (${a.voices.size})", "${a.detail}: $names", a.key !in offA,
                { v -> if (v) offA.remove(a.key) else offA.add(a.key) }) { preview(a, Catalog.styles.first(), sample) }
        }
        heading("Speaking styles", "Each chunk also gets a random style. Untick any you don't want.")
        Catalog.styles.forEach { st ->
            row(st.label, "pitch ×${"%.2f".format(st.pitch)} · speed ×${"%.2f".format(st.rate)}", st.key !in offS,
                { v -> if (v) offS.remove(st.key) else offS.add(st.key) }) { preview(accents.first(), st, sample) }
        }

        AlertDialog.Builder(this)
            .setTitle("Your kenku's voices")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Done") { _, _ -> setOff("off_accents", offA); setOff("off_styles", offS); updateCastInfo() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun preview(accent: Accent, style: Style, text: String) {
        if (busy || previewing || !loaded) return
        previewing = true
        val out = File(cacheDir, "preview.wav")
        worker.execute {
            val ok = try { engine.preview(accent, style, text, out); true } catch (e: Throwable) { false }
            main.post {
                previewing = false
                if (isFinishing || isDestroyed) return@post
                if (ok) playFile(out) else Toast.makeText(this, "Couldn't play that sample", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- actions ----------

    private fun submit() {
        val text = input.text.toString().replace(Regex("\\s+"), " ").trim().take(500)
        if (text.isEmpty() || busy || !loaded) return
        input.setText("")
        record(Line(System.currentTimeMillis().toString(), text, ""), isNew = true)
    }

    private fun remake(line: Line) { if (!busy && loaded) record(line, isNew = false) }

    private fun record(line: Line, isNew: Boolean) {
        if (!loaded || busy) return
        val useAccents = activeAccents()
        val useStyles = activeStyles()
        setBusy(true, "Stealing voices…")
        worker.execute {
            try {
                val res = engine.render(line.text, useAccents, useStyles, fileFor(line)) { done, total ->
                    main.post { if (busy) status.text = "Stealing voices… $done of $total" }
                }
                line.voices = res.parts.joinToString("  ·  ") { it.label }
                main.post {
                    if (isFinishing || isDestroyed) return@post
                    if (isNew) lines.add(0, line)
                    saveLines(); renderList(); setBusy(false, ""); play(line)
                }
            } catch (e: Throwable) {
                main.post {
                    if (isFinishing || isDestroyed) return@post
                    setBusy(false, "Couldn't make that line. Try plain words and avoid unusual symbols.")
                }
            }
        }
    }

    private fun fileFor(line: Line) = File(linesDir, "${line.id}.wav")

    private fun play(line: Line) {
        val f = fileFor(line)
        if (!f.exists()) { remake(line); return }
        playFile(f)
    }

    private fun playFile(f: File) {
        player?.release()
        player = try {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(f.absolutePath)
                setOnCompletionListener { mp -> mp.release(); if (player === mp) player = null }
                prepare(); start()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't play that audio. Tap New voices to remake it.", Toast.LENGTH_LONG).show()
            null
        }
    }

    private fun share(line: Line) {
        val f = fileFor(line)
        if (!f.exists()) return
        try {
            val shareDir = File(cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val name = line.text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "kenku" }
            val named = File(shareDir, "kenku-$name.wav")
            f.copyTo(named, overwrite = true)
            val uri = FileProvider.getUriForFile(this, "com.kenku.voice.files", named)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Send kenku audio"))
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't share that line.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(line: Line) {
        AlertDialog.Builder(this)
            .setMessage("Delete “${line.text.take(60)}”?")
            .setPositiveButton("Delete") { _, _ ->
                fileFor(line).delete(); lines.remove(line); saveLines(); renderList()
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    // ---------- storage ----------

    private fun loadLines() {
        lines.clear()
        try {
            if (!indexFile.exists()) return
            val arr = JSONArray(indexFile.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                lines.add(Line(o.getString("id"), o.getString("text"), o.optString("voices"), o.optBoolean("starred")))
            }
        } catch (e: Exception) {}
    }

    private fun saveLines() {
        // Keep the newest 80 unpinned lines; pinned lines are never trimmed.
        var unpinned = lines.count { !it.starred }
        while (unpinned > 80) {
            val old = lines.last { !it.starred }
            fileFor(old).delete(); lines.remove(old); unpinned--
        }
        val arr = JSONArray()
        lines.forEach { arr.put(JSONObject().put("id", it.id).put("text", it.text).put("voices", it.voices).put("starred", it.starred)) }
        try { indexFile.writeText(arr.toString()) } catch (e: Exception) {}
    }
}
