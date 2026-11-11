package com.atdevdev.distroboxer

import android.content.Context
import android.view.View
import au.com.darkside.xserver.ScreenView
import au.com.darkside.xserver.XServer

/**
 * Embedded X11 display running inside DistroBoxer's Android process.
 * The server listens only on 127.0.0.1:6001 (display 127.0.0.1:1).
 */
interface X11DisplayBackend {
    val id: String
    fun isAvailable(context: Context): Boolean
    fun displayName(): String
    fun environment(): Map<String, String>
    fun start(context: Context): View
    fun stop()
}

class EmbeddedX11Backend : X11DisplayBackend {
    override val id: String = "embedded-java-x11"

    @Volatile
    private var server: XServer? = null

    override fun isAvailable(context: Context): Boolean = try {
        context.applicationContext.classLoader.loadClass("au.com.darkside.xserver.XServer")
        true
    } catch (_: ClassNotFoundException) {
        false
    } catch (_: LinkageError) {
        false
    }

    @Synchronized
    override fun start(context: Context): ScreenView {
        server?.let { return it.screen }
        check(isAvailable(context)) { "El servidor X11 no está incluido en esta compilación." }
        val instance = XServer(context.applicationContext, DISPLAY_PORT, null)
        check(instance.start()) { "No se pudo iniciar X11 en el puerto $DISPLAY_PORT." }
        server = instance
        return instance.screen
    }

    @Synchronized
    override fun stop() {
        server?.stop()
        server = null
    }

    override fun displayName(): String = DISPLAY_ADDRESS

    override fun environment(): Map<String, String> = mapOf(
        "DISPLAY" to DISPLAY_ADDRESS,
        "TMPDIR" to "/tmp",
    )

    companion object {
        const val DISPLAY_PORT = 6001
        const val DISPLAY_ADDRESS = "127.0.0.1:1"
    }
}

/** Validated dimensions shared by the Android display surface and desktop UI. */
data class X11SessionConfig(
    val display: String = EmbeddedX11Backend.DISPLAY_ADDRESS,
    val width: Int,
    val height: Int,
    val dpi: Int = 160,
) {
    init {
        require(display == EmbeddedX11Backend.DISPLAY_ADDRESS)
        require(width in 320..4096)
        require(height in 320..4096)
        require(dpi in 80..640)
    }
}
