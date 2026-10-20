package com.kenku.voice

import androidx.core.content.edit
import android.Manifest
import android.content.Intent
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
    /** Every voice-model call runs on this one thread (shared across screen restarts). */
    val engineThread get() = VoiceEngine.thread
    /**
     * Disk and decoding work that must not block the screen or the voices. Process-wide,
     * like the engine thread: a clone save queued just before the activity recreates
     * (auto dark mode, split screen) must finish, not hit a shut-down executor.
     */
    val ioThread get() = sharedIo
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
        CrashReporter.install(this)
        ui = Ui(this)
        engine = VoiceEngine.get(this)
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
        root.addView(View(this).apply { setBackgroundColor(ui.c(R.color.line)) }, LinearLayout.LayoutParams(-1, ui.dp(1)))
        root.addView(tabBar())
        setContentView(root)
        select(prefs.getInt("tab", 0).coerceIn(0, 2))
        applyKeepAwake()
        offerCrashReport()

        engineThread.execute {
            val error = try { engine.load(); null } catch (e: Throwable) { e }
            main.post {
                if (!alive) return@post
                voicesReady = error == null
                speak.onVoicesLoaded(error)
                clonesTab.refresh()
                // Warm up in the background so the first real line (and first cloned line) start quickly.
                if (voicesReady) engineThread.execute { engine.warmUp() }
                if (voicesReady) engineThread.execute {
                    // Warm the cloning path with a real reference so the first cloned line is quick.
                    val c = clones.list().let { all -> all.firstOrNull { it.inMix } ?: all.firstOrNull() }
                    if (c != null) try { engine.warmUpPocket(c.samples()) } catch (e: Throwable) {}
                }
            }
        }
    }

    /** After a crash, offer to share the saved report so the fault can actually get fixed. */
    private fun offerCrashReport() {
        val report = CrashReporter.pending(this) ?: return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Mocking Bird hit a problem last time")
            .setMessage("A crash report was saved (no personal data, just what the app was doing). Share it with the developer so it can be fixed?")
            .setPositiveButton("Share") { _, _ ->
                try { startActivity(Intent.createChooser(CrashReporter.shareIntent(report), "Send crash report")) } catch (e: Exception) {}
                CrashReporter.clear(this)
            }
            .setNegativeButton("Delete") { _, _ -> CrashReporter.clear(this) }
            .setCancelable(true)
            .show()
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
                setOnClickListener { v -> ui.tick(v); select(i) }
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
        prefs.edit { putInt("tab", i) }
        tabViews.forEachIndexed { k, v -> v.visibility = if (k == i) View.VISIBLE else View.GONE }
        tabButtons.forEachIndexed { k, b ->
            val on = k == i
            b.background = ui.ripple(if (on) ui.rounded(ui.c(R.color.tab_on), ui.c(R.color.accent), 14) else null, 14)
            for (c in 0 until b.childCount) (b.getChildAt(c) as TextView).setTextColor(ui.c(if (on) R.color.accent else R.color.muted))
        }
        if (i == 0) speak.updateCastInfo()
        if (i == 1) clonesTab.refresh()
    }

    /** Jumps to the Speak tab and drops text (such as a [sound] tag) into the line being typed. */
    fun insertIntoSpeak(text: String) { select(0); speak.insert(text) }

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
                // An async error must release the player and fire onDone, or the Stop
                // button and the ducked audio focus stay stuck.
                setOnErrorListener { mp, _, _ -> mp.release(); if (player === mp) player = null; onDone?.invoke(); true }
                prepare(); start()
            }
        } catch (e: Exception) {
            toast("Couldn't play that audio.")
            null
        }
    }

    fun stopPlayback() { player?.let { try { it.stop() } catch (e: Exception) {}; it.release(); duck(false) }; player = null }

    private val audio by lazy { getSystemService(AUDIO_SERVICE) as android.media.AudioManager }
    private val focus by lazy {
        android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            // When something more important takes the audio (a phone call), the kenku goes quiet.
            .setOnAudioFocusChangeListener { change ->
                if (change == android.media.AudioManager.AUDIOFOCUS_LOSS ||
                    change == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    performer.cancel()
                    stopPlayback()
                    sfx.stopAll()
                }
            }
            .build()
    }

    /** While the kenku talks, music or other apps briefly lower their volume. */
    fun duck(on: Boolean) {
        try { if (on) audio.requestAudioFocus(focus) else audio.abandonAudioFocusRequest(focus) } catch (e: Exception) {}
    }

    /** Keeps the screen from sleeping mid-session (on by default; switch in Voices). */
    fun applyKeepAwake() {
        if (prefs.getBoolean("awake", true)) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        stopPlayback()
        sfx.release()
        performer.cancel()
        clonesTab.onDestroy()
        // The voice models and the io thread stay alive for the next time the screen
        // opens; Android frees them with the app's process when it's no longer needed.
        super.onDestroy()
    }

    companion object {
        private val sharedIo = Executors.newSingleThreadExecutor()
    }
}
