package com.atdevdev.distroboxer

import android.content.Context

/**
 * Display server contract. PRoot and the display server must run in the same
 * Android application sandbox so the X11 Unix socket can be shared safely.
 */
interface X11DisplayBackend {
    val id: String
    fun isAvailable(context: Context): Boolean
    fun displayName(): String
    fun environment(): Map<String, String>
}

/**
 * Embedded backend reserved for the DistroBoxer-owned Android X server.
 *
 * This backend intentionally reports unavailable until the native X server
 * implementation and its Android Surface renderer are shipped in the APK.
 * It never pretends that an installed third-party X11 app is an embedded server.
 */
class EmbeddedX11Backend : X11DisplayBackend {
    override val id: String = "embedded-x11"

    override fun isAvailable(context: Context): Boolean {
        return try {
            context.applicationContext.classLoader
                .loadClass("com.atdevdev.distroboxer.nativebridge.X11Server")
            false // Native server bridge is not packaged in this release yet.
        } catch (_: ClassNotFoundException) {
            false
        } catch (_: LinkageError) {
            false
        }
    }

    override fun displayName(): String = ":1"

    override fun environment(): Map<String, String> = mapOf(
        "DISPLAY" to ":1",
        "TMPDIR" to "/tmp",
    )
}

/**
 * Shared display configuration used by a future native X11 server.
 * A private socket path is kept under app cache; no world-writable TCP listener
 * or externally exposed desktop port is created.
 */
data class X11SessionConfig(
    val display: String = ":1",
    val socketDirectory: String,
    val width: Int,
    val height: Int,
    val dpi: Int = 160,
) {
    init {
        require(display.matches(Regex(":[0-9]{1,2}")))
        require(socketDirectory.startsWith("/") && !socketDirectory.contains(".."))
        require(width in 320..4096)
        require(height in 320..4096)
        require(dpi in 80..640)
    }
}
