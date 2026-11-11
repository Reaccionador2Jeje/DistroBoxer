package com.atdevdev.distroboxer

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists uncaught app crashes so the next launch can show the actual failure.
 * The original Android handler is always invoked afterwards.
 */
class DistroBoxerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val report = StringWriter().also { writer ->
                    throwable.printStackTrace(PrintWriter(writer))
                }.toString()
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                File(filesDir, "last-crash.txt").writeText(
                    "DistroBoxer startup/runtime crash\nTime: $timestamp\nThread: ${thread.name}\n\n$report"
                )
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}
