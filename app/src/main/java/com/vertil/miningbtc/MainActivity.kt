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
import android.os.SystemClock
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
    private val exportCsvRequestCode = 501
    private val exportStatsRequestCode = 502
    private val importStatsRequestCode = 503
    private var pendingExportText = ""
    private var pendingStatsExportText = ""
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
    private lateinit var feedScroll: ScrollView
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
    private var configSaved = false
    private var miningScreen = false
    private var previousHashCount = 0L
    private var previousHashTimeNs = 0L
    private var smoothedFallbackRate = 0.0
    private var latestStats: MinerStats? = null
    private val renderedFeedStatuses = LinkedHashMap<String, String>()

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
            val nowNs = SystemClock.elapsedRealtimeNanos()
            val deltaHashes = stats.totalHashes - previousHashCount
            val deltaNs = nowNs - previousHashTimeNs
            if (deltaHashes > 0 && deltaNs > 0 && stats.hashrate <= 0.0) {
                val measured = deltaHashes * 1_000_000_000.0 / deltaNs
                smoothedFallbackRate = if (smoothedFallbackRate <= 0.0) measured else smoothedFallbackRate * 0.65 + measured * 0.35
            }
            previousHashCount = stats.totalHashes
            previousHashTimeNs = nowNs
            val visibleRate = if (stats.hashrate > 0.0) stats.hashrate else smoothedFallbackRate
            if (miningScreen) {
                rate.text = formatRate(visibleRate)
                bestShareDiff.text = if (stats.bestShareBits > 0) "BEST ${stats.bestShareBits}b / ${formatDifficulty(stats.bestShareDifficulty)}" else "BEST —"
                bestShareBits.text = if (stats.bitsMissing >= 0) "NET −${stats.bitsMissing}b" else "NET —"
                accepted.text = "S ${stats.accepted}/${stats.rejected}"
                rejected.text = "CPU ${if (stats.cpuTempC > 0) "%.1f°".format(Locale.US, stats.cpuTempC) else "—"}"
                status.text = if (stats.state == "Mining") "● ONLINE" else "● " + stats.state.uppercase(Locale.US)
                status.setTextColor(when (stats.state) {
                    "Mining" -> green
                    "Throttled" -> yellow
                    "Stopped", "Disconnected", "Error" -> red
                    else -> muted
                })
                thermalBar.text = "BAT %.1f° · %s".format(Locale.US, stats.batteryTempC, thermalStatusName(stats.thermalStatus))
                networkTargetLabel.text = if (stats.networkBitsHex.isNotBlank()) "TARGET ${stats.networkBitsHex} · D ${formatDifficulty(stats.networkDifficulty)}" else "TARGET —"
                if (!feedPaused) renderHashFeed(stats.hashFeed)
            }
            if (stats.state == "Stopped" || stats.state == "Error" || stats.state == "Disconnected") {
                if (miningScreen) showConfigScreen()
            }
        }
    }

        override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(8))
            setBackgroundColor(bg)
        }
        benchmarkPanel = buildBenchmark()
        stratumPanel = buildStratum()
        configSaved = getSharedPreferences("pool_config", MODE_PRIVATE).getBoolean("config_saved", false)
        showConfigScreen()
        setContentView(root)
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

    @Deprecated("Uses the platform document picker for CSV export")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == exportCsvRequestCode && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(pendingExportText.toByteArray(Charsets.UTF_8)) }
                toast("CSV exportado correctamente")
            } catch (e: Exception) {
                toast("No se pudo exportar CSV: ${e.message}")
            }
        } else if (requestCode == exportStatsRequestCode && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(pendingStatsExportText.toByteArray(Charsets.UTF_8)) }
                toast("Estadísticas exportadas")
            } catch (e: Exception) { toast("Error al exportar: ${e.message}") }
        } else if (requestCode == importStatsRequestCode && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("No se pudo leer el archivo")
                val targetService = service ?: error("Servicio no disponible")
                targetService.importStatsJson(json)
                toast("Estadísticas importadas")
            } catch (e: Exception) { toast("Importación rechazada: ${e.message}") }
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
        p.addView(label("CONFIGURACIÓN", 18f, white, true).padBottom(4))
        p.addView(label("STRATUM SOLO · MAINNET", 9f, muted, true).padBottom(18))
        hostInput = field("Host", prefs.getString("host", "solo.ckpool.org") ?: "solo.ckpool.org")
        portInput = field("Puerto", prefs.getString("port", "3333") ?: "3333", true)
        val savedWallet = WalletConfig.restoreWallet(prefs.getString("wallet", null), prefs.getString("user", null))
        userInput = field("Wallet Bech32", savedWallet)
        workerNameInput = field("Worker", prefs.getString("worker_name", "worker1") ?: "worker1")
        passInput = field("Password", prefs.getString("pass", "x") ?: "x")
        tlsInput = CheckBox(this).apply {
            text = "TLS"; textSize = 12f; setTextColor(white)
            isChecked = prefs.getBoolean("tls", false)
            buttonTintList = android.content.res.ColorStateList.valueOf(orange)
        }
        listOf("HOST" to hostInput, "PUERTO" to portInput, "WALLET (BECH32)" to userInput,
            "WORKER" to workerNameInput, "PASSWORD" to passInput).forEach { (title, input) ->
            p.addView(label(title, 9f, muted, true).padTop(8))
            p.addView(input)
        }
        p.addView(tlsInput)
        val error = label("", 10f, red, false).apply { tag = "config_error" }
        p.addView(error)
        p.addView(action("GUARDAR") { savePoolConfiguration(error) }.apply {
            tag = "save_config"; background = rounded(bg, 8); setTextColor(orange)
        })
        startButton = action("EMPEZAR MINERÍA") { startMining() }.apply {
            visibility = if (configSaved) View.VISIBLE else View.GONE
            background = rounded(orange, 8); setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(28) }
        }
        p.addView(startButton)
        p.addView(action("AJUSTES") { showSettingsMenu() }.apply {
            background = rounded(panel, 8); setTextColor(muted)
            layoutParams = LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(12) }
        })
        status = label("● CONECTANDO", 9f, green, true)
        rate = label("0.00 H/s", 25f, white, true)
        bestShareDiff = label("BEST —", 9f, orange, true)
        bestShareBits = label("NET —", 9f, muted, true)
        accepted = label("S 0/0", 9f, green, true)
        rejected = label("CPU —", 9f, muted, true)
        thermalBar = label("BAT — · NORMAL", 8f, muted, false)
        networkTargetLabel = label("TARGET —", 8f, muted, false)
        detail = label("", 8f, muted, false); hashes = label("", 8f, muted, false)
        totalHashesLabel = label("", 8f, muted, false); bestShareHash = label("", 8f, muted, false)
        bestShareTime = label("", 8f, muted, false); thermalDetail = label("", 8f, muted, false)
        topHashesLabel = label("", 8f, muted, false)
        hashFeedView = label("Esperando muestra en vivo…", 9f, red, false).apply {
            typeface = Typeface.MONOSPACE; maxLines = 200; setHorizontallyScrolling(true)
            setPadding(0, dp(5), 0, dp(5))
        }
        feedScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(hashFeedView) }
        stopButton = action("DETENER") { stopMining() }.apply {
            background = rounded(bg, 7); setTextColor(red)
            layoutParams = LinearLayout.LayoutParams(dp(104), dp(34)).apply { topMargin = dp(5) }
        }
        return p
    }

    private fun savePoolConfiguration(errorView: TextView) {
        val host = hostInput.text.toString().trim()
        val port = portInput.text.toString().trim().toIntOrNull()
        val wallet = userInput.text.toString().trim()
        val worker = workerNameInput.text.toString().trim()
        val password = passInput.text.toString()
        val error = when {
            host.isBlank() || host.any { it.isWhitespace() } -> "Host inválido."
            port == null || port !in 1..65535 -> "Puerto inválido: rango 1–65535."
            !WalletConfig.isValidPayoutAddress(wallet) -> "Wallet inválida. Se requiere Bech32/Bech32m mainnet (bc1…)."
            worker.isBlank() || worker.any { it.isWhitespace() } -> "Worker inválido."
            password.isBlank() -> "Password no puede estar vacío."
            else -> ""
        }
        if (error.isNotEmpty()) { errorView.text = error; return }
        getSharedPreferences("pool_config", MODE_PRIVATE).edit()
            .putString("host", host).putString("port", port.toString())
            .putString("wallet", wallet).putString("user", WalletConfig.stratumUsername(wallet, worker))
            .putString("worker_name", worker).putString("pass", password)
            .putBoolean("tls", tlsInput.isChecked).putBoolean("config_saved", true).apply()
        configSaved = true
        listOf(hostInput, portInput, userInput, workerNameInput, passInput, tlsInput, errorView)
            .forEach { it.visibility = View.GONE }
        stratumPanel.findViewWithTag<View>("save_config")?.visibility = View.GONE
        startButton.visibility = View.VISIBLE
    }

    private fun showConfigScreen() {
        miningScreen = false
        root.removeAllViews()
        root.addView(stratumPanel, LinearLayout.LayoutParams(-1, -1))
        startButton.visibility = if (configSaved) View.VISIBLE else View.GONE
        if (configSaved) {
            listOf(hostInput, portInput, userInput, workerNameInput, passInput, tlsInput).forEach { it.visibility = View.GONE }
            stratumPanel.findViewWithTag<View>("save_config")?.visibility = View.GONE
        }
    }

    private fun showMiningScreen() {
        miningScreen = true
        feedRateHz = 30
        service?.setHashFeedRate(30)
        root.removeAllViews()
        val metrics = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(rate, LinearLayout.LayoutParams(0, -2, 1.2f))
                addView(status, LinearLayout.LayoutParams(0, -2, 1f))
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(bestShareDiff, LinearLayout.LayoutParams(0, -2, 1f))
                addView(bestShareBits, LinearLayout.LayoutParams(0, -2, 1f))
                addView(accepted, LinearLayout.LayoutParams(0, -2, 1f))
                addView(rejected, LinearLayout.LayoutParams(0, -2, 1f))
            })
            addView(thermalBar); addView(networkTargetLabel)
            setPadding(0, 0, 0, dp(6))
        }
        root.addView(metrics, LinearLayout.LayoutParams(-1, -2))
        root.addView(label("MUESTRA EN VIVO", 8f, muted, true))
        root.addView(feedScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(LinearLayout(this).apply { gravity = Gravity.END; addView(stopButton) }, LinearLayout.LayoutParams(-1, -2))
        latestStats?.let { rate.text = formatRate(if (it.hashrate > 0) it.hashrate else smoothedFallbackRate); renderHashFeed(it.hashFeed) }
    }

    private fun showSettingsMenu() {
        val items = arrayOf("Benchmark local", "Exportar CSV y registros", "Exportar estadísticas JSON",
            "Importar estadísticas JSON", "Simular alerta de bloque", "Depuración")
        android.app.AlertDialog.Builder(this).setTitle("AJUSTES").setItems(items) { _, which ->
            when (which) {
                0 -> { root.removeAllViews(); root.addView(benchmarkPanel); root.addView(action("VOLVER") { showConfigScreen() }) }
                1 -> exportMiningCsv()
                2 -> exportStatsJson()
                3 -> importStatsJson()
                4 -> service?.simulateBlockAlert() ?: toast("Servicio no disponible")
                5 -> showDebugLog()
            }
        }.show()
    }

    private fun showDebugLog() {
        val stats = latestStats
        val content = buildString {
            append("Estado: ").append(stats?.state ?: "Idle").append('\n')
            append("Mensaje: ").append(stats?.message ?: "—").append('\n')
            append("Pool difficulty: ").append(stats?.difficulty ?: 0.0).append('\n')
            append("Diff req: ").append(stats?.difficultySuggestion ?: "—").append('\n')
            append("Submit latency: ").append(stats?.lastSubmitLatencyMs ?: -1).append(" ms\n")
            append("Stale shares: ").append(stats?.staleShares ?: 0).append('\n')
            append("CPU sensor: ").append(stats?.cpuSensorName ?: "—").append('\n')
            append("Thermal status: ").append(stats?.thermalStatus ?: -1).append('\n')
            append("Target: ").append(stats?.networkBitsHex ?: "—").append('\n')
            append("nBits difficulty: ").append(stats?.networkDifficulty ?: 0.0)
        }
        android.app.AlertDialog.Builder(this).setTitle("LOG DE DEPURACIÓN").setMessage(content).setPositiveButton("Cerrar", null).show()
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
        if (!configSaved) { toast("Primero guarda y valida la configuración."); return }
        val intent = Intent(this, MiningService::class.java).setAction(MiningService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        if (!bound) bindService(Intent(this, MiningService::class.java), connection, Context.BIND_AUTO_CREATE)
        showMiningScreen()
        status.text = "● CONECTANDO"; status.setTextColor(yellow)
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
        // Explain the battery-optimization exemption before opening Android settings.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Permitir minería en segundo plano")
                    .setMessage("Para mantener el hashrate con la pantalla apagada, excluye Mining BTC de la optimización de batería. Android puede limitar el CPU durante Doze.")
                    .setNegativeButton("Ahora no", null)
                    .setPositiveButton("Abrir ajustes") { _, _ ->
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(Uri.parse("package:$packageName"))
                            startActivity(intent)
                        } catch (_: Exception) {
                            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    }
                    .show()
            }
        }
    }

    private fun exportStatsJson() {
        try {
            pendingStatsExportText = (service ?: error("Servicio no disponible")).exportStatsJson()
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "mining-btc-statistics.json")
            }
            startActivityForResult(intent, exportStatsRequestCode)
        } catch (e: Exception) { toast("No se pudo preparar la exportación: ${e.message}") }
    }

    private fun importStatsJson() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        startActivityForResult(intent, importStatsRequestCode)
    }

    private fun setFeedRate(rate: Int) {
        feedRateHz = rate
        service?.setHashFeedRate(rate)
        toast("Hash feed configurado: $rate filas/s")
    }

    private fun renderHashFeed(entries: List<HashFeedEntry>) {
        val changedStatus = entries.any { entry ->
            val key = "${entry.timestampMs}:${entry.nonce}"
            renderedFeedStatuses.containsKey(key) && renderedFeedStatuses[key] != entry.status
        }
        if (changedStatus) { renderedFeedStatuses.clear(); hashFeedView.text = "" }
        val out = SpannableStringBuilder(hashFeedView.text)
        for (entry in entries) {
            val key = "${entry.timestampMs}:${entry.nonce}"
            if (renderedFeedStatuses.containsKey(key)) continue
            val time = SimpleDateFormat("HH:mm:ss.SS", Locale.US).format(Date(entry.timestampMs))
            val line = "%s  %08x  %2db  %s\n".format(Locale.US, time, entry.nonce, entry.bits, entry.hashHex)
            val start = out.length; out.append(line)
            val color = when (entry.status) {
                "accepted" -> green
                "threshold", "pending" -> orange
                "block" -> yellow
                else -> red
            }
            out.setSpan(ForegroundColorSpan(color), start, out.length, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
            renderedFeedStatuses[key] = entry.status
        }
        while (out.count { it == '\n' } > 200) {
            val firstBreak = out.indexOf("\n"); if (firstBreak < 0) break
            out.delete(0, firstBreak + 1); renderedFeedStatuses.keys.firstOrNull()?.let { renderedFeedStatuses.remove(it) }
        }
        hashFeedView.text = out
        feedScroll.post { feedScroll.fullScroll(View.FOCUS_DOWN) }
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
        pendingExportText = telemetry + feed
        val create = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "mining-btc-telemetry.csv")
        }
        startActivityForResult(create, exportCsvRequestCode)
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
