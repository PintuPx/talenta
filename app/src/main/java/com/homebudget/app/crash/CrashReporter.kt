package com.homebudget.app.crash

import android.content.Context
import android.os.Build
import java.io.File
import java.time.Instant

/**
 * Private crash reports. Nothing is ever sent automatically: when the app crashes, a short report is
 * written to the app's private storage, and on the next start the user may choose to share it.
 *
 * The report contains the app version, Android version, device model, exception class names and code
 * locations. Exception MESSAGES are dropped (they could contain user input such as amounts or notes),
 * except for a few exception types whose messages only describe code.
 */
object CrashReporter {
    private const val DIR = "crash"
    private const val FILE = "last-crash.txt"
    private const val MAX_FRAMES = 40
    private const val MAX_CAUSES = 5

    /** Exception types whose messages describe code only, never user data. */
    private val SAFE_MESSAGE_TYPES = setOf(
        "java.lang.NullPointerException", "java.lang.ClassCastException", "java.lang.NoSuchMethodError",
        "java.lang.NoSuchFieldError", "java.lang.NoClassDefFoundError", "java.lang.ClassNotFoundException",
        "java.lang.AbstractMethodError", "java.lang.UnsatisfiedLinkError",
    )

    fun install(context: Context, versionName: String, versionCode: Long) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, report(error, versionName, versionCode, Instant.now().toString(), Build.VERSION.SDK_INT, "${Build.MANUFACTURER} ${Build.MODEL}")) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun file(context: Context) = File(File(context.filesDir, DIR), FILE)

    private fun write(context: Context, text: String) {
        val f = file(context)
        f.parentFile?.mkdirs()
        f.writeText(text)
    }

    fun pending(context: Context): String? = file(context).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        file(context).delete()
    }

    /** Builds the report text. Pure (no Android calls) so it can be unit-tested. */
    fun report(error: Throwable, versionName: String, versionCode: Long, time: String, sdk: Int, device: String): String = buildString {
        appendLine("Talenta crash report")
        appendLine("App: $versionName ($versionCode)")
        appendLine("Android SDK: $sdk")
        appendLine("Device: $device")
        appendLine("Time: $time")
        appendLine()
        var current: Throwable? = error
        var depth = 0
        val seen = HashSet<Throwable>()
        while (current != null && depth < MAX_CAUSES && seen.add(current)) {
            val type = current.javaClass.name
            append(if (depth == 0) "" else "Caused by: ")
            append(type)
            if (type in SAFE_MESSAGE_TYPES && !current.message.isNullOrBlank()) append(": ").append(current.message)
            appendLine()
            val frames = current.stackTrace
            frames.take(MAX_FRAMES).forEach { appendLine("    at $it") }
            if (frames.size > MAX_FRAMES) appendLine("    ... ${frames.size - MAX_FRAMES} more")
            current = current.cause
            depth++
        }
    }
}
