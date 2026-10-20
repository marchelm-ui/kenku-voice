package com.kenku.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/**
 * If the app ever crashes, the stack trace is saved; on the next launch the user is
 * offered a one-tap way to share it. Without Play Console, this is the only path a
 * real-device fault has back to the developer.
 */
object CrashReporter {

    private fun file(ctx: Context) = File(ctx.filesDir, "last-crash.txt")

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val device = try { "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}" } catch (x: Throwable) { "unknown device" }
                file(app).writeText(format(thread.name, e, BuildConfig.VERSION_NAME, device, Date().toString()))
            } catch (x: Throwable) {
                // Never let the reporter itself cause trouble while the app is dying.
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Pure formatter, unit-tested: everything needed to debug, capped so sharing stays light. */
    fun format(threadName: String, e: Throwable, version: String, device: String, time: String): String {
        val stack = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
        return buildString {
            appendLine("Mocking Bird crash report")
            appendLine("Version: $version")
            appendLine("Time: $time")
            appendLine("Device: $device")
            appendLine("Thread: $threadName")
            appendLine()
            append(stack)
        }.take(MAX_CHARS)
    }

    fun pending(ctx: Context): String? = try {
        file(ctx).takeIf { it.exists() }?.readText()?.take(MAX_CHARS)
    } catch (e: Exception) { null }

    fun clear(ctx: Context) { try { file(ctx).delete() } catch (e: Exception) {} }

    fun shareIntent(report: String): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Mocking Bird crash report")
        putExtra(Intent.EXTRA_TEXT, report)
    }

    const val MAX_CHARS = 16000
}
