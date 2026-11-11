package com.atdevdev.distroboxer

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves Java crashes and Android's recorded native-crash/ANR exit diagnostics.
 * The platform's original uncaught-exception handler is always preserved.
 */
class DistroBoxerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        collectPreviousExitInfo()

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val report = StringWriter().also { writer ->
                    throwable.printStackTrace(PrintWriter(writer))
                }.toString()
                writeCrashReport(
                    "Java/Kotlin uncaught exception",
                    "Thread: ${thread.name}\n\n$report",
                )
            }
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    private fun collectPreviousExitInfo() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val exit = manager.getHistoricalProcessExitReasons(packageName, 0, 8)
                .firstOrNull { it.reason in CRASH_REASONS } ?: return
            val trace = runCatching {
                exit.traceInputStream?.bufferedReader()?.use { it.readText().takeLast(MAX_TRACE_CHARS) }
            }.getOrNull().orEmpty()
            val details = buildString {
                appendLine("Process: ${exit.processName}")
                appendLine("Reason: ${reasonName(exit.reason)} (${exit.reason})")
                appendLine("Timestamp: ${Date(exit.timestamp)}")
                appendLine("Description: ${exit.description ?: "No disponible"}")
                appendLine("Status: ${exit.status}")
                appendLine("Importance: ${exit.importance}")
                appendLine("PSS: ${exit.pss} KB · RSS: ${exit.rss} KB")
                if (trace.isNotBlank()) {
                    appendLine()
                    appendLine("System trace:")
                    appendLine(trace)
                }
            }
            writeCrashReport("Android process-exit diagnostics", details)
        }
    }

    private fun writeCrashReport(title: String, details: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        File(filesDir, CRASH_REPORT_FILE).writeText(
            "DistroBoxer diagnostic report\nType: $title\nTime: $timestamp\n\n$details",
        )
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        else -> "OTHER"
    }

    companion object {
        const val CRASH_REPORT_FILE = "last-crash.txt"
        private const val MAX_TRACE_CHARS = 24000
        private val CRASH_REASONS = setOf(
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        )
    }
}
