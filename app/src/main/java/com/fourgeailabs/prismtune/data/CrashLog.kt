package com.fourgeailabs.prismtune.data

import android.content.Context
import android.os.Build
import com.fourgeailabs.prismtune.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash history, kept ON the device, for future debugging
 *, other testers, and regular users once public.
 *
 * 1.3.3: saved only the LAST crash, overwriting the file.
 * 1.3.5 (2026-10-26): keep a HISTORY, not just one.
 * Every crash is APPENDED to a history file, with a
 * separator, and the file is pruned to the most recent
 * 20 crashes / 500 KB, so it never grows unbounded.
 * The history survives app updates (it lives in filesDir),
 * and it is only cleared by an explicit sign-out or by
 * tapping Clear in Diagnostics — never by a crash, and
 * never by a normal app restart.
 *
 * Privacy, for public users: NO data leaves the device
 * automatically. There is no server upload, no analytics,
 * no tracking, and no background send. A crash log only
 * leaves the device when the user taps Copy or Share in
 * Settings > Diagnostics, and shares it themselves.
 * Stack traces here contain class names, file names, and
 * line numbers only, and the writer strips any line
 * containing "token", "password", or "authToken" just in
 * case. Version is read from BuildConfig, so it can never
 * go stale the way a hardcoded "1.3.3" header did.
 */
object CrashLog {
    private const val FILE_NAME = "prismtune_crash_history.txt"
    private const val MARKER_NAME = "prismtune_launch_marker.txt"
    private const val SEPARATOR = "\n===== CRASH =====\n"
    private const val MAX_CRASHES = 20
    private const val MAX_BYTES = 500 * 1024

    data class CrashEntry(val header: String, val body: String) {
        val title: String get() {
            val stamp = header.lines().firstOrNull { it.startsWith("PrismTune crash") } ?: "Crash"
            val exception = body.lines().firstOrNull()?.take(120) ?: ""
            return "$stamp — $exception"
        }
        val fullText: String get() = header + body
    }

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrash(context, thread, throwable) }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    fun writeCrash(context: Context, thread: Thread, throwable: Throwable) {
        try {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            throwable.printStackTrace(pw)
            pw.flush()
            var trace = sw.toString()
            trace = trace.lines().filter { line ->
                val lower = line.lowercase()
                !lower.contains("token") && !lower.contains("password") && !lower.contains("authtoken")
            }.joinToString("\n")
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val header = "PrismTune crash — $stamp\n" +
                "App: ${BuildConfig.VERSION_NAME} (versionCode ${BuildConfig.VERSION_CODE})\n" +
                "Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n" +
                "Device: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                "Thread: ${thread.name}\n" +
                "---\n"
            val f = file(context)
            val existing = if (f.exists()) f.readText() else ""
            val combined = if (existing.isBlank()) header + trace else existing + SEPARATOR + header + trace
            // Prune: keep the most recent 20 crashes, and
            // drop the oldest until under 500 KB.
            var parts = combined.split(SEPARATOR)
            if (parts.size > MAX_CRASHES) parts = parts.takeLast(MAX_CRASHES)
            var out = parts.joinToString(SEPARATOR)
            while (out.toByteArray().size > MAX_BYTES && parts.size > 1) {
                parts = parts.drop(1)
                out = parts.joinToString(SEPARATOR)
            }
            f.writeText(out)
        } catch (_: Exception) { /* crash logging must never crash */ }
    }

    fun readHistory(context: Context): List<CrashEntry> {
        return try {
            val f = file(context)
            if (!f.exists()) return emptyList()
            f.readText().split(SEPARATOR).filter { it.isNotBlank() }.map { part ->
                val lines = part.lines()
                val sepIdx = lines.indexOf("---")
                if (sepIdx >= 0) {
                    CrashEntry(lines.take(sepIdx + 1).joinToString("\n") + "\n", lines.drop(sepIdx + 1).joinToString("\n"))
                } else {
                    CrashEntry("", part)
                }
            }.reversed() // newest first
        } catch (_: Exception) { emptyList() }
    }

    /** Latest crash only, for callers that only need one. */
    fun read(context: Context): String? {
        val history = readHistory(context)
        return history.firstOrNull()?.fullText?.take(8000)
    }

    /**
     * 1.4.1 CRASH GATE: a marker file written at the very
     * start of a launch and deleted only once the UI has
     * actually drawn. If it still exists on the next launch,
     * the previous launch died before drawing — that is a
     * launch crash loop, and the next launch must NOT
     * repeat the same heavy startup. All file ops are
     * guarded; a marker failure never blocks launch.
     */
    fun markLaunchStarted(context: Context) {
        runCatching { File(context.filesDir, MARKER_NAME).writeText(System.currentTimeMillis().toString()) }
    }
    fun markLaunchDrawn(context: Context) {
        runCatching { File(context.filesDir, MARKER_NAME).delete() }
    }
    fun previousLaunchCrashed(context: Context): Boolean =
        runCatching { File(context.filesDir, MARKER_NAME).exists() }.getOrDefault(false)
    fun clearMarker(context: Context) {
        runCatching { File(context.filesDir, MARKER_NAME).delete() }
    }

    fun clear(context: Context) {
        try { file(context).delete() } catch (_: Exception) { }
        // Also delete the old 1.3.3 single-crash file, if present.
        try { File(context.filesDir, "prismtune_crash_log.txt").delete() } catch (_: Exception) { }
    }
}
