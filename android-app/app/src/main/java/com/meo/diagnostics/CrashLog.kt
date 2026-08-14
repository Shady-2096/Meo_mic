package com.meo.diagnostics

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last crash, written to a file the user can read and choose to send.
 *
 * Meo has no crash reporting and plan §6.5 says it never will: "No analytics or
 * crash upload, ever. Diagnostic export is local." That is the right policy and
 * it has a cost — a sideloaded build that dies on a device the maintainer does
 * not own is otherwise completely undiagnosable, which is exactly the situation
 * this was written in.
 *
 * So: the trace is written locally, shown to the user, and goes nowhere unless
 * they explicitly share it.
 *
 * What is deliberately **not** recorded, per the same section's requirement that
 * diagnostics redact secrets: no pairing tokens, no credentials, no SPKI pins,
 * no message payloads, no frame content. A stack trace carries types, methods
 * and line numbers. The exception messages this app constructs are written to
 * avoid naming secret material — [com.meo.pairing.PinningTrustManager] refuses
 * to name the pins it expected for precisely this reason — and that discipline
 * is what makes a trace safe to hand over.
 */
object CrashLog {

    private const val FILE_NAME = "last-crash.txt"

    /**
     * Installs the handler. Chains to whatever was there before rather than
     * replacing it, so the process still dies the way Android expects; a
     * handler that swallowed the crash would leave a half-dead app running.
     */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write(appContext, thread, error)
            } catch (_: Throwable) {
                // Never let diagnostics turn a crash into a different crash.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? =
        file(context).takeIf { it.isFile }?.let {
            try {
                it.readText().ifBlank { null }
            } catch (_: Exception) {
                null
            }
        }

    fun clear(context: Context) {
        try {
            file(context).delete()
        } catch (_: Exception) {
            // Nothing useful to do; the banner simply reappears.
        }
    }

    private fun file(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val stack = StringWriter().also { writer ->
            PrintWriter(writer).use(error::printStackTrace)
        }.toString()

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        // Device and OS are the whole reason this file exists: the bugs that
        // reach it are the ones that did not reproduce on the maintainer's
        // emulator, and "which Android, which phone" is the first question.
        val report = buildString {
            appendLine("Meo crash report")
            appendLine("time      $timestamp")
            appendLine("thread    ${thread.name}")
            appendLine("device    ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android   ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("build     ${Build.FINGERPRINT}")
            appendLine("abis      ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine()
            append(stack)
        }
        file(context).writeText(report)
    }
}
