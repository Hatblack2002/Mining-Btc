package com.vertil.miningbtc

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Foreground Service that hosts the Stratum miner and keeps it alive 24/7.
 *
 * - Holds a PARTIAL_WAKE_LOCK so the CPU keeps hashing when the screen is off.
 * - Posts a persistent low-importance notification so Android treats the
 *   process as foreground and does not kill it under Doze.
 * - Restarts with START_STICKY if the system reclaims memory.
 * - Exposes a Binder so the Activity can register/unregister a stats callback.
 *
 * The Activity does NOT own the StratumMiner anymore. It writes pool config
 * to SharedPreferences, then sends ACTION_START. The service reads config
 * from prefs and spawns the miner.
 */
class MiningService : Service() {
    companion object {
        const val CHANNEL_ID = "mining_btc_channel"
        const val NOTIFICATION_ID = 1
        const val BLOCK_CHANNEL_ID = "mining_btc_block_found"
        const val BLOCK_NOTIFICATION_ID = 2
        const val ACTION_START = "com.vertil.miningbtc.START"
        const val ACTION_STOP = "com.vertil.miningbtc.STOP"
        private const val PREFS_NAME = "pool_config"
    }

    private val binder = LocalBinder()
    private val thermalExecutor = Executors.newSingleThreadExecutor()
    private val environmentRunning = AtomicBoolean(false)
    @Volatile private var batteryTempC = 0.0
    @Volatile private var batteryChargeStartUah = -1L
    @Volatile private var batteryChargeCurrentUah = -1L
    @Volatile private var officialThermalStatus = -1
    @Volatile private var lastTelemetryLogMs = 0L
    @Volatile private var lastStatsPersistMs = 0L
    @Volatile private var lastNotificationMs = 0L
    @Volatile private var lastPersistedBestHash = ""
    private val telemetryFile by lazy { File(filesDir, "mining-telemetry-v3.csv") }
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        officialThermalStatus = status
        miner?.setSystemThermalStatus(status)
    }
    private var miner: StratumMiner? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val listeners = CopyOnWriteArrayList<(MinerStats) -> Unit>()
    @Volatile private var lastStats: MinerStats? = null
    @Volatile private var running = false

    inner class LocalBinder : Binder() {
        fun service(): MiningService = this@MiningService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        createBlockNotificationChannel()
        registerThermalStatusListener()
        startEnvironmentMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMining()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // ACTION_START or null (system restarted us) -> ensure foreground + mining
        startForeground(NOTIFICATION_ID, buildNotification("Iniciando minería…", 0.0, 0.0, 0))
        if (!running) startMiningFromPrefs()
        return START_STICKY
    }

    private fun startMiningFromPrefs() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val host = prefs.getString("host", "") ?: ""
        val portStr = prefs.getString("port", "3333") ?: "3333"
        val port = portStr.toIntOrNull() ?: 3333
        val user = prefs.getString("user", "") ?: ""
        val pass = prefs.getString("pass", "x") ?: "x"
        val tls = prefs.getBoolean("tls", false)

        if (host.isBlank() || user.isBlank() || port !in 1..65535) {
            updateNotification("Configuración incompleta. Abre la app y configura el pool.", 0.0, 0.0, 0)
            return
        }

        acquireWakeLock()
        batteryChargeStartUah = -1L
        batteryChargeCurrentUah = -1L
        lastTelemetryLogMs = 0L
        running = true
        miner = StratumMiner(
            PoolConfig(host, port, user, pass, tls),
            onLoadStats(prefs),
            onBlockFound = { record -> handleBlockFound(record) }
        ) { stats ->
            onStats(stats, prefs)
        }
        miner?.setSystemThermalStatus(officialThermalStatus)
        miner?.start()
    }

    private fun stopMining() {
        running = false
        miner?.stop()
        miner = null
        releaseWakeLock()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { wl ->
            try { if (wl.isHeld) wl.release() } catch (_: Exception) {}
        }
        wakeLock = null
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MiningBTC::MiningWakelock").apply {
            setReferenceCounted(false)
            try { acquire() } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        environmentRunning.set(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try { (getSystemService(POWER_SERVICE) as PowerManager).removeThermalStatusListener(thermalListener) } catch (_: Exception) {}
        }
        thermalExecutor.shutdownNow()
        stopMining()
        wakeLock?.let { wl ->
            try { if (wl.isHeld) wl.release() } catch (_: Exception) {}
        }
        wakeLock = null
        super.onDestroy()
    }

    fun registerListener(cb: (MinerStats) -> Unit) {
        listeners.add(cb)
        lastStats?.let { cb(it) }
    }

    fun unregisterListener(cb: (MinerStats) -> Unit) {
        listeners.remove(cb)
    }

    private fun onStats(stats: MinerStats, prefs: SharedPreferences) {
        val enriched = enrichStats(stats)
        lastStats = enriched
        for (l in listeners) {
            try { l(enriched) } catch (_: Exception) {}
        }
        val now = System.currentTimeMillis()
        if (now - lastNotificationMs >= 1000L) {
            updateNotification(enriched.message, enriched.hashrate, enriched.bestShareDifficulty, enriched.bestShareBits)
            lastNotificationMs = now
        }
        appendTelemetryIfDue(enriched)

        // Avoid SharedPreferences I/O on every 30 Hz feed update.
        if (now - lastStatsPersistMs >= 10_000L || enriched.bestShareHashHex != lastPersistedBestHash) {
            prefs.edit()
                .putLong("totalHashes", enriched.totalHashes)
                .putLong("totalAccepted", enriched.accepted)
                .putLong("totalRejected", enriched.rejected)
                .putLong("totalStale", enriched.staleShares)
                .putString("bestShareHashHex", enriched.bestShareHashHex)
                .putString("bestShareDifficulty", enriched.bestShareDifficulty.toString())
                .putInt("bestShareBits", enriched.bestShareBits)
                .putLong("bestShareTimestampMs", enriched.bestShareTimestampMs)
                .putLong("bestShareNonce", enriched.bestShareNonce)
                .apply()
            lastStatsPersistMs = now
            lastPersistedBestHash = enriched.bestShareHashHex
        }
    }

    private fun onLoadStats(prefs: SharedPreferences): MinerStats {
        val totalHashes = prefs.getLong("totalHashes", 0L)
        val totalAccepted = prefs.getLong("totalAccepted", 0L)
        val totalRejected = prefs.getLong("totalRejected", 0L)
        val totalStale = prefs.getLong("totalStale", 0L)
        val bestDiffStr = prefs.getString("bestShareDifficulty", "0.0") ?: "0.0"
        val bestDiff = bestDiffStr.toDoubleOrNull() ?: 0.0
        val bestBits = prefs.getInt("bestShareBits", 0)
        val bestHashHex = prefs.getString("bestShareHashHex", "") ?: ""
        val bestTimestamp = prefs.getLong("bestShareTimestampMs", 0L)
        val bestNonce = prefs.getLong("bestShareNonce", -1L)
        return MinerStats(
            state = "Idle",
            totalHashes = totalHashes,
            accepted = totalAccepted,
            rejected = totalRejected,
            staleShares = totalStale,
            bestShareDifficulty = bestDiff,
            bestShareBits = bestBits,
            bestShareHashHex = bestHashHex,
            bestShareTimestampMs = bestTimestamp,
            bestShareNonce = bestNonce
        )
    }

    fun exportStatsJson(): String {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return org.json.JSONObject()
            .put("version", 1)
            .put("totalHashes", prefs.getLong("totalHashes", 0L))
            .put("totalAccepted", prefs.getLong("totalAccepted", 0L))
            .put("totalRejected", prefs.getLong("totalRejected", 0L))
            .put("totalStale", prefs.getLong("totalStale", 0L))
            .put("bestShareHashHex", prefs.getString("bestShareHashHex", "") ?: "")
            .put("bestShareDifficulty", prefs.getString("bestShareDifficulty", "0.0") ?: "0.0")
            .put("bestShareBits", prefs.getInt("bestShareBits", 0))
            .put("bestShareTimestampMs", prefs.getLong("bestShareTimestampMs", 0L))
            .put("bestShareNonce", prefs.getLong("bestShareNonce", -1L))
            .toString(2)
    }

    fun importStatsJson(json: String) {
        val obj = org.json.JSONObject(json)
        require(obj.optInt("version", -1) == 1) { "Formato de estadísticas no compatible" }
        val wasRunning = running
        if (wasRunning) stopMining()
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit()
            .putLong("totalHashes", obj.optLong("totalHashes", 0L).coerceAtLeast(0L))
            .putLong("totalAccepted", obj.optLong("totalAccepted", 0L).coerceAtLeast(0L))
            .putLong("totalRejected", obj.optLong("totalRejected", 0L).coerceAtLeast(0L))
            .putLong("totalStale", obj.optLong("totalStale", 0L).coerceAtLeast(0L))
            .putString("bestShareHashHex", obj.optString("bestShareHashHex", ""))
            .putString("bestShareDifficulty", obj.optString("bestShareDifficulty", "0.0"))
            .putInt("bestShareBits", obj.optInt("bestShareBits", 0).coerceIn(0, 256))
            .putLong("bestShareTimestampMs", obj.optLong("bestShareTimestampMs", 0L).coerceAtLeast(0L))
            .putLong("bestShareNonce", obj.optLong("bestShareNonce", -1L))
            .apply()
        NativePowEngine.nativeResetBestHash()
        lastPersistedBestHash = ""
        lastStatsPersistMs = 0L
        lastStats = onLoadStats(prefs)
        lastStats?.let { value -> listeners.forEach { listener -> try { listener(value) } catch (_: Exception) {} } }
        if (wasRunning) startMiningFromPrefs()
    }

    fun resetStatistics() {
        val wasRunning = running
        if (wasRunning) stopMining()
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .remove("totalHashes").remove("totalAccepted").remove("totalRejected").remove("totalStale")
            .remove("bestShareHashHex").remove("bestShareDifficulty").remove("bestShareBits")
            .remove("bestShareTimestampMs").remove("bestShareNonce").apply()
        NativePowEngine.nativeResetBestHash()
        lastPersistedBestHash = ""
        lastStatsPersistMs = 0L
        lastStats = MinerStats("Idle")
        listeners.forEach { listener -> try { listener(lastStats!!) } catch (_: Exception) {} }
        if (wasRunning) startMiningFromPrefs()
    }

    fun simulateBlockAlert() {
        // A deliberately easy synthetic target exercises the same target predicate
        // used for real mining.notify nBits, without submitting a fake share to the pool.
        val fakeDigest = ByteArray(32)
        val fakeTarget = ByteArray(32) { 0xff.toByte() }
        if (!BitcoinTarget.meetsTarget(fakeDigest, fakeTarget)) return
        handleBlockFound(BlockFoundRecord(
            header80 = ByteArray(80),
            hashHex = "0000000000000000000000000000000000000000000000000000000000000000",
            nonce = 0L,
            timestampMs = System.currentTimeMillis(),
            compactBits = "FAKE-EASY-TARGET",
            networkDifficulty = 0.0,
            simulated = true
        ))
    }

    private fun handleBlockFound(record: BlockFoundRecord) {
        try {
            val file = File(filesDir, "found-blocks.jsonl")
            val headerHex = record.header80.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val line = org.json.JSONObject()
                .put("timestamp_ms", record.timestampMs)
                .put("hash", record.hashHex)
                .put("nonce", record.nonce)
                .put("compact_bits", record.compactBits)
                .put("network_difficulty", record.networkDifficulty)
                .put("simulated", record.simulated)
                .put("header80_hex", headerHex)
            synchronized(file) { file.appendText(line.toString() + "\n") }
            val activity = Intent(this, BlockFoundActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("hash", record.hashHex)
                putExtra("nonce", record.nonce)
                putExtra("timestamp_ms", record.timestampMs)
                putExtra("header80_hex", headerHex)
                putExtra("simulated", record.simulated)
                putExtra("compact_bits", record.compactBits)
            }
            val pi = PendingIntent.getActivity(this, 2, activity,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, BLOCK_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mining_btc)
                .setContentTitle(if (record.simulated) "SIMULACIÓN: BLOQUE ENCONTRADO" else "BLOQUE ENCONTRADO")
                .setContentText("Hash ${record.hashHex} · nonce ${record.nonce}")
                .setCategory(Notification.CATEGORY_EVENT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setVibrate(longArrayOf(0, 700, 250, 700, 250, 1200))
                .setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION))
                .build()
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(BLOCK_NOTIFICATION_ID, notification)
        } catch (_: Exception) {}
    }

    private fun createBlockNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                BLOCK_CHANNEL_ID, "Bitcoin block found", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical alert when a hash meets the Bitcoin network target"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 700, 250, 700, 250, 1200)
                setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION), null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun registerThermalStatusListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                pm.addThermalStatusListener(thermalExecutor, thermalListener)
                officialThermalStatus = pm.currentThermalStatus
                miner?.setSystemThermalStatus(officialThermalStatus)
            } catch (_: Exception) { officialThermalStatus = -1 }
        }
    }

    private fun startEnvironmentMonitor() {
        if (!environmentRunning.compareAndSet(false, true)) return
        Thread({
            while (environmentRunning.get()) {
                try {
                    val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val raw = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
                    batteryTempC = if (raw >= 0) raw / 10.0 else 0.0
                    val batteryManager = getSystemService(BATTERY_SERVICE) as BatteryManager
                    val chargeUah = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                    if (chargeUah >= 0L) {
                        batteryChargeCurrentUah = chargeUah
                        if (running && batteryChargeStartUah < 0L) batteryChargeStartUah = chargeUah
                    }
                    val current = lastStats ?: MinerStats("Idle")
                    val updated = enrichStats(current)
                    lastStats = updated
                    listeners.forEach { listener -> try { listener(updated) } catch (_: Exception) {} }
                    appendTelemetryIfDue(updated)
                    Thread.sleep(2500)
                } catch (_: InterruptedException) { break }
                catch (_: Exception) { try { Thread.sleep(2500) } catch (_: InterruptedException) { break } }
            }
        }, "mining-environment-monitor").apply { isDaemon = true; start() }
    }

    private fun enrichStats(stats: MinerStats): MinerStats =
        stats.copy(batteryTempC = batteryTempC, thermalStatus = officialThermalStatus)

    private fun appendTelemetryIfDue(stats: MinerStats) {
        val now = System.currentTimeMillis()
        if (now - lastTelemetryLogMs < 10_000L) return
        synchronized(telemetryFile) {
            if (!telemetryFile.exists()) telemetryFile.writeText("timestamp_ms,cpu_temp_c,cpu_sensor,battery_temp_c,battery_charge_counter_uah,battery_net_consumed_mah,power_thermal_status,hashrate_hs,active_native_threads,affinity_workers,allowed_cpus,cpu_frequencies_khz,app_state\n")
            val sensor = stats.cpuSensorName.replace(",", "_").replace("\n", " ")
            val processState = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(processState)
            val appState = if (processState.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) "foreground" else "background"
            val cpuFreqs = readCpuFrequenciesKHz()
            val chargeUah = batteryChargeCurrentUah
            val consumedMah = if (batteryChargeStartUah >= 0L && chargeUah >= 0L)
                (batteryChargeStartUah - chargeUah) / 1000.0 else -1.0
            telemetryFile.appendText("$now,${stats.cpuTempC},$sensor,${stats.batteryTempC},$chargeUah,$consumedMah,${stats.thermalStatus},${stats.hashrate},${stats.effectiveThreads},${stats.nativeAffinityThreads},${stats.nativeAllowedCpus},$cpuFreqs,$appState\n")
            lastTelemetryLogMs = now
        }
    }

    private fun readCpuFrequenciesKHz(): String {
        val root = File("/sys/devices/system/cpu")
        return root.listFiles()?.filter { it.name.matches(Regex("cpu[0-9]+")) }
            ?.sortedBy { it.name.removePrefix("cpu").toIntOrNull() ?: Int.MAX_VALUE }
            ?.joinToString(";") { cpu ->
                val value = try { File(cpu, "cpufreq/scaling_cur_freq").readText().trim().toLongOrNull() }
                    catch (_: Exception) { null }
                cpu.name + ":" + (value ?: -1)
            }.orEmpty()
    }

    fun setHashFeedRate(rate: Int) { miner?.setHashFeedRate(rate) }

    fun telemetryCsvText(): String = synchronized(telemetryFile) {
        if (telemetryFile.exists()) telemetryFile.readText() else "timestamp_ms,cpu_temp_c,cpu_sensor,battery_temp_c,battery_charge_counter_uah,battery_net_consumed_mah,power_thermal_status,hashrate_hs,active_native_threads,affinity_workers,allowed_cpus,cpu_frequencies_khz,app_state\n"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mining BTC",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Servicio de minería en ejecución"
                setShowBadge(false)
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(text: String, hashrate: Double, bestDiff: Double, bestBits: Int) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text, hashrate, bestDiff, bestBits))
    }

    private fun buildNotification(text: String, hashrate: Double, bestDiff: Double, bestBits: Int): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = Intent(this, MiningService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this, 10, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val rateStr = if (hashrate > 0) " · " + formatRate(hashrate) else ""
        val bestStr = if (bestBits > 0) " · best ${bestBits}b" else ""
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Mining BTC$rateStr$bestStr")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mining_btc)
            .setOngoing(true)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_media_pause, "Detener", stopPi)
            .build()
    }

    private fun formatRate(v: Double): String = when {
        v >= 1_000_000 -> String.format(Locale.US, "%.2f MH/s", v / 1_000_000)
        v >= 1_000 -> String.format(Locale.US, "%.2f kH/s", v / 1_000)
        else -> String.format(Locale.US, "%.2f H/s", v)
    }
}
