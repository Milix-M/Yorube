package com.hermes.client.data.crash

import android.content.Context
import java.io.File

/**
 * Last-resort crash recorder. Android kills the process after an uncaught
 * exception, so this appends the stack trace to a small rotated file first.
 * The file lives in the app's private storage for later inspection.
 */
object CrashLog {
    internal const val FILE_NAME = "last_crash.log"
    internal const val MAX_BYTES = 64 * 1024

    fun install(context: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val report = try {
                format(thread, error)
            } catch (_: Exception) {
                error.toString()
            }
            try {
                writeReport(context, report)
            } catch (_: Exception) {
                // Never crash the crash reporter.
            }
            prev?.uncaughtException(thread, error)
        }
    }

    internal fun format(thread: Thread, error: Throwable): String = buildString {
        appendLine("time=${java.time.Instant.now()}")
        appendLine("thread=${thread.name}")
        appendLine(error.toString())
        error.stackTrace.take(60).forEach { appendLine("    at $it") }
        var cause = error.cause
        while (cause != null) {
            appendLine("Caused by: $cause")
            cause = cause.cause
        }
    }

    internal fun writeReport(context: Context, report: String) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            val older = if (file.exists()) file.readText() else ""
            file.writeText((report + "\n---\n" + older).take(MAX_BYTES))
        } catch (_: Exception) {
            // Best effort only.
        }
    }

    fun read(context: Context): String {
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) return file.readText()
        } catch (_: Exception) {
        }
        return ""
    }

    fun clear(context: Context) {
        try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (_: Exception) {
        }
    }
}
