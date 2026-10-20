package com.kenku.voice

import androidx.core.content.edit
import android.content.Intent
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import java.io.File

/** The main tab: type a line, press Enter, hear it in stolen voices; saved lines below. */
class SpeakTab(private val act: MainActivity) {
    private val ui = act.ui
    val view: View

    private lateinit var list: LinearLayout
    private lateinit var input: EditText
    private lateinit var say: Button
    private lateinit var stop: Button
    private lateinit var status: TextView
    private lateinit var castInfo: TextView
    private lateinit var progress: ProgressBar
    private lateinit var quickWrap: LinearLayout
    private lateinit var quickRow: LinearLayout
    private lateinit var filter: EditText
    private var filterText = ""
    private val filterRender = Runnable { if (act.alive) renderCards() }

    @Volatile private var busy = false
    @Volatile private var streaming = false
    private val linesDir by lazy { File(act.filesDir, "lines").apply { mkdirs() } }
    private val indexFile by lazy { File(act.filesDir, "lines.json") }

    private val lines = mutableListOf<Line>()

    init {
        view = build()
        loadLines()
        renderList()
        updateCastInfo()
        setBusy(true, "Loading the voices… the first launch takes a little longer.")
    }

    fun onVoicesLoaded(error: Throwable?) {
        if (error == null) setBusy(false, "")
        else setBusy(true, "The voices couldn't load (${error.javaClass.simpleName}). Close other apps and reopen Mocking Bird.")
    }

    // ---------- voice choices ----------

    private fun offSet(key: String): Set<String> = act.prefs.getStringSet(key, emptySet()) ?: emptySet()
    private fun setOff(key: String, v: Set<String>) { act.prefs.edit { putStringSet(key, HashSet(v)) } }
    private val mixMode get() = act.prefs.getString("mix", "all") ?: "all"
    private val smooth get() = act.prefs.getBoolean("smooth", true)
    private val wild get() = act.prefs.getBoolean("wild", false)
    private val effect get() = act.prefs.getString("effect", "none") ?: "none"

    private fun activeAccents(): List<Accent> {
        val off = offSet("off_accents")
        return Catalog.accents.filter { it.key !in off }.ifEmpty { Catalog.accents }
    }

    private fun activeStyles(): List<Style> {
        val off = offSet("off_styles")
        return Catalog.styles.filter { it.key !in off }.ifEmpty { listOf(Catalog.styles.first()) }
    }

    private fun mixClones() = act.clones.list().filter { it.inMix }

    private fun speakers(): List<Speaker> {
        val builtin = activeAccents().flatMap { a -> a.voices.map { Speaker.Builtin(it, a) } }
        val cloned = mixClones().map { Speaker.Cloned(it) }
        return when (mixMode) {
            "clones" -> cloned.ifEmpty { builtin }
            "builtin" -> builtin
            else -> builtin + cloned
        }
    }

    fun updateCastInfo() {
        val a = activeAccents()
        val clonesN = mixClones().size
        val styles = activeStyles().size
        castInfo.text = when (mixMode) {
            "clones" -> if (clonesN == 0) "Clones only — no clones yet, using built-in voices" else "$clonesN cloned voices · $styles styles"
            "builtin" -> "${a.sumOf { it.voices.size }} voices · ${a.size} accents · $styles styles"
            else -> "${a.sumOf { it.voices.size }} voices · ${a.size} accents" + (if (clonesN > 0) " · $clonesN clones" else "") + " · $styles styles"
        }
    }

    // ---------- layout ----------

    private fun build(): View {
        val root = ui.column()
        val header = ui.column().apply { setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(6)) }
        val titleRow = ui.row()
        titleRow.addView(ImageView(act).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            background = ui.rounded(ui.c(R.color.icon_bg), null, 12)
            contentDescription = null
        }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)).apply { rightMargin = ui.dp(10) })
        titleRow.addView(ui.title("Mocking Bird"), ui.weight())
        titleRow.addView(ui.button("Voices") { openVoices() }.apply { textSize = 14f })
        header.addView(titleRow)
        header.addView(ui.body("Type and press Enter. Every 2–3 words come out in a new stolen voice. ＋ Add drops in phrases and sounds, like: Pay up [coins] or else.").apply { setPadding(0, ui.dp(4), 0, 0) })
        castInfo = ui.body("", 12f, R.color.brass).apply { typeface = Typeface.MONOSPACE; setPadding(0, ui.dp(4), 0, 0) }
        header.addView(castInfo)
        val statusRow = ui.row().apply { setPadding(0, ui.dp(6), 0, 0) }
        // TalkBack announces progress changes ("Stealing voices… 3 of 9") as they happen.
        status = ui.body("", 14f, R.color.accent).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        statusRow.addView(status, ui.weight())
        stop = ui.button("■ Stop", Ui.Kind.DANGER) { stopSpeaking() }.apply { visibility = View.GONE; minHeight = ui.dp(40); minimumHeight = ui.dp(40) }
        statusRow.addView(stop)
        header.addView(statusRow)
        progress = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0; visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(ui.c(R.color.accent))
            // Decorative: the status line above already announces the same progress.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        header.addView(progress, LinearLayout.LayoutParams(-1, ui.dp(6)).apply { topMargin = ui.dp(4) })
        root.addView(header)

        // Quick lines: pinned lines as big one-tap buttons, always in reach during combat.
        quickWrap = ui.column().apply { setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(4)); visibility = View.GONE }
        quickWrap.addView(ui.body("⚡ Quick lines (pin a line to add it here)", 12f, R.color.brass).apply { setPadding(0, 0, 0, ui.dp(4)) })
        quickRow = ui.row()
        quickWrap.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(quickRow) })
        root.addView(quickWrap)

        list = ui.column().apply { setPadding(ui.dp(16), ui.dp(6), ui.dp(16), ui.dp(16)) }
        filter = EditText(act).apply {
            id = R.id.speak_filter
            hint = "🔍  Find a saved line"
            setSingleLine(true)
            textSize = 15f
            setTextColor(ui.c(R.color.ink)); setHintTextColor(ui.c(R.color.muted))
            background = ui.rounded(ui.c(R.color.surface), ui.c(R.color.line))
            setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) {
                    filterText = e?.toString()?.trim().orEmpty()
                    // Debounced: re-render once the user pauses, not on every keystroke.
                    act.main.removeCallbacks(filterRender)
                    act.main.postDelayed(filterRender, 150)
                }
            })
        }
        // The search box sits outside the card list so typing in it never loses focus.
        val host = ui.column()
        host.addView(filter, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = ui.dp(16); rightMargin = ui.dp(16); topMargin = ui.dp(6) })
        host.addView(list)
        root.addView(ScrollView(act).apply { addView(host); isFillViewport = true }, LinearLayout.LayoutParams(-1, 0, 1f))

        val bar = ui.row(Gravity.BOTTOM).apply {
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            setBackgroundColor(ui.c(R.color.surface))
        }
        input = EditText(act).apply {
            // A STABLE id: generateViewId() changes every recreation, so Android could
            // never restore the half-typed line when auto dark mode flips at sunset.
            id = R.id.speak_input
            hint = "What does your kenku say?"
            textSize = 18f
            setTextColor(ui.c(R.color.ink))
            setHintTextColor(ui.c(R.color.muted))
            background = ui.rounded(ui.c(R.color.bg), ui.c(R.color.line))
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
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
        bar.addView(ui.button("＋ Add") { addMenu() }.apply { textSize = 13f; minHeight = ui.dp(52); contentDescription = "Add a phrase or sound effect" },
            LinearLayout.LayoutParams(-2, -2).apply { rightMargin = ui.dp(8) })
        bar.addView(input, ui.weight())
        say = ui.button("Say", Ui.Kind.PRIMARY) { ui.tick(input); submit() }.apply { minWidth = ui.dp(76); minHeight = ui.dp(52) }
        bar.addView(say, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = ui.dp(8) })
        root.addView(bar)
        return root
    }

    private fun setBusy(isBusy: Boolean, message: String) {
        busy = isBusy
        say.isEnabled = !isBusy
        say.alpha = if (isBusy) 0.5f else 1f
        status.text = message
        status.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
        if (!isBusy) progress.visibility = View.GONE
    }

    private fun renderQuick() {
        quickRow.removeAllViews()
        val pinned = lines.filter { it.starred }
        quickWrap.visibility = if (pinned.isEmpty()) View.GONE else View.VISIBLE
        pinned.forEach { l ->
            val label = if (l.text.length > 28) l.text.take(26).trimEnd() + "…" else l.text
            quickRow.addView(ui.button("▶ $label", Ui.Kind.NORMAL) { play(l) }.apply {
                minHeight = ui.dp(52); textSize = 15f; contentDescription = "Play ${l.text}"
            }, ui.wrap())
        }
    }

    private fun renderList() {
        renderQuick()
        renderCards()
    }

    private var lastRenderKey: String? = null

    private fun renderCards() {
        filter.visibility = if (lines.size >= 6) View.VISIBLE else View.GONE
        if (lines.size < 6) filterText = ""
        val sorted = LineBook.visible(lines, filterText)
        // Rebuilding 80 cards per keystroke is jank; skip when nothing visible changed.
        val key = "${lines.isEmpty()}|$filterText|" + LineBook.renderKey(sorted)
        if (key == lastRenderKey) return
        lastRenderKey = key
        list.removeAllViews()
        if (lines.isEmpty()) {
            list.addView(ui.body("Your lines will show up here.\n\nTry: You owe us gold. The tavern keeper said so.", 15f).apply {
                background = ui.rounded(ui.c(R.color.bg), ui.c(R.color.line))
                setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))
            })
            return
        }
        if (sorted.isEmpty()) list.addView(ui.body("No saved lines match “$filterText”.", 14f))
        sorted.forEach { list.addView(card(it), ui.fill()) }
    }

    private fun card(line: Line): View {
        val box = ui.card(highlight = line.starred)
        box.addView(ui.body(if (line.starred) "★ ${line.text}" else line.text, 17f, R.color.ink).apply {
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener { play(line) }
        })
        if (line.voices.isNotBlank()) box.addView(ui.body(line.voices, 12f, R.color.brass).apply { setPadding(0, ui.dp(4), 0, 0) })
        val row = ui.row().apply { setPadding(0, ui.dp(8), 0, 0) }
        row.addView(ui.button("▶ Play", Ui.Kind.PRIMARY) { play(line) }, ui.wrap())
        row.addView(ui.button("New voices") { remake(line) }, ui.wrap())
        row.addView(ui.button("Share") { share(line) }, ui.wrap())
        row.addView(ui.button(if (line.starred) "Unpin" else "Pin") { line.starred = !line.starred; saveLines(); renderList() }, ui.wrap())
        row.addView(ui.button("Delete", Ui.Kind.DANGER) { confirmDelete(line) }, ui.wrap())
        box.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        return box
    }

    // ---------- voices screen ----------

    private fun openVoices() {
        if (!act.voicesReady) { act.toast("Voices are still loading"); return }
        if (busy) { act.toast("One moment, still speaking a line"); return }
        val offA = offSet("off_accents").toMutableSet()
        val offS = offSet("off_styles").toMutableSet()
        var mode = mixMode
        var smoothOn = smooth
        var wildOn = wild
        var awakeOn = act.prefs.getBoolean("awake", true)
        var effectKey = effect

        val body = ui.column().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8)) }
        fun heading(t: String, sub: String) {
            body.addView(ui.heading(t).apply { setPadding(0, ui.dp(12), 0, 0) })
            body.addView(ui.body(sub, 13f).apply { setPadding(0, ui.dp(2), 0, ui.dp(6)) })
        }
        fun row(label: String, detail: String, checked: Boolean, onToggle: (Boolean) -> Unit, onHear: (() -> Unit)?) {
            val r = ui.row().apply { setPadding(0, ui.dp(4), 0, ui.dp(4)) }
            val texts = ui.column()
            texts.addView(ui.body(label, 16f, R.color.ink))
            texts.addView(ui.body(detail, 12f))
            val cb = CheckBox(act).apply { isChecked = checked; setOnCheckedChangeListener { _, v -> onToggle(v) }; contentDescription = label }
            texts.setOnClickListener { cb.isChecked = !cb.isChecked }
            r.addView(cb)
            r.addView(texts, ui.weight())
            if (onHear != null) r.addView(ui.button("Hear") { onHear() }.apply { textSize = 13f; minHeight = ui.dp(40); minimumHeight = ui.dp(40) })
            body.addView(r)
        }

        heading("Voice mix", "Which voices your kenku steals from.")
        val clonesN = mixClones().size
        val group = RadioGroup(act)
        listOf("all" to "All voices (built-in + clones)", "builtin" to "Built-in voices only", "clones" to "My clones only ($clonesN)").forEach { (k, t) ->
            group.addView(RadioButton(act).apply {
                id = View.generateViewId(); text = t; textSize = 15f; setTextColor(ui.c(R.color.ink))
                isChecked = k == mode
                setOnCheckedChangeListener { _, on -> if (on) mode = k }
            })
        }
        body.addView(group)
        row("Smooth blending", "Softer handovers between voices", smoothOn, { smoothOn = it }, null)
        row("Wild styles", "Much bigger pitch and speed changes. Fun, but some voices get hard to understand.", wildOn, { wildOn = it }, null)
        heading("Voice effect", "Atmosphere on the whole line, as if the kenku is replaying something it overheard.")
        val fxGroup = RadioGroup(act)
        LineEffect.choices.forEach { (k, t) ->
            fxGroup.addView(RadioButton(act).apply {
                id = View.generateViewId(); text = t; textSize = 15f; setTextColor(ui.c(R.color.ink))
                isChecked = k == effectKey
                setOnCheckedChangeListener { _, on -> if (on) effectKey = k }
            })
        }
        body.addView(fxGroup)
        body.addView(ui.button("Hear this effect") {
            val a = Catalog.accents.first()
            preview(Speaker.Builtin(a.voices.random(), a), Catalog.styles.first(), "Did you hear that? Something moved in the dark.", effectKey)
        }.apply { textSize = 13f; minHeight = ui.dp(40); minimumHeight = ui.dp(40) }, ui.wrap())
        heading("Screen", "")
        row("Keep screen awake", "Stops the phone from locking while the app is open, handy at the table", awakeOn, { awakeOn = it }, null)

        val sample = "Squawk! You owe us gold, friend."
        heading("Accents", "Untick any you don't want. Hear plays a random voice from that accent.")
        Catalog.accents.forEach { a ->
            val names = a.voices.joinToString(", ") { it.name.substringBefore(" (") }
            row("${a.label} (${a.voices.size})", "${a.detail}: $names", a.key !in offA,
                { v -> if (v) offA.remove(a.key) else offA.add(a.key) }) {
                val v = a.voices.random()
                preview(Speaker.Builtin(v, a), Catalog.styles.first(), sample)
            }
        }
        heading("Speaking styles", "Each snippet also gets a random style. Untick any you don't want.")
        Catalog.styles.forEach { st ->
            row(st.label, "pitch ×${"%.2f".format(st.pitch)} · speed ×${"%.2f".format(st.rate)} (wild: ×${"%.2f".format(st.wildPitch)} · ×${"%.2f".format(st.wildRate)})", st.key !in offS,
                { v -> if (v) offS.remove(st.key) else offS.add(st.key) }) {
                val a = Catalog.accents.first()
                preview(Speaker.Builtin(a.voices.random(), a), st, sample)
            }
        }

        AlertDialog.Builder(act)
            .setTitle("Your kenku's voices")
            .setView(ScrollView(act).apply { addView(body) })
            .setPositiveButton("Done") { _, _ ->
                setOff("off_accents", offA); setOff("off_styles", offS)
                act.prefs.edit { putString("mix", mode); putBoolean("smooth", smoothOn); putBoolean("wild", wildOn); putBoolean("awake", awakeOn); putString("effect", effectKey) }
                act.applyKeepAwake()
                updateCastInfo()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private var previewing = false
    private fun preview(speaker: Speaker, style: Style, text: String, fxKey: String = "none") {
        if (busy || previewing || !act.voicesReady) return
        previewing = true
        val out = File(act.cacheDir, "preview.wav")
        act.engineThread.execute {
            val ok = try {
                val fx = LineEffect(fxKey)
                val dry = fx.process(act.engine.speak(text, speaker, style, smooth, wild))
                AudioDsp.writeWav(dry + fx.tail(), out); true
            } catch (e: Throwable) { false }
            act.main.post {
                previewing = false
                if (!act.alive) return@post
                if (ok) act.playFile(out) else act.toast("Couldn't play that sample")
            }
        }
    }

    /** The ＋ Add button: a ready-made phrase or a sound effect. */
    private fun addMenu() {
        AlertDialog.Builder(act)
            .setTitle("Add to your line")
            .setItems(arrayOf("📜  A phrase from the phrasebook", "🔔  A sound effect")) { _, i -> if (i == 0) pickPhrase() else pickSound() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickPhrase() {
        val sections = Phrasebook.sections
        AlertDialog.Builder(act)
            .setTitle("📜 Phrasebook")
            .setItems(sections.map { it.first }.toTypedArray()) { _, i ->
                val (title, phrases) = sections[i]
                AlertDialog.Builder(act)
                    .setTitle(title)
                    .setItems(phrases.map { "“$it”" }.toTypedArray()) { _, j -> phraseActions(phrases[j]) }
                    .setNegativeButton("Back") { _, _ -> pickPhrase() }
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** A chosen phrase can go into the text box to edit, or straight onto the ⚡ Quick row. */
    private fun phraseActions(text: String) {
        AlertDialog.Builder(act)
            .setTitle("“$text”")
            .setItems(arrayOf("✏️  Put it in the text box", "⚡  Pin as a Quick line")) { _, i ->
                if (i == 0) insert(text) else pinPhrase(text)
            }
            .setNegativeButton("Back") { _, _ -> pickPhrase() }
            .show()
    }

    private fun pinPhrase(text: String) {
        LineBook.pin(lines, text, System.currentTimeMillis().toString())
        saveLines()
        renderList()
        act.toast("⚡ Pinned — tap it up top to hear it")
    }

    /** Two taps: pick a category (any sound from it) or a specific sound; inserts a [tag] at the cursor. */
    private fun pickSound() {
        val cats = try { SfxLibrary.categories(act) } catch (e: Exception) { emptyList() }
        if (cats.isEmpty()) return
        AlertDialog.Builder(act)
            .setTitle("Add a sound to the line")
            .setItems(cats.map { "${it.icon}  ${it.title}" }.toTypedArray()) { _, i ->
                val c = cats[i]
                val labels = listOf("🎲  Any ${c.title.lowercase()} sound") + c.sounds.map { "▸  ${it.label}" }
                AlertDialog.Builder(act)
                    .setTitle("${c.icon} ${c.title}")
                    .setItems(labels.toTypedArray()) { _, j -> insertTag(if (j == 0) c.key else c.sounds[j - 1].label) }
                    .setNegativeButton("Back") { _, _ -> pickSound() }
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun insert(raw: String) {
        val t = input.text ?: return
        val at = input.selectionStart.coerceIn(0, t.length)
        val before = if (at > 0 && !t[at - 1].isWhitespace()) " " else ""
        val ins = "$before$raw "
        t.insert(at, ins)
        input.setSelection((at + ins.length).coerceAtMost(input.text.length))
        input.requestFocus()
    }

    private fun insertTag(tag: String) {
        val t = input.text ?: return
        val at = input.selectionStart.coerceIn(0, t.length)
        val before = if (at > 0 && !t[at - 1].isWhitespace()) " " else ""
        val ins = "$before[$tag] "
        t.insert(at, ins)
        input.setSelection((at + ins.length).coerceAtMost(input.text.length))
        input.requestFocus()
    }

    // ---------- actions ----------

    private fun submit() {
        val raw = input.text.toString().replace(Regex("\\s+"), " ").trim()
        if (raw.isEmpty() || busy || !act.voicesReady) return
        // Cap long pastes at a word boundary, and say so instead of trimming silently.
        val text = if (raw.length <= 600) raw else raw.take(600).substringBeforeLast(' ').ifBlank { raw.take(600) }
        if (text.length < raw.length) act.toast("Long speech — speaking the first ${text.split(' ').size} words.")
        input.setText("")
        record(Line(System.currentTimeMillis().toString(), text, ""), isNew = true)
    }

    private fun remake(line: Line) { if (!busy && act.voicesReady) record(line, isNew = false) }

    private fun record(line: Line, isNew: Boolean) {
        val appCtx = act.applicationContext
        val opts = Performer.Options(speakers(), activeStyles(), smooth, wild, effect) { tag ->
            SfxLibrary.find(appCtx, tag)?.let { fx ->
                try {
                    val raw = if (fx.isUserFile) AudioDsp.readWav(java.io.File(fx.file))?.let { (x, r) -> AudioDsp.resample(x, r) } ?: throw IllegalStateException("unreadable")
                    else AudioImport.decodeAsset(appCtx, fx.file, 12)
                    AudioDsp.matchLoudness(AudioDsp.trim(raw, 0.004f), 0.07f) to fx.label
                }
                catch (e: Exception) { null }
            }
        }
        act.stopPlayback()
        setBusy(true, "Stealing voices…")
        stop.visibility = View.VISIBLE
        progress.progress = 0
        progress.visibility = View.VISIBLE
        streaming = true
        act.duck(true)
        act.engineThread.execute {
            try {
                val res = act.performer.perform(line.text, opts, fileFor(line),
                    onProgress = { done, total -> act.main.post { if (busy) { status.text = "Stealing voices… $done of $total"; progress.progress = if (total > 0) done * 100 / total else 0 } } },
                    onStarted = { act.main.post { if (busy) status.text = "Speaking…" } },
                    onPlaybackDone = { act.main.post { streaming = false; act.duck(false); if (!busy) stop.visibility = View.GONE } })
                line.voices = res.parts.joinToString("  ·  ")
                act.main.post {
                    if (!act.alive) return@post
                    if (isNew) lines.add(0, line)
                    saveLines(); renderList(); setBusy(false, "")
                    if (!streaming) stop.visibility = View.GONE
                }
            } catch (e: InterruptedException) {
                act.main.post { if (act.alive) { setBusy(false, ""); stop.visibility = View.GONE } }
            } catch (e: Throwable) {
                act.main.post {
                    if (!act.alive) return@post
                    stop.visibility = View.GONE
                    setBusy(false, if (e.message?.contains("Cloning") == true)
                        "A cloned voice couldn't load. Untick it in the Clones tab and try again."
                    else "Couldn't make that line. Try plain words and avoid unusual symbols.")
                    // A new line that failed must not take the typed text down with it.
                    if (isNew && input.text.isNullOrBlank()) { input.setText(line.text); input.setSelection(line.text.length) }
                }
            }
        }
    }

    private fun stopSpeaking() {
        act.performer.cancel()
        act.stopPlayback()
        stop.visibility = View.GONE
    }

    private fun fileFor(line: Line) = File(linesDir, "${line.id}.wav")

    private fun play(line: Line) {
        val f = fileFor(line)
        if (!f.exists()) { remake(line); return }
        act.performer.cancel()
        stop.visibility = View.VISIBLE
        act.playFile(f) { stop.visibility = View.GONE; act.duck(false) }
        act.duck(true)
    }

    private fun share(line: Line) {
        val f = fileFor(line)
        if (!f.exists()) return
        try {
            val shareDir = File(act.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val name = line.text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "kenku" }
            val named = File(shareDir, "kenku-$name.wav")
            f.copyTo(named, overwrite = true)
            val uri = FileProvider.getUriForFile(act, "com.kenku.voice.files", named)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            act.startActivity(Intent.createChooser(send, "Send kenku audio"))
        } catch (e: Exception) {
            act.toast("Couldn't share that line.")
        }
    }

    private fun confirmDelete(line: Line) {
        AlertDialog.Builder(act)
            .setMessage("Delete “${line.text.take(60)}”?")
            .setPositiveButton("Delete") { _, _ -> fileFor(line).delete(); lines.remove(line); saveLines(); renderList() }
            .setNegativeButton("Keep", null)
            .show()
    }

    // ---------- storage ----------

    private fun loadLines() {
        lines.clear()
        try { if (indexFile.exists()) lines.addAll(LineBook.fromJson(indexFile.readText())) } catch (e: Exception) {}
    }

    private fun saveLines() {
        LineBook.evict(lines).forEach { File(linesDir, "$it.wav").delete() }
        try { FileSafe.writeText(indexFile, LineBook.toJson(lines)) } catch (e: Exception) {}
    }
}
