package com.vertil.miningbtc

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.widget.Button
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
    private lateinit var best: TextView
    private lateinit var addressStatus: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private val receivingAddress = "bc1qatjctrapmz985s23yf8jh8rafvwtk46cpg3cyj"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 32, 28, 28)
            setBackgroundColor(0xFF101820.toInt())
        }
        fun text(value: String, size: Float, color: Int = 0xFFEAF0F6.toInt()) =
            TextView(this).apply {
                this.text = value
                textSize = size
                setTextColor(color)
                setPadding(0, 10, 0, 10)
            }

        root.addView(text("MINING BTC LAB", 25f))
        root.addView(text("SHA-256d · Android benchmark · v0.1.0", 13f, 0xFF91A4B7.toInt()))
        root.addView(text("Receiving address", 14f, 0xFF91A4B7.toInt()))
        root.addView(text(receivingAddress, 12f))
        addressStatus = text("Checking Bech32 address…", 13f, 0xFF80CBC4.toInt())
        root.addView(addressStatus)
        root.addView(text("Measured hashrate", 14f, 0xFF91A4B7.toInt()))
        rate = text("0 H/s", 30f)
        root.addView(rate)
        root.addView(text("Best leading-zero result (benchmark only)", 13f, 0xFF91A4B7.toInt()))
        best = text("0 bits", 20f)
        root.addView(best)
        status = text("Stopped. This test does not mine blocks or earn BTC.", 14f, 0xFFFFCC80.toInt())
        root.addView(status)
        startButton = Button(this).apply {
            text = "START 30-SECOND TEST"
            setOnClickListener { startBenchmark() }
        }
        stopButton = Button(this).apply {
            text = "STOP"
            isEnabled = false
            setOnClickListener { stopBenchmark("Stopped by user.") }
        }
        root.addView(startButton)
        root.addView(stopButton)
        root.addView(text(
            "Thermal safety: short tests only. SHA-256d benchmark results are not valid Bitcoin shares. " +
                "No pool connection or payout is implemented in this phase.",
            12f, 0xFF91A4B7.toInt()
        ))
        setContentView(ScrollView(this).apply { addView(root) })
        addressStatus.text = if (Bech32.isValidWitnessAddress(receivingAddress)) {
            "Address checksum: valid (Bech32)"
        } else {
            "Address checksum: INVALID — do not use for payouts"
        }
    }

    private fun startBenchmark() {
        if (!running.compareAndSet(false, true)) return
        startButton.isEnabled = false
        stopButton.isEnabled = true
        status.text = "Running a 30-second SHA-256d benchmark…"
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
                    val hps = hashes.toDouble() * 1000.0 / elapsed.toDouble()
                    runOnUiThread {
                        rate.text = formatRate(hps)
                        best.text = "$bestBits bits"
                    }
                    lastUi = now
                }
            }
            val elapsed = (SystemClock.elapsedRealtime() - start).coerceAtLeast(1L)
            val finalRate = hashes.toDouble() * 1000.0 / elapsed.toDouble()
            running.set(false)
            runOnUiThread {
                rate.text = formatRate(finalRate)
                best.text = "$bestBits bits"
                status.text = "Completed: $hashes double-SHA-256 hashes in ${elapsed / 1000.0}s. No BTC mined."
                startButton.isEnabled = true
                stopButton.isEnabled = false
            }
        }
    }

    private fun stopBenchmark(message: String) {
        running.set(false)
        status.text = message
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
            if (value == 0) count += 8
            else {
                count += Integer.numberOfLeadingZeros(value) - 24
                break
            }
        }
        return count
    }
}

private object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val generators = intArrayOf(
        0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3
    )

    fun isValidWitnessAddress(address: String): Boolean {
        if (address.length !in 14..90 || address != address.lowercase()) return false
        val separator = address.lastIndexOf('1')
        if (separator !in 1..address.length - 7) return false
        val hrp = address.substring(0, separator)
        if (hrp != "bc") return false
        val values = ArrayList<Int>()
        for (char in address.substring(separator + 1)) {
            val value = CHARSET.indexOf(char)
            if (value < 0) return false
            values.add(value)
        }
        val expanded = ArrayList<Int>()
        hrp.forEach { expanded.add(it.code shr 5) }
        expanded.add(0)
        hrp.forEach { expanded.add(it.code and 31) }
        expanded.addAll(values)
        val check = polymod(expanded)
        val version = values.firstOrNull() ?: return false
        if (version !in 0..16) return false
        return if (version == 0) check == 1 else check == 0x2bc830a3
    }

    private fun polymod(values: List<Int>): Int {
        var checksum = 1
        for (value in values) {
            val top = checksum ushr 25
            checksum = ((checksum and 0x1ffffff) shl 5) xor value
            for (i in 0..4) {
                if (((top ushr i) and 1) != 0) checksum = checksum xor generators[i]
            }
        }
        return checksum
    }
}
