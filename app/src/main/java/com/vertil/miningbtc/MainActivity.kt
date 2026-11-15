package com.vertil.miningbtc

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {
    private val bg = Color.rgb(8, 14, 23)
    private val panel = Color.rgb(17, 27, 40)
    private val muted = Color.rgb(145, 163, 184)
    private val white = Color.rgb(239, 245, 251)
    private val orange = Color.rgb(247, 147, 26)
    private val green = Color.rgb(57, 201, 145)
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var userInput: EditText
    private lateinit var passInput: EditText
    private lateinit var tlsInput: CheckBox
    private lateinit var status: TextView
    private lateinit var rate: TextView
    private lateinit var hashes: TextView
    private lateinit var accepted: TextView
    private lateinit var rejected: TextView
    private lateinit var detail: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private var miner: StratumMiner? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val prefs = getSharedPreferences("pool_config", MODE_PRIVATE)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(28))
            setBackgroundColor(bg)
        }
        page.addView(label("₿  MINING BTC", 23f, white, true))
        page.addView(label("VERTIL  /  STRATUM V1 PROOF OF WORK", 10f, muted, true))
            .also { it.setPadding(0, dp(3), 0, dp(25)) }

        page.addView(label("LIVE MINING PERFORMANCE", 11f, muted, true).padTop(8))
        val metrics = card(20)
        metrics.addView(label("SHA-256d HASHRATE", 10f, muted, true))
        rate = label("0.00 H/s", 38f, white, true).padTop(8)
        metrics.addView(rate)
        metrics.addView(label("REAL HASHES PER SECOND", 9f, muted, false).padTop(2))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val left = metric("ACCEPTED SHARES")
        accepted = left.second
        val right = metric("REJECTED SHARES")
        rejected = right.second
        row.addView(left.first, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(right.first, LinearLayout.LayoutParams(0, -2, 1f))
        metrics.addView(row.padTop(18))
        page.addView(metrics)

        page.addView(label("POOL CONNECTION", 11f, muted, true).padTop(24).padBottom(10))
        val config = card(18)
        hostInput = field("Pool hostname (e.g. pool.example.com)", prefs.getString("host", "") ?: "")
        portInput = field("Port", prefs.getString("port", "3333") ?: "3333", true)
        userInput = field("Worker username / wallet.worker", prefs.getString("user", "") ?: "")
        passInput = field("Worker password (often x)", prefs.getString("pass", "x") ?: "")
        tlsInput = CheckBox(this).apply {
            text = "Use TLS (Stratum SSL port)"
            setTextColor(white)
            isChecked = prefs.getBoolean("tls", false)
            buttonTintList = android.content.res.ColorStateList.valueOf(orange)
        }
        config.addView(fieldLabel("POOL HOST"))
        config.addView(hostInput)
        config.addView(fieldLabel("POOL PORT"))
        config.addView(portInput)
        config.addView(fieldLabel("POOL USERNAME"))
        config.addView(userInput)
        config.addView(fieldLabel("POOL PASSWORD"))
        config.addView(passInput)
        config.addView(tlsInput)
        page.addView(config)

        page.addView(label("MINING SESSION", 11f, muted, true).padTop(22).padBottom(10))
        val session = card(18)
        status = label("●  DISCONNECTED", 12f, white, true)
        session.addView(status)
        detail = label("Configure a pool and worker account. This app will submit real Stratum shares; it does not guarantee BTC payouts.", 11f, muted, false).padTop(9)
        session.addView(detail)
        page.addView(session)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        startButton = Button(this).apply {
            text = "START MINING"; isAllCaps = false; setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD; background = rounded(orange, 14)
            setOnClickListener { startMining() }
        }
        stopButton = Button(this).apply {
            text = "STOP"; isAllCaps = false; isEnabled = false
            setTextColor(white); background = rounded(Color.rgb(47, 60, 75), 14)
            setOnClickListener { stopMining() }
        }
        actions.addView(startButton, LinearLayout.LayoutParams(0, dp(54), 1f).apply { rightMargin = dp(6); topMargin = dp(16) })
        actions.addView(stopButton, LinearLayout.LayoutParams(0, dp(54), 1f).apply { leftMargin = dp(6); topMargin = dp(16) })
        page.addView(actions)

        page.addView(label("TOTAL HASHES", 9f, muted, true).padTop(24))
        hashes = label("0", 19f, white, true).padTop(4)
        page.addView(hashes)
        page.addView(label(
            "REAL POOL MODE · STRATUM V1\nShares are submitted to the configured pool. Pool acceptance is not the same as a confirmed Bitcoin payout. Mining is CPU-intensive and can heat the phone. Keep the app open during this initial foreground-only build.",
            10f, muted, false
        ).padTop(22))

        setContentView(ScrollView(this).apply { isFillViewport = true; addView(page) })
    }

    private fun startMining() {
        if (miner != null) return
        val host = hostInput.text.toString().trim()
        val port = portInput.text.toString().trim().toIntOrNull()
        val user = userInput.text.toString().trim()
        val pass = passInput.text.toString()
        if (host.isBlank() || port == null || port !in 1..65535 || user.isBlank()) {
            status.text = "●  CONFIGURATION ERROR"
            detail.text = "Enter a valid pool hostname, port and worker username."
            return
        }
        getSharedPreferences("pool_config", MODE_PRIVATE).edit()
            .putString("host", host).putString("port", port.toString())
            .putString("user", user).putString("pass", pass).putBoolean("tls", tlsInput.isChecked).apply()
        val engine = StratumMiner(PoolConfig(host, port, user, pass, tlsInput.isChecked)) { stats ->
            runOnUiThread {
                rate.text = formatRate(stats.hashrate)
                hashes.text = String.format(Locale.US, "%,d", stats.hashes)
                accepted.text = stats.accepted.toString()
                rejected.text = stats.rejected.toString()
                status.text = "●  ${stats.state.uppercase(Locale.US)}"
                detail.text = stats.message + "  ·  Pool difficulty: " + String.format(Locale.US, "%.8f", stats.difficulty)
                if (stats.state == "Error" || stats.state == "Disconnected" || stats.state == "Stopped") {
                    miner = null
                    startButton.isEnabled = true
                    stopButton.isEnabled = false
                }
            }
        }
        miner = engine
        startButton.isEnabled = false
        stopButton.isEnabled = true
        status.text = "●  CONNECTING"
        detail.text = "Opening Stratum connection and authorizing worker…"
        engine.start()
    }

    private fun stopMining() {
        miner?.stop()
        miner = null
        startButton.isEnabled = true
        stopButton.isEnabled = false
        status.text = "●  STOPPED"
        detail.text = "Mining stopped. Shares already sent remain recorded by the pool."
    }

    override fun onDestroy() {
        miner?.stop()
        miner = null
        super.onDestroy()
    }

    private fun field(hintText: String, value: String, numeric: Boolean = false) = EditText(this).apply {
        hint = hintText
        setText(value)
        setTextColor(white)
        setHintTextColor(muted)
        textSize = 13f
        setSingleLine(true)
        inputType = if (numeric) android.text.InputType.TYPE_CLASS_NUMBER else android.text.InputType.TYPE_CLASS_TEXT
        background = rounded(Color.rgb(8, 14, 23), 10)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(9) }
    }

    private fun fieldLabel(value: String) = label(value, 9f, muted, true).apply {
        setPadding(0, dp(7), 0, dp(5))
    }

    private fun metric(title: String): Pair<LinearLayout, TextView> {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(title, 9f, muted, true))
        val value = label("0", 20f, white, true).padTop(5)
        col.addView(value)
        return col to value
    }

    private fun card(radius: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(panel, radius)
        setPadding(dp(18), dp(18), dp(18), dp(18))
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }

    private fun TextView.padTop(value: Int) = apply { setPadding(paddingLeft, dp(value), paddingRight, paddingBottom) }
    private fun TextView.padBottom(value: Int) = apply { setPadding(paddingLeft, paddingTop, paddingRight, dp(value)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun formatRate(value: Double): String = when {
        value >= 1_000_000 -> String.format(Locale.US, "%.2f MH/s", value / 1_000_000)
        value >= 1_000 -> String.format(Locale.US, "%.2f kH/s", value / 1_000)
        else -> String.format(Locale.US, "%.2f H/s", value)
    }
}
