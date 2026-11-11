package com.atdevdev.distroboxer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Minimal launcher used to separate Android startup from Compose, JNI, PRoot and X11.
 * It intentionally loads none of those components until the user taps the test button.
 */
class StartupProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val output = TextView(this).apply {
            text = "DistroBoxer · Diagnóstico de arranque\n\n" +
                "La capa básica de Android abrió correctamente.\n" +
                "PRoot, JNI, Compose y X11 todavía no se han inicializado."
            textSize = 16f
            setPadding(24, 24, 24, 24)
        }

        val launchButton = Button(this).apply {
            text = "Probar interfaz completa"
            setOnClickListener {
                try {
                    startActivity(Intent(this@StartupProbeActivity, MainActivity::class.java))
                } catch (error: Throwable) {
                    output.text = "La interfaz completa no pudo abrirse.\n\n" +
                        error.javaClass.name + ": " + (error.message ?: "sin mensaje") +
                        "\n\n" + error.stackTraceToString().take(9000)
                }
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 24, 24, 24)
            addView(output)
            addView(launchButton)
        }

        setContentView(ScrollView(this).apply { addView(layout) })
    }
}
