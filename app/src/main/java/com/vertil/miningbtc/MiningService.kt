package com.vertil.miningbtc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.util.Locale
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
        const val ACTION_START = "com.vertil.miningbtc.START"
        const val ACTION_STOP = "com.vertil.miningbtc.STOP"
        private const val PREFS_NAME = "pool_config"
    }

    private val binder = LocalBinder()
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
        acquireWakeLock()
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

        running = true
        miner = StratumMiner(
            PoolConfig(host, port, user, pass, tls),
            onLoadStats(prefs)
        ) { stats ->
            onStats(stats, prefs)
        }
        miner?.start()
    }

    private fun stopMining() {
        running = false
        miner?.stop()
        miner = null
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
        lastStats = stats
        for (l in listeners) {
            try { l(stats) } catch (_: Exception) {}
        }
        updateNotification(stats.message, stats.hashrate, stats.bestShareDifficulty, stats.bestShareBits)

        // Persist totals across reboots. Best share and counters survive app kills.
        prefs.edit()
            .putLong("totalHashes", stats.totalHashes)
            .putLong("totalAccepted", stats.accepted)
            .putLong("totalRejected", stats.rejected)
            .putString("bestShareHashHex", stats.bestShareHashHex)
            .putString("bestShareDifficulty", stats.bestShareDifficulty.toString())
            .putInt("bestShareBits", stats.bestShareBits)
            .apply()
    }

    private fun onLoadStats(prefs: SharedPreferences): MinerStats {
        val totalHashes = prefs.getLong("totalHashes", 0L)
        val totalAccepted = prefs.getLong("totalAccepted", 0L)
        val totalRejected = prefs.getLong("totalRejected", 0L)
        val bestDiffStr = prefs.getString("bestShareDifficulty", "0.0") ?: "0.0"
        val bestDiff = bestDiffStr.toDoubleOrNull() ?: 0.0
        val bestBits = prefs.getInt("bestShareBits", 0)
        val bestHashHex = prefs.getString("bestShareHashHex", "") ?: ""
        return MinerStats(
            state = "Idle",
            totalHashes = totalHashes,
            accepted = totalAccepted,
            rejected = totalRejected,
            bestShareDifficulty = bestDiff,
            bestShareBits = bestBits,
            bestShareHashHex = bestHashHex
        )
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
        val rateStr = if (hashrate > 0) " · " + formatRate(hashrate) else ""
        val bestStr = if (bestBits > 0) " · best ${bestBits}b" else ""
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Mining BTC$rateStr$bestStr")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mining_btc)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun formatRate(v: Double): String = when {
        v >= 1_000_000 -> String.format(Locale.US, "%.2f MH/s", v / 1_000_000)
        v >= 1_000 -> String.format(Locale.US, "%.2f kH/s", v / 1_000)
        else -> String.format(Locale.US, "%.2f H/s", v)
    }
}
