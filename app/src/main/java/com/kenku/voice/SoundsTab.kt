package com.kenku.voice

import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Sounds tab: a medieval soundboard. Big category buttons across the top
 * (Doors & Locks, Coins & Treasure, Tavern…), a grid of sounds below. Tap a sound
 * to play it, tap again to stop. Star your favorites; search finds any sound by name.
 */
class SoundsTab(private val act: MainActivity) {
    private val ui = act.ui
    val view: View

    private val categories by lazy { try { SfxLibrary.categories(act) } catch (e: Exception) { emptyList() } }
    private lateinit var chipRow: LinearLayout
    private lateinit var grid: LinearLayout
    private lateinit var header: TextView
    private lateinit var sub: TextView
    private lateinit var search: EditText
    private lateinit var stopAll: Button
    private val chips = mutableMapOf<String, LinearLayout>()
    private val tiles = mutableMapOf<String, LinearLayout>()
    private var selected = act.prefs.getString("sfx_cat", null)
    private val favorites: MutableSet<String> = (act.prefs.getStringSet("sfx_fav", emptySet()) ?: emptySet()).toMutableSet()

    init {
        view = build()
        act.sfx.onChange = { act.main.post { refreshTiles(); stopAll.visibility = if (act.sfx.anyPlaying) View.VISIBLE else View.GONE } }
        if (selected == null || (selected != FAV && categories.none { it.key == selected })) selected = categories.firstOrNull()?.key
        showCategory(selected ?: FAV)
    }

    private fun build(): View {
        val root = ui.column()
        val top = ui.column().apply { setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(4)) }
        val titleRow = ui.row()
        titleRow.addView(ui.title("Sounds"), ui.weight())
        stopAll = ui.button("■ Stop all", Ui.Kind.DANGER) { act.sfx.stopAll() }.apply { visibility = View.GONE }
        titleRow.addView(stopAll)
        top.addView(titleRow)
        search = EditText(act).apply {
            id = View.generateViewId()
            hint = "🔍  Search sounds (door, coins, goblin…)"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 16f
            setTextColor(ui.c(R.color.ink)); setHintTextColor(ui.c(R.color.muted))
            background = ui.rounded(ui.c(R.color.surface), ui.c(R.color.line))
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val q = s?.toString()?.trim().orEmpty()
                    if (q.isEmpty()) showCategory(selected ?: FAV) else showSearch(q)
                }
            })
        }
        top.addView(search, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        root.addView(top)

        // Category buttons: large, labeled, scroll sideways.
        chipRow = ui.row().apply { setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8)) }
        addChip(FAV, "⭐", "Favorites")
        categories.forEach { addChip(it.key, it.icon, it.title) }
        root.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) })

        val listCol = ui.column().apply { setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(16)) }
        header = ui.heading("")
        sub = ui.body("", 13f).apply { setPadding(0, ui.dp(2), 0, ui.dp(10)) }
        listCol.addView(header)
        listCol.addView(sub)
        grid = ui.column()
        listCol.addView(grid)
        root.addView(ScrollView(act).apply { addView(listCol); isFillViewport = true }, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun addChip(key: String, icon: String, title: String) {
        val chip = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumWidth = ui.dp(92)
            setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
            isClickable = true; isFocusable = true
            contentDescription = "$title sounds"
            setOnClickListener { search.setText(""); selected = key; act.prefs.edit().putString("sfx_cat", key).apply(); showCategory(key) }
        }
        chip.addView(TextView(act).apply { text = icon; textSize = 26f; gravity = Gravity.CENTER })
        chip.addView(TextView(act).apply { text = title; textSize = 13f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; maxLines = 2 })
        chipRow.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = ui.dp(8) })
        chips[key] = chip
    }

    private fun highlightChip(key: String?) {
        chips.forEach { (k, chip) ->
            val on = k == key
            chip.background = ui.ripple(ui.rounded(ui.c(if (on) R.color.tab_on else R.color.surface), ui.c(if (on) R.color.accent else R.color.line), 14), 14)
            (chip.getChildAt(1) as TextView).setTextColor(ui.c(if (on) R.color.accent else R.color.ink))
        }
    }

    private fun showCategory(key: String) {
        highlightChip(key)
        if (key == FAV) {
            val favs = categories.flatMap { it.sounds }.filter { it.id in favorites }
            header.text = "⭐ Favorites"
            sub.text = if (favs.isEmpty()) "Tap the ☆ on any sound to keep it here for quick use." else "${favs.size} favorite sounds"
            fill(favs, showCategory = true)
            act.sfx.preload(favs)
            return
        }
        val cat = categories.firstOrNull { it.key == key } ?: return
        header.text = "${cat.icon} ${cat.title}"
        sub.text = "${cat.desc} · ${cat.sounds.size} sounds · tap to play, tap again to stop"
        fill(cat.sounds, showCategory = false)
        act.sfx.preload(cat.sounds)
    }

    private fun showSearch(q: String) {
        highlightChip(null)
        val words = q.lowercase().split(Regex("\\s+"))
        val found = categories.flatMap { c -> c.sounds.filter { s ->
            val hay = (s.label + " " + c.title).lowercase()
            words.all { hay.contains(it) }
        } }
        header.text = "Results for “$q”"
        sub.text = if (found.isEmpty()) "No sounds match. Try a simpler word like door, coin, sword or wolf." else "${found.size} sounds"
        fill(found, showCategory = true)
    }

    private fun fill(sounds: List<Sfx>, showCategory: Boolean) {
        grid.removeAllViews()
        tiles.clear()
        val icons = categories.associate { it.key to it.icon }
        sounds.chunked(2).forEach { pair ->
            val row = ui.row(Gravity.TOP)
            pair.forEachIndexed { i, s ->
                val tile = tile(s, if (showCategory) icons[s.category] else null)
                row.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i == 0) rightMargin = ui.dp(5) else leftMargin = ui.dp(5) })
            }
            if (pair.size == 1) row.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = ui.dp(5) })
            grid.addView(row, ui.fill(10))
        }
        refreshTiles()
    }

    private fun tile(s: Sfx, icon: String?): LinearLayout {
        val t = ui.row(Gravity.CENTER_VERTICAL).apply {
            setPadding(ui.dp(12), ui.dp(10), ui.dp(4), ui.dp(10))
            minimumHeight = ui.dp(64)
            isClickable = true; isFocusable = true
            contentDescription = "Play ${s.label}"
            setOnClickListener { v -> ui.tick(v); act.sfx.toggle(s) }
        }
        val texts = ui.column()
        texts.addView(ui.body((icon?.let { "$it " } ?: "") + s.label, 15f, R.color.ink).apply { setTypeface(typeface, Typeface.BOLD) })
        texts.addView(ui.body(if (s.seconds >= 3f) "%.0f s · tap again to stop".format(s.seconds) else "%.1f s".format(s.seconds), 12f))
        t.addView(texts, ui.weight())
        val star = TextView(act).apply {
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6))
            minWidth = ui.dp(44)
            isClickable = true
            setOnClickListener { toggleFavorite(s); text = if (s.id in favorites) "★" else "☆" }
        }
        star.text = if (s.id in favorites) "★" else "☆"
        star.setTextColor(ui.c(R.color.brass))
        star.contentDescription = "Favorite ${s.label}"
        t.addView(star)
        tiles[s.id] = t
        return t
    }

    private fun refreshTiles() {
        tiles.forEach { (id, t) ->
            val on = act.sfx.isPlaying(id)
            t.background = ui.ripple(ui.rounded(ui.c(if (on) R.color.tab_on else R.color.surface), ui.c(if (on) R.color.accent else R.color.line), 12))
        }
    }

    private fun toggleFavorite(s: Sfx) {
        if (!favorites.add(s.id)) favorites.remove(s.id)
        act.prefs.edit().putStringSet("sfx_fav", HashSet(favorites)).apply()
        if (selected == FAV && search.text.isNullOrBlank()) showCategory(FAV)
    }

    companion object { const val FAV = "favorites" }
}
