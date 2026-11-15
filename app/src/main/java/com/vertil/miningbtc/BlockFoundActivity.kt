package com.vertil.miningbtc

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BlockFoundActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val simulated = intent.getBooleanExtra("simulated", false)
        val hash = intent.getStringExtra("hash") ?: "—"
        val nonce = intent.getLongExtra("nonce", -1L)
        val time = intent.getLongExtra("timestamp_ms", 0L)
        val header = intent.getStringExtra("header80_hex") ?: ""
        val bits = intent.getStringExtra("compact_bits") ?: ""
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 36, 28, 36)
            setBackgroundColor(Color.rgb(5, 12, 18))
        }
        fun text(value: String, size: Float, color: Int, mono: Boolean = false) =
            TextView(this).apply {
                text = value
                textSize = size
                setTextColor(color)
                gravity = Gravity.CENTER
                if (mono) typeface = Typeface.MONOSPACE
                setPadding(0, 12, 0, 12)
            }
        root.addView(text(if (simulated) "SIMULACIÓN" else "BLOQUE ENCONTRADO", 27f,
            if (simulated) Color.rgb(247, 147, 26) else Color.rgb(34, 197, 94)))
        root.addView(text("Bitcoin network target reached", 15f, Color.WHITE))
        root.addView(text("HASH", 11f, Color.GRAY))
        root.addView(text(hash, 11f, Color.WHITE, true))
        root.addView(text("Nonce: %08x".format(Locale.US, nonce), 13f, Color.WHITE, true))
        root.addView(text("Hora: " + if (time > 0) SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(time)) else "—", 12f, Color.LTGRAY))
        root.addView(text("nBits: $bits", 12f, Color.LTGRAY, true))
        root.addView(text("Header (80 bytes)", 11f, Color.GRAY))
        root.addView(text(header, 9f, Color.LTGRAY, true))
        root.addView(text("Registro guardado en el almacenamiento privado de la aplicación.", 11f, Color.GRAY))
        setContentView(root)
    }
}
