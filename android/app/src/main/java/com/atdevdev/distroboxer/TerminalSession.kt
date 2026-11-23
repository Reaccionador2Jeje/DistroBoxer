package com.atdevdev.distroboxer

import android.os.Handler
import android.os.Looper
import java.io.BufferedWriter
import java.io.OutputStreamWriter

/**
 * Bridges an interactive Linux process to the Compose terminal.
 * Process output is collected off the UI thread; callbacks return on main.
 */
class TerminalSession(
    private val process: Process,
    private val onOutput: (String) -> Unit,
    private val onExit: (Int) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val writer = BufferedWriter(OutputStreamWriter(process.outputStream))
    @Volatile private var closed = false

    init {
        Thread({
            val buffer = CharArray(2048)
            try {
                process.inputStream.bufferedReader().use { reader ->
                    while (!closed) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        val chunk = String(buffer, 0, count)
                        mainHandler.post { if (!closed) onOutput(chunk) }
                    }
                }
            } catch (error: Exception) {
                if (!closed) {
                    val message = error.message ?: "error de lectura"
                    mainHandler.post { if (!closed) onOutput("\n[terminal] $message\n") }
                }
            } finally {
                val exitCode = runCatching { process.waitFor() }.getOrDefault(-1)
                mainHandler.post { if (!closed) onExit(exitCode) }
            }
        }, "DistroBoxer-Terminal-Reader").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun send(text: String) {
        check(!closed && process.isAlive) { "La sesión Linux no está activa." }
        writer.write(text)
        writer.flush()
    }

    fun sendLine(command: String) = send("$command\n")

    fun close() {
        closed = true
        runCatching { writer.close() }
        if (process.isAlive) process.destroy()
        if (process.isAlive) process.destroyForcibly()
    }

    fun isAlive(): Boolean = !closed && process.isAlive
}
