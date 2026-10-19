package com.kenku.voice

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executors

/**
 * App shell: owns the voice engine and shared players, and shows one of three tabs:
 * Speak, Clones and Sounds, switched with a big tab bar at the bottom.
 */
class MainActivity : AppCompatActivity() {

    lateinit var ui: Ui
    lateinit var engine: VoiceEngine
    lateinit var performer: Performer
    lateinit var clones: CloneStore
    lateinit var sfx: SfxPlayer
    /** Every voice-model call runs on this one thread. */
    val engineThread = Executors.newSingleThreadExecutor()
    /** Disk and decoding work that must not block the screen or the voices. */
    val ioThread = Executors.newSingleThreadExecutor()
    val main = Handler(Looper.getMainLooper())
    val prefs by lazy { getSharedPreferences("kenku", MODE_PRIVATE) }
    @Volatile var voicesReady = false
    private var player: MediaPlayer? = null

    private lateinit var speak: SpeakTab
    private lateinit var clonesTab: ClonesTab
    private lateinit var sounds: SoundsTab
    private val tabViews = mutableListOf<View>()
    private val tabButtons = mutableListOf<LinearLayout>()
    private var current = 0
    private var micCallback: ((Boolean) -> Unit)? = null

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micCallback?.invoke(granted); micCallback = null
    }

    val alive get() = !isFinishing && !isDestroyed

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        engine = VoiceEngine(applicationContext)
        performer = Performer(engine)
        clones = CloneStore(applicationContext)
        sfx = SfxPlayer(applicationContext)

        speak = SpeakTab(this)
        clonesTab = ClonesTab(this)
        sounds = SoundsTab(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ui.c(R.color.bg))
            fitsSystemWindows = true
        }
        val content = FrameLayout(this)
        listOf(speak.view, clonesTab.view, sounds.view).forEach { v ->
            content.addView(v, FrameLayout.LayoutParams(-1, -1)); tabViews.add(v)
        }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(tabBar())
        setContentView(root)
        select(prefs.getInt("tab", 0).coerceIn(0, 2))

        engineThread.execute {
            val error = try { engine.load(); null } catch (e: Throwable) { e }
            main.post {
                if (!alive) return@post
                voicesReady = error == null
                speak.onVoicesLoaded(error)
                clonesTab.refresh()
                // Warm up the cloning model in the background so the first cloned line isn't slow.
                if (voicesReady && clones.list().isNotEmpty()) engineThread.execute { engine.loadPocket() }
            }
        }
    }

    private fun tabBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(ui.c(R.color.surface))
            setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
        }
        val tabs = listOf("🗣️" to "Speak", "🎭" to "Clones", "🔔" to "Sounds")
        tabs.forEachIndexed { i, (icon, label) ->
            val b = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumHeight = ui.dp(60)
                setPadding(0, ui.dp(6), 0, ui.dp(6))
                isClickable = true
                isFocusable = true
                contentDescription = "$label tab"
                setOnClickListener { select(i) }
            }
            b.addView(TextView(this).apply { text = icon; textSize = 24f; gravity = Gravity.CENTER })
            b.addView(TextView(this).apply { text = label; textSize = 14f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER })
            bar.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = ui.dp(4); rightMargin = ui.dp(4) })
            tabButtons.add(b)
        }
        return bar
    }

    fun select(i: Int) {
        current = i
        prefs.edit().putInt("tab", i).apply()
        tabViews.forEachIndexed { k, v -> v.visibility = if (k == i) View.VISIBLE else View.GONE }
        tabButtons.forEachIndexed { k, b ->
            val on = k == i
            b.background = if (on) ui.rounded(ui.c(R.color.tab_on), ui.c(R.color.accent), 14) else null
            for (c in 0 until b.childCount) (b.getChildAt(c) as TextView).setTextColor(ui.c(if (on) R.color.accent else R.color.muted))
        }
        if (i == 0) speak.updateCastInfo()
        if (i == 1) clonesTab.refresh()
    }

    /** Asks for the microphone only when the user starts recording a clone. */
    fun withMic(then: (Boolean) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { then(true); return }
        micCallback = then
        micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun playFile(f: File, onDone: (() -> Unit)? = null) {
        stopPlayback()
        player = try {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(f.absolutePath)
                setOnCompletionListener { mp -> mp.release(); if (player === mp) player = null; onDone?.invoke() }
                prepare(); start()
            }
        } catch (e: Exception) {
            toast("Couldn't play that audio.")
            null
        }
    }

    fun stopPlayback() { player?.let { try { it.stop() } catch (e: Exception) {}; it.release() }; player = null }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onStop() {
        super.onStop()
        player?.let { try { if (it.isPlaying) it.pause() } catch (e: Exception) {} }
    }

    override fun onDestroy() {
        stopPlayback()
        sfx.stopAll()
        performer.cancel()
        clonesTab.onDestroy()
        // Free the models on the same thread that uses them, after any line in progress.
        engineThread.execute { engine.release() }
        engineThread.shutdown()
        ioThread.shutdown()
        super.onDestroy()
    }
}
