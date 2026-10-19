package com.kenku.voice

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var engine: KenkuEngine
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null

    private lateinit var list: LinearLayout
    private lateinit var input: EditText
    private lateinit var say: Button
    private lateinit var status: TextView

    private val linesDir by lazy { File(filesDir, "lines").apply { mkdirs() } }
    private val indexFile by lazy { File(filesDir, "lines.json") }
    private val tmpDir by lazy { File(cacheDir, "chunks") }

    data class Line(val id: String, val text: String, var voices: String)
    private val lines = mutableListOf<Line>()

    private fun c(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        loadLines()
        renderList()
        setBusy(true, "Waking up the voices…")
        engine = KenkuEngine(this) { ok ->
            main.post {
                if (ok) setBusy(false, "")
                else setBusy(true, "No text-to-speech engine found. Install \"Speech Services by Google\" from the Play Store, then reopen.")
            }
        }
    }

    override fun onDestroy() {
        player?.release()
        engine.shutdown()
        worker.shutdown()
        super.onDestroy()
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
        minHeight = dp(44); minimumHeight = dp(44)
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
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(button("More voices") { openVoiceSettings() }.apply { textSize = 13f })
        header.addView(titleRow)
        header.addView(TextView(this).apply {
            text = "Type and press Enter. Every 2–3 words come out in a new stolen voice. Tap a saved line to play it again."
            textSize = 14f
            setTextColor(c(R.color.muted))
            setPadding(0, dp(4), 0, 0)
        })
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
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(c(R.color.surface))
        }
        input = EditText(this).apply {
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
        bar.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        say = button("Say", primary = true) { submit() }.apply { minWidth = dp(76); minHeight = dp(52) }
        bar.addView(say, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        root.addView(bar)

        setContentView(root)
    }

    private fun setBusy(busy: Boolean, message: String) {
        say.isEnabled = !busy
        say.alpha = if (busy) 0.5f else 1f
        status.text = message
        status.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
    }

    private fun renderList() {
        list.removeAllViews()
        if (lines.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Your lines will show up here.\nTry: You owe us gold. The tavern keeper said so."
                textSize = 15f
                setTextColor(c(R.color.muted))
                background = rounded(c(R.color.bg), c(R.color.line))
                setPadding(dp(16), dp(16), dp(16), dp(16))
            })
            return
        }
        lines.forEach { list.addView(card(it), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }) }
    }

    private fun card(line: Line): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(c(R.color.surface), c(R.color.line))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        box.addView(TextView(this).apply {
            text = line.text
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(c(R.color.ink))
            setOnClickListener { play(line) }
        })
        box.addView(TextView(this).apply {
            text = line.voices
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(c(R.color.brass))
            setPadding(0, dp(4), 0, dp(8))
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val lp = { LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) } }
        row.addView(button("▶ Play", primary = true) { play(line) }, lp())
        row.addView(button("New voices") { remake(line) }, lp())
        row.addView(button("Share") { share(line) }, lp())
        row.addView(button("✕") { delete(line) }.apply { contentDescription = "Delete line" }, lp())
        box.addView(row)
        return box
    }

    // ---------- actions ----------

    private fun submit() {
        val text = input.text.toString().replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty() || !say.isEnabled) return
        input.setText("")
        val line = Line(System.currentTimeMillis().toString(), text, "")
        record(line, isNew = true)
    }

    private fun remake(line: Line) {
        if (!say.isEnabled) return
        record(line, isNew = false)
    }

    private fun record(line: Line, isNew: Boolean) {
        if (!engine.isReady) return
        setBusy(true, "Stealing voices…")
        worker.execute {
            try {
                val res = engine.render(line.text, fileFor(line), tmpDir)
                line.voices = res.parts.joinToString("  ·  ") { it.voiceLabel }
                main.post {
                    if (isNew) lines.add(0, line)
                    saveLines(); renderList(); setBusy(false, ""); play(line)
                }
            } catch (e: Exception) {
                main.post { setBusy(false, "Couldn't make that line. Try simpler words, or tap More voices to install voices.") }
            }
        }
    }

    private fun fileFor(line: Line) = File(linesDir, "${line.id}.wav")

    private fun play(line: Line) {
        val f = fileFor(line)
        if (!f.exists()) { remake(line); return }
        player?.release()
        player = try {
            MediaPlayer().apply {
                setDataSource(f.absolutePath)
                setOnCompletionListener { it.release(); if (player === it) player = null }
                prepare(); start()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't play that line. Tap New voices to remake it.", Toast.LENGTH_LONG).show()
            null
        }
    }

    private fun share(line: Line) {
        val f = fileFor(line)
        if (!f.exists()) return
        val shareName = line.text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "kenku" }
        val named = File(linesDir, "kenku-$shareName.wav")
        f.copyTo(named, overwrite = true)
        val uri = FileProvider.getUriForFile(this, "com.kenku.voice.files", named)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Send kenku audio"))
    }

    private fun delete(line: Line) {
        fileFor(line).delete()
        lines.remove(line)
        saveLines(); renderList()
    }

    private fun openVoiceSettings() {
        try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
        catch (e: ActivityNotFoundException) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (e: Exception) {}
            Toast.makeText(this, "Open Settings → Text-to-speech to add voices", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- storage ----------

    private fun loadLines() {
        lines.clear()
        try {
            val arr = JSONArray(indexFile.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                lines.add(Line(o.getString("id"), o.getString("text"), o.optString("voices")))
            }
        } catch (e: Exception) {}
    }

    private fun saveLines() {
        while (lines.size > 60) { val old = lines.removeAt(lines.size - 1); fileFor(old).delete() }
        val arr = JSONArray()
        lines.forEach { arr.put(JSONObject().put("id", it.id).put("text", it.text).put("voices", it.voices)) }
        indexFile.writeText(arr.toString())
    }
}
