package com.vertil.miningbtc

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val running = AtomicBoolean(false)
    private lateinit var status: TextView
    private lateinit var rate: TextView
    private lateinit var hashesView: TextView
    private lateinit var best: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private val receivingAddress = "bc1qatjctrapmz985s23yf8jh8rafvwtk46cpg3cyj"

    private val bg = Color.rgb(8, 14, 23)
    private val panel = Color.rgb(17, 27, 40)
    private val muted = Color.rgb(145, 163, 184)
    private val white = Color.rgb(239, 245, 251)
    private val orange = Color.rgb(247, 147, 26)
    private val green = Color.rgb(57, 201, 145)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(28))
            setBackgroundColor(bg)
        }

        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        brand.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_mining_btc)
            layoutParams = LinearLayout.LayoutParams(dp(54), dp(54))
        })
        val brandText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        brandText.addView(label("MINING BTC", 19f, white, true))
        brandText.addView(label("POW LAB  /  ANDROID", 10f, muted, true))
        brand.addView(brandText, LinearLayout.LayoutParams(0, -2, 1f))
        val livePill = label("●  LOCAL", 10f, green, true).apply {
            setPadding(dp(11), dp(7), dp(11), dp(7))
            background = rounded(Color.rgb(18, 52, 47), 30)
        }
        brand.addView(livePill)
        page.addView(brand)

        page.addView(label("DEVICE PERFORMANCE", 11f, muted, true).apply {
            setPadding(0, dp(30), 0, dp(12))
        })

        val hero = card(22).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        hero.addView(label("SHA-256d HASHRATE", 11f, muted, true))
        rate = label("0.00", 42f, white, true)
        rate.setPadding(0, dp(7), 0, 0)
        hero.addView(rate)
        hero.addView(label("hashes per second", 12f, muted, false))
        val divider = View(this).apply { setBackgroundColor(Color.rgb(42, 57, 73)) }
        hero.addView(divider, LinearLayout.LayoutParams(-1, dp(1)).apply {
            topMargin = dp(18); bottomMargin = dp(14)
        })
        val metrics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val metricA = metricColumn("TOTAL HASHES")
        hashesView = metricA.second
        val metricB = metricColumn("BEST RESULT")
        best = metricB.second
        metrics.addView(metricA.first, LinearLayout.LayoutParams(0, -2, 1f))
        metrics.addView(metricB.first, LinearLayout.LayoutParams(0, -2, 1f))
        hero.addView(metrics)
        page.addView(hero)

        page.addView(label("MINING SESSION", 11f, muted, true).apply {
            setPadding(0, dp(24), 0, dp(12))
        })
        val session = card(18).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(16), dp(17), dp(16))
        }
        val stateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = label("●", 12f, orange, true)
        stateRow.addView(dot)
        status = label("  READY TO BENCHMARK", 12f, white, true)
        stateRow.addView(status)
        session.addView(stateRow)
        session.addView(label(
            "This test measures double-SHA-256 throughput on your phone. It does not submit Bitcoin shares or earn BTC.",
            12f, muted, false
        ).apply { setPadding(0, dp(10), 0, 0) })
        page.addView(session)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        startButton = Button(this).apply {
            text = "START TEST"
            isAllCaps = false
            setTextColor(Color.BLACK)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(orange, 14)
            setOnClickListener { startBenchmark() }
        }
        stopButton = Button(this).apply {
            text = "STOP"
            isAllCaps = false
            isEnabled = false
            setTextColor(white)
            textSize = 14f
            background = rounded(Color.rgb(47, 60, 75), 14)
            setOnClickListener { stopBenchmark() }
        }
        actions.addView(startButton, LinearLayout.LayoutParams(0, dp(54), 1f).apply {
            rightMargin = dp(7); topMargin = dp(16)
        })
        actions.addView(stopButton, LinearLayout.LayoutParams(0, dp(54), 1f).apply {
            leftMargin = dp(7); topMargin = dp(16)
        })
        page.addView(actions)

        page.addView(label("PAYOUT ADDRESS", 11f, muted, true).apply {
            setPadding(0, dp(26), 0, dp(10))
        })
        val wallet = card(16).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
        }
        wallet.addView(label(receivingAddress, 12f, white, false))
        wallet.addView(label("Configured destination · checksum validation pending", 10f, orange, false)
            .apply { setPadding(0, dp(8), 0, 0) })
        page.addView(wallet)

        page.addView(label(
            "RESEARCH BUILD 0.1.0  •  SNAPDRAGON 865 TARGET\nNo private keys are stored in this app. Never share your wallet recovery phrase.",
            10f, muted, false
        ).apply { setPadding(0, dp(24), 0, 0) })

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(page)
        })
    }

    private fun metricColumn(title: String): Pair<LinearLayout, TextView> {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(title, 9f, muted, true))
        val value = label("0", 19f, white, true)
        value.setPadding(0, dp(5), 0, 0)
        col.addView(value)
        return col to value
    }

    private fun card(radius: Int) = LinearLayout(this).apply {
        background = rounded(panel, radius)
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun startBenchmark() {
        if (!running.compareAndSet(false, true)) return
        startButton.isEnabled = false
        stopButton.isEnabled = true
        status.text = "  RUNNING · 30 SECOND TEST"
        thread(name = "sha256d-benchmark") {
            val digest = MessageDigest.getInstance("SHA-256")
            val header = ByteArray(80)
            var nonce = 0L
            var hashes = 0L
            var bestBits = 0
            val start = SystemClock.elapsedRealtime()
            var lastUi = start
            val end = start + 30_000L

            while (running.get() && SystemClock.elapsedRealtime() < end) {
                header[76] = (nonce and 0xff).toByte()
                header[77] = ((nonce ushr 8) and 0xff).toByte()
                header[78] = ((nonce ushr 16) and 0xff).toByte()
                header[79] = ((nonce ushr 24) and 0xff).toByte()
                val first = digest.digest(header)
                val second = digest.digest(first)
                bestBits = maxOf(bestBits, leadingZeroBits(second))
                nonce = (nonce + 1) and 0xffffffffL
                hashes++

                val now = SystemClock.elapsedRealtime()
                if (now - lastUi >= 1000L) {
                    val elapsed = (now - start).coerceAtLeast(1L)
                    val hps = hashes.toDouble() * 1000.0 / elapsed
                    val currentHashes = hashes
                    val currentBest = bestBits
                    runOnUiThread {
                        rate.text = formatRate(hps)
                        hashesView.text = String.format(Locale.US, "%,d", currentHashes)
                        best.text = "$currentBest bits"
                    }
                    lastUi = now
                }
            }

            val elapsed = (SystemClock.elapsedRealtime() - start).coerceAtLeast(1L)
            val finalRate = hashes.toDouble() * 1000.0 / elapsed
            running.set(false)
            runOnUiThread {
                rate.text = formatRate(finalRate)
                hashesView.text = String.format(Locale.US, "%,d", hashes)
                best.text = "$bestBits bits"
                status.text = "  TEST COMPLETE · NO BTC MINED"
                startButton.isEnabled = true
                stopButton.isEnabled = false
            }
        }
    }

    private fun stopBenchmark() {
        running.set(false)
        status.text = "  STOPPING TEST…"
        stopButton.isEnabled = false
        startButton.isEnabled = true
    }

    override fun onDestroy() {
        running.set(false)
        super.onDestroy()
    }

    private fun formatRate(value: Double): String = when {
        value >= 1_000_000 -> String.format(Locale.US, "%.2f MH/s", value / 1_000_000)
        value >= 1_000 -> String.format(Locale.US, "%.2f kH/s", value / 1_000)
        else -> String.format(Locale.US, "%.2f H/s", value)
    }

    private fun leadingZeroBits(bytes: ByteArray): Int {
        var count = 0
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            if (value == 0) count += 8 else {
                count += Integer.numberOfLeadingZeros(value) - 24
                break
            }
        }
        return count
    }
}
