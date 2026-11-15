package com.vertil.miningbtc

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.security.MessageDigest
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val bg = Color.rgb(8, 14, 23)
    private val panel = Color.rgb(17, 27, 40)
    private val muted = Color.rgb(145, 163, 184)
    private val white = Color.rgb(239, 245, 251)
    private val orange = Color.rgb(247, 147, 26)
    private val green = Color.rgb(34, 197, 94)
    private val red = Color.rgb(239, 68, 68)
    private val yellow = Color.rgb(234, 179, 8)
    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var root: LinearLayout
    private lateinit var benchmarkPanel: LinearLayout
    private lateinit var stratumPanel: LinearLayout
    private lateinit var benchStatus: TextView
    private lateinit var benchRate: TextView
    private lateinit var benchAvg: TextView
    private lateinit var benchHashes: TextView
    private lateinit var benchThreads: TextView
    private lateinit var benchPath: TextView
    private lateinit var benchSelf: TextView
    private lateinit var benchDetails: TextView
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var userInput: EditText
    private lateinit var workerNameInput: EditText
    private lateinit var passInput: EditText
    private lateinit var tlsInput: CheckBox
    private lateinit var status: TextView
    private lateinit var rate: TextView
    private lateinit var hashes: TextView
    private lateinit var accepted: TextView
    private lateinit var rejected: TextView
    private lateinit var detail: TextView
    private lateinit var bestShareDiff: TextView
    private lateinit var bestShareBits: TextView
    private lateinit var bestShareHash: TextView
    private lateinit var bestShareTime: TextView
    private lateinit var topHashesLabel: TextView
    private lateinit var hashFeedView: TextView
    private lateinit var thermalDetail: TextView
    private lateinit var networkTargetLabel: TextView
    private lateinit var thermalBar: TextView
    private lateinit var totalHashesLabel: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private var service: MiningService? = null
    private var bound = false
    private var benchRunning = false
    private var feedPaused = false
    private var feedRateHz = 30
    private var latestStats: MinerStats? = null

    private val header = ByteArray(80).also {
        val h = "01000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "3ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa4b1e5e4a" +
            "29ab5f49" + "ffff001d" + "1dac2b7c"
        for (i in 0 until 80) it[i] = h.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as? MiningService.LocalBinder)?.service() ?: return
            service = s
            bound = true
            s.setHashFeedRate(feedRateHz)
            s.registerListener(statsListener)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service?.unregisterListener(statsListener)
            service = null
            bound = false
        }
    }

    private val statsListener: (MinerStats) -> Unit = { stats ->
        runOnUiThread {
            latestStats = stats
            rate.text = formatRate(stats.hashrate)
            hashes.text = "Hashes (sesión): " + String.format(Locale.US, "%,d", stats.totalHashes)
            totalHashesLabel.text = "Hashes totales (acumulados): " + String.format(Locale.US, "%,d", stats.totalHashes)
            accepted.text = "Accepted: " + stats.accepted
            rejected.text = "Rejected: " + stats.rejected
            status.text = "● " + stats.state.uppercase(Locale.US)
            status.setTextColor(
                when (stats.state) {
                    "Mining" -> green
                    "Stopped", "Disconnected", "Error" -> red
                    "Throttled" -> yellow
                    else -> white
                }
            )
            detail.text = stats.message +
                " · Pool diff: " + stats.difficulty +
                (if (stats.latencyMs >= 0) " · Latency: " + stats.latencyMs + " ms" else "") +
                (if (stats.rejectionReason.isNotBlank()) " · Rejection: " + stats.rejectionReason else "") +
                (if (stats.difficultySuggestion.isNotBlank()) " · Diff req: " + stats.difficultySuggestion else "")

            bestShareDiff.text = "Best share diff: " + formatDifficulty(stats.bestShareDifficulty)
            bestShareBits.text = "Bits cero: " + stats.bestShareBits + " / ~78 (red Bitcoin)"
            bestShareHash.text = if (stats.bestShareHashHex.isNotBlank()) "Hash: " + stats.bestShareHashHex else "Hash: —"
            bestShareTime.text = if (stats.bestShareTimestampMs > 0L)
                "Nonce: %08x · Hora: %s".format(Locale.US, stats.bestShareNonce, SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(stats.bestShareTimestampMs)))
            else "Nonce: — · Hora: —"
            topHashesLabel.text = stats.topHashes.mapIndexed { index, entry ->
                "%2d  %2db  %08x  %s".format(Locale.US, index + 1, entry.bits, entry.nonce, entry.hashHex)
            }.joinToString("\n").ifBlank { "Esperando hashes…" }
            if (!feedPaused) renderHashFeed(stats.hashFeed)

            val temp = stats.cpuTempC
            val threads = stats.effectiveThreads
            thermalBar.text = "CPU: %.1f°C · batería: %.1f°C · hilos: %d/%d".format(Locale.US, temp, stats.batteryTempC, threads, 8)
            thermalDetail.text = "Sensor CPU: ${stats.cpuSensorName} · PowerManager thermal status: ${thermalStatusName(stats.thermalStatus)}"
            networkTargetLabel.text = if (stats.networkBitsHex.isNotBlank()) "Objetivo red: nBits ${stats.networkBitsHex} · dificultad ${formatDifficulty(stats.networkDifficulty)} · faltan ${stats.bitsMissing} bits" else "Objetivo red: esperando mining.notify"
            thermalBar.setTextColor(
                when {
                    temp >= 85.0 -> red
                    temp >= 75.0 -> yellow
                    temp > 0.0 -> green
                    else -> muted
                }
            )

            if (stats.state == "Stopped") {
                startButton.isEnabled = true
                stopButton.isEnabled = false
            } else if (stats.state == "Mining" || stats.state == "Throttled" || stats.state == "Waiting for work") {
                startButton.isEnabled = false
                stopButton.isEnabled = true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(26))
            setBackgroundColor(bg)
        }
        root.addView(label("₿  POW LAB", 23f, white, true))
        root.addView(label("VERTIL  /  SHA-256d PROOF OF WORK", 10f, muted, true).padBottom(16))

        val selector = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        selector.addView(action("BENCHMARK LOCAL") { showMode(true) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        selector.addView(action("MINERÍA STRATUM") { showMode(false) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) })
        root.addView(selector)

        benchmarkPanel = buildBenchmark()
        stratumPanel = buildStratum()
        root.addView(benchmarkPanel)
        root.addView(stratumPanel)
        showMode(true)
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })

        runSelfTest()
        requestNotificationPermissionIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        // Bind to the running service if it exists; this lets us pick up stats
        // after a screen rotation or app return without restarting the miner.
        val intent = Intent(this, MiningService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            service?.unregisterListener(statsListener)
            unbindService(connection)
            bound = false
            service = null
        }
    }

    override fun onDestroy() {
        benchRunning = false
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun buildBenchmark(): LinearLayout {
        val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        p.addView(label("BENCHMARK NATIVO SHA-256d", 11f, muted, true).padTop(20))
        val c = card()
        benchStatus = label("Preparando autoprueba…", 13f, white, true)
        c.addView(benchStatus)
        benchSelf = label("Autoprueba: pendiente", 11f, muted, false).padTop(10)
        c.addView(benchSelf)
        benchPath = label("Motor: detectando…", 11f, muted, false).padTop(8)
        c.addView(benchPath)
        benchRate = label("0 H/s", 34f, white, true).padTop(16)
        c.addView(benchRate)
        benchAvg = label("Promedio de sesión: 0 H/s", 11f, muted, false)
        c.addView(benchAvg)
        benchHashes = label("Hashes totales: 0", 12f, white, true).padTop(10)
        c.addView(benchHashes)
        benchThreads = label("Hilos: —", 11f, muted, false).padTop(5)
        c.addView(benchThreads)
        benchDetails = label(
            "La autoprueba compara el bloque génesis con SHA2 ARM y el fallback portable.",
            10f, muted, false
        ).padTop(10)
        c.addView(benchDetails)
        p.addView(c)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        buttons.addView(action("INICIAR NATIVO MULTIHILO") { runNative(0) })
        buttons.addView(action("NATIVO 1 HILO") { runNative(1) })
        buttons.addView(action("COMPARAR JAVA / NATIVO 1 HILO / MULTIHILO") { runComparison() })
        buttons.addView(action("DETENER BENCHMARK") { benchRunning = false })
        p.addView(buttons)
        return p
    }

    private fun buildStratum(): LinearLayout {
        val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val prefs = getSharedPreferences("pool_config", MODE_PRIVATE)
        p.addView(label("STRATUM V1 · POOL SOLO MAINNET", 11f, muted, true).padTop(20))
        p.addView(label(
            "Recomendado: solo.ckpool.org:3333 (sin TLS). Wallet no-custodial (BlueWallet/Electrum/hardware). NUNCA uses una dirección de exchange.",
            10f, yellow, false
        ).padTop(6))
        val c = card()
        hostInput = field("Pool hostname", prefs.getString("host", "solo.ckpool.org") ?: "solo.ckpool.org")
        portInput = field("Port", prefs.getString("port", "3333") ?: "3333", true)
        // Keep the payout address separate from the Stratum username (wallet.worker).
        // Migrate older installs whose only saved value was the composed username.
        val savedWallet = WalletConfig.restoreWallet(
            prefs.getString("wallet", null),
            prefs.getString("user", null)
        )
        userInput = field("Wallet (Bech32 mainnet)", savedWallet)
        workerNameInput = field("Worker name", prefs.getString("worker_name", "worker1") ?: "worker1")
        passInput = field("Worker password", prefs.getString("pass", "x") ?: "x")
        tlsInput = CheckBox(this).apply {
            text = "Use TLS"
            setTextColor(white)
            isChecked = prefs.getBoolean("tls", false)
        }
        for ((title, v) in listOf(
            "POOL HOST" to hostInput,
            "POOL PORT" to portInput,
            "WALLET BECH32 (bc1…)" to userInput,
            "WORKER NAME" to workerNameInput,
            "POOL PASSWORD" to passInput
        )) {
            c.addView(label(title, 9f, muted, true))
            c.addView(v)
        }
        c.addView(tlsInput)
        p.addView(c)

        val s = card()
        status = label("● DISCONNECTED", 12f, white, true)
        s.addView(status)
        detail = label("Configura pool y wallet.", 11f, muted, false).padTop(8)
        s.addView(detail)
        rate = label("0 H/s", 25f, white, true).padTop(12)
        s.addView(rate)
        hashes = label("Hashes (sesión): 0", 11f, muted, false)
        s.addView(hashes)
        totalHashesLabel = label("Hashes totales (acumulados): 0", 11f, muted, false)
        s.addView(totalHashesLabel)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        accepted = label("Accepted: 0", 11f, white, true)
        rejected = label("Rejected: 0", 11f, white, true)
        row.addView(accepted, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(rejected, LinearLayout.LayoutParams(0, -2, 1f))
        s.addView(row)

        // Telemetry: best share difficulty, leading zero bits, hash.
        // These are the metrics that matter when solo mining: they tell you
        // how close you got to a real mainnet block.
        s.addView(label("MEJOR SHARE ENCONTRADO", 10f, orange, true).padTop(14))
        bestShareDiff = label("Best share diff: 0", 12f, white, true)
        s.addView(bestShareDiff)
        bestShareBits = label("Bits cero: 0 / ~78 (red Bitcoin)", 10f, muted, false)
        s.addView(bestShareBits)
        bestShareHash = label("Hash: —", 9f, muted, false).apply { typeface = Typeface.MONOSPACE }
        s.addView(bestShareHash)
        bestShareTime = label("Nonce: — · Hora: —", 10f, muted, false)
        s.addView(bestShareTime)
        networkTargetLabel = label("Objetivo red: esperando mining.notify", 10f, yellow, false).padTop(8)
        s.addView(networkTargetLabel)

        // Thermal + thread indicator
        thermalBar = label("CPU: 0.0°C · batería: 0.0°C · hilos: 0/8", 11f, muted, true).padTop(12)
        s.addView(thermalBar)
        thermalDetail = label("Sensor CPU: — · PowerManager thermal status: —", 9f, muted, false)
        s.addView(thermalDetail)
        s.addView(label("TOP 10 MEJORES HASHES · MUESTRA EN VIVO", 10f, orange, true).padTop(14))
        topHashesLabel = label("Esperando hashes…", 9f, muted, false).apply { typeface = Typeface.MONOSPACE }
        s.addView(topHashesLabel)
        s.addView(label("HASH FEED EN VIVO · MÁXIMO 200 FILAS", 10f, orange, true).padTop(14))
        hashFeedView = label("Esperando hashes nativos…", 9f, muted, false).apply { typeface = Typeface.MONOSPACE; maxLines = 200 }
        s.addView(hashFeedView)
        val feedControls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        feedControls.addView(action("10/s") { setFeedRate(10) }, LinearLayout.LayoutParams(0, dp(40), 1f))
        feedControls.addView(action("30/s") { setFeedRate(30) }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { leftMargin = dp(4) })
        feedControls.addView(action("60/s") { setFeedRate(60) }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { leftMargin = dp(4) })
        s.addView(feedControls)
        val feedActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        feedActions.addView(action("PAUSAR / REANUDAR") {
            feedPaused = !feedPaused
            if (!feedPaused) latestStats?.let { renderHashFeed(it.hashFeed) }
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        feedActions.addView(action("EXPORTAR CSV") { exportMiningCsv() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(6) })
        s.addView(feedActions)
        p.addView(s)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startButton = action("START MINING") { startMining() }
        stopButton = action("STOP") { stopMining() }
        stopButton.isEnabled = false
        actions.addView(startButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(stopButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) })
        p.addView(actions)
        p.addView(action("SIMULAR ALERTA DE BLOQUE (TARGET FALSO)") {
            service?.simulateBlockAlert() ?: toast("Servicio todavía no está enlazado")
        })
        return p
    }

    private fun showMode(local: Boolean) {
        benchmarkPanel.visibility = if (local) View.VISIBLE else View.GONE
        stratumPanel.visibility = if (local) View.GONE else View.VISIBLE
    }

    private fun runSelfTest() {
        executor.execute {
            try {
                val report = NativePowEngine.nativeSelfTest()
                runOnUiThread {
                    benchSelf.text = report
                    benchStatus.text = if (report.contains("genesis_portable=OK") &&
                        (report.contains("arm_vs_portable=OK") || report.contains("arm_vs_portable=NOT_AVAILABLE"))
                    ) "AUTOPRUEBA PORTABLE OK" + (if (report.contains("arm_vs_portable=OK")) " · ARM OK" else " · ARM NO DISPONIBLE")
                    else "AUTOPRUEBA FALLIDA"
                    benchPath.text = "Motor: " + NativePowEngine.nativeCapabilities()
                    benchDetails.text = report
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    benchStatus.text = "AUTOPRUEBA FALLIDA"
                    benchSelf.text = t.message ?: t.javaClass.simpleName
                }
            }
        }
    }

    private fun runNative(threads: Int) {
        if (benchRunning) return
        benchRunning = true
        benchStatus.text = "Benchmark nativo en ejecución…"
        executor.execute {
            try {
                val useArm = NativePowEngine.nativeCapabilities().contains("arm64_sha2=true")
                val result = NativePowEngine.nativeBenchmark(header, 10000L, threads, useArm, 0)
                val rate = result[0] / 10.0
                runOnUiThread {
                    benchRunning = false
                    benchRate.text = formatRate(rate)
                    benchAvg.text = "Promedio de 10 s: " + formatRate(rate)
                    benchHashes.text = "Hashes totales: " + String.format(Locale.US, "%,d", result[0])
                    benchThreads.text = "Hilos: ${result[1]} · CPUs detectadas: ${result[2]}"
                    benchStatus.text = "Benchmark finalizado"
                    benchPath.text = if (useArm) "Motor: ARMv8 SHA2" else "Motor: portable SHA-256"
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    benchRunning = false
                    benchStatus.text = "ERROR: ${t.message}"
                }
            }
        }
    }

    private fun runComparison() {
        if (benchRunning) return
        benchRunning = true
        benchStatus.text = "Comparación: Java → nativo 1 hilo → nativo multihilo"
        executor.execute {
            try {
                val javaCount = javaBenchmark(5000)
                val useArm = NativePowEngine.nativeCapabilities().contains("arm64_sha2=true")
                val one = NativePowEngine.nativeBenchmark(header, 5000, 1, useArm, 0)
                val multi = NativePowEngine.nativeBenchmark(header, 5000, 0, useArm, 0)
                runOnUiThread {
                    benchRunning = false
                    benchRate.text = "Java " + formatRate(javaCount / 5.0)
                    benchAvg.text = "Nativo 1 hilo: " + formatRate(one[0] / 5.0) + " · Multihilo: " + formatRate(multi[0] / 5.0)
                    benchHashes.text = "Hashes: Java ${javaCount} · Nativo 1H ${one[0]} · Multi ${multi[0]}"
                    benchThreads.text = "Hilos nativo: 1 / ${multi[1]}"
                    benchStatus.text = "Comparación completada"
                    benchDetails.text = "Mediciones independientes de 5 segundos. Sujetas a temperatura del dispositivo."
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    benchRunning = false
                    benchStatus.text = "ERROR: ${t.message}"
                }
            }
        }
    }

    private fun javaBenchmark(ms: Long): Long {
        val md = MessageDigest.getInstance("SHA-256")
        var n = 0L
        val until = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < until) {
            val a = md.digest(header)
            md.digest(a)
            n++
        }
        return n
    }

    private fun startMining() {
        val host = hostInput.text.toString().trim()
        val port = portInput.text.toString().trim().toIntOrNull()
        val wallet = userInput.text.toString().trim()
        val worker = workerNameInput.text.toString().trim()
        val pass = passInput.text.toString()
        val tls = tlsInput.isChecked

        if (host.isBlank() || port == null || port !in 1..65535) {
            toast("Pool host/port inválidos")
            return
        }
        if (wallet.isBlank()) {
            toast("Falta la wallet Bech32")
            return
        }
        // Bech32 / Bech32m validation: a malformed wallet means any block
        // reward would go to a stranger. Do not allow START on bad input.
        if (!Bech32.isValidMainnet(wallet)) {
            toast("Wallet no es Bech32/Bech32m válida en mainnet (bc1…)")
            return
        }
        val user = WalletConfig.stratumUsername(wallet, worker)
        getSharedPreferences("pool_config", MODE_PRIVATE).edit()
            .putString("host", host)
            .putString("port", port.toString())
            .putString("wallet", wallet)
            .putString("user", user)
            .putString("worker_name", worker)
            .putString("pass", pass)
            .putBoolean("tls", tls)
            .apply()

        // Foreground service keeps the miner alive when the screen is off
        // and under Doze. This is the difference between "mines for 5 min"
        // and "mines 24/7".
        val intent = Intent(this, MiningService::class.java).setAction(MiningService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        // Bind so the Activity receives stats callbacks.
        bindService(Intent(this, MiningService::class.java), connection, Context.BIND_AUTO_CREATE)
        startButton.isEnabled = false
        stopButton.isEnabled = true
        status.text = "● STARTING"
        status.setTextColor(yellow)
    }

    private fun stopMining() {
        val intent = Intent(this, MiningService::class.java).setAction(MiningService.ACTION_STOP)
        startService(intent)
        startButton.isEnabled = true
        stopButton.isEnabled = false
        status.text = "● STOPPED"
        status.setTextColor(red)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        // On Android 14+ a non-Play-Store app also needs the user to disable
        // battery optimizations for the foreground service to truly survive
        // Doze. Open the system settings page so the user can whitelist us.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName"))
                    startActivity(intent)
                } catch (_: Exception) {}
            }
        }
    }

    private fun setFeedRate(rate: Int) {
        feedRateHz = rate
        service?.setHashFeedRate(rate)
        toast("Hash feed configurado: $rate filas/s")
    }

    private fun renderHashFeed(entries: List<HashFeedEntry>) {
        val out = SpannableStringBuilder()
        for (entry in entries) {
            val time = SimpleDateFormat("HH:mm:ss.SS", Locale.US).format(Date(entry.timestampMs))
            val line = "%s  %08x  %2db  %s  %s\n".format(Locale.US, time, entry.nonce, entry.bits, entry.hashHex, entry.status)
            val start = out.length
            out.append(line)
            val color = when (entry.status) {
                "accepted" -> green
                "rejected" -> red
                "threshold", "pending" -> orange
                else -> muted
            }
            out.setSpan(ForegroundColorSpan(color), start, out.length, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        hashFeedView.text = out
    }

    private fun thermalStatusName(status: Int): String = when (status) {
        0 -> "NONE"; 1 -> "LIGHT"; 2 -> "MODERATE"; 3 -> "SEVERE"
        4 -> "CRITICAL"; 5 -> "EMERGENCY"; 6 -> "SHUTDOWN"; else -> "UNAVAILABLE"
    }

    private fun exportMiningCsv() {
        val stats = latestStats
        val telemetry = service?.telemetryCsvText().orEmpty()
        val feed = buildString {
            append("\n# hash_feed_sample\n")
            append("timestamp_ms,nonce,bits,difficulty,hash,status\n")
            stats?.hashFeed?.forEach { row ->
                append("${row.timestampMs},${row.nonce},${row.bits},${row.difficulty},${row.hashHex},${row.status}\n")
            }
            append("\n# top10\n")
            stats?.topHashes?.forEach { row ->
                append("${row.timestampMs},${row.nonce},${row.bits},${row.difficulty},${row.hashHex},${row.status}\n")
            }
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, "Mining BTC telemetry")
            putExtra(Intent.EXTRA_TEXT, telemetry + feed)
        }
        startActivity(Intent.createChooser(send, "Exportar telemetría y hash feed"))
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(panel, 16)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(10)
            bottomMargin = dp(10)
        }
    }

    private fun field(h: String, v: String, n: Boolean = false) = EditText(this).apply {
        hint = h
        setText(v)
        setTextColor(white)
        setHintTextColor(muted)
        textSize = 13f
        setSingleLine(true)
        inputType = if (n) android.text.InputType.TYPE_CLASS_NUMBER else android.text.InputType.TYPE_CLASS_TEXT
        background = rounded(bg, 9)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        layoutParams = LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(8) }
    }

    private fun action(t: String, fn: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        textSize = 11f
        setTextColor(Color.BLACK)
        typeface = Typeface.DEFAULT_BOLD
        background = rounded(orange, 11)
        setOnClickListener { fn() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(7) }
    }

    private fun label(t: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun rounded(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
    }

    private fun TextView.padTop(v: Int) = apply { setPadding(paddingLeft, dp(v), paddingRight, paddingBottom) }
    private fun TextView.padBottom(v: Int) = apply { setPadding(paddingLeft, paddingTop, paddingRight, dp(v)) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun formatRate(v: Double) = when {
        v >= 1_000_000 -> String.format(Locale.US, "%.2f MH/s", v / 1_000_000)
        v >= 1_000 -> String.format(Locale.US, "%.2f kH/s", v / 1_000)
        else -> String.format(Locale.US, "%.2f H/s", v)
    }

    private fun formatDifficulty(d: Double): String = when {
        d.isInfinite() -> "∞ (BLOCK FOUND!)"
        d <= 0.0 -> "0"
        d >= 1_000_000_000_000.0 -> String.format(Locale.US, "%.3e", d)
        d >= 1_000_000.0 -> String.format(Locale.US, "%.2f G", d / 1_000_000_000.0)
        d >= 1_000.0 -> String.format(Locale.US, "%.2f M", d / 1_000_000.0)
        else -> String.format(Locale.US, "%.2f", d)
    }
}
