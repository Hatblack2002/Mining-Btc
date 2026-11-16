package com.vertil.miningbtc

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

internal object StratumProtocol {
    fun validateExtranonce(extranonce1: String, extranonce2Size: Int) {
        require(extranonce1.length % 2 == 0 && extranonce1.all { it in "0123456789abcdefABCDEF" }) {
            "Pool extranonce1 must be valid even-length hexadecimal"
        }
        require(extranonce2Size in 1..16) {
            "Pool extranonce2_size must be between 1 and 16 bytes"
        }
    }

    fun extranonce2Hex(counter: BigInteger, sizeBytes: Int): String {
        require(sizeBytes in 1..16) { "Invalid extranonce2 size" }
        val modulus = BigInteger.ONE.shiftLeft(sizeBytes * 8)
        return counter.mod(modulus).toString(16).padStart(sizeBytes * 2, '0')
    }

    fun merkleRoot(coinbase: ByteArray, branches: List<ByteArray>): ByteArray {
        var root = sha256d(coinbase)
        for (branch in branches) {
            require(branch.size == 32) { "Merkle branch item must be 32 bytes" }
            val pair = ByteArray(64)
            System.arraycopy(root, 0, pair, 0, 32)
            System.arraycopy(branch, 0, pair, 32, 32)
            root = sha256d(pair)
        }
        return root
    }

    private fun sha256d(data: ByteArray): ByteArray {
        val first = java.security.MessageDigest.getInstance("SHA-256").digest(data)
        return java.security.MessageDigest.getInstance("SHA-256").digest(first)
    }
}

data class PoolConfig(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val tls: Boolean,
    val suggestedDifficulty: Double = 1.0
)

data class HashFeedEntry(
    val nonce: Long,
    val hashHex: String,
    val bits: Int,
    val timestampMs: Long,
    val difficulty: Double,
    val status: String = "normal"
)

data class MinerStats(
    val state: String,
    val hashrate: Double = 0.0,
    val totalHashes: Long = 0,
    val accepted: Long = 0,
    val rejected: Long = 0,
    val difficulty: Double = 0.0,
    val message: String = "",
    val latencyMs: Long = -1,
    val rejectionReason: String = "",
    val difficultySuggestion: String = "",
    // Solo mining telemetry: best share difficulty found, leading zero bits of
    // the best share hash, hex of the best share hash, current CPU temperature
    // (degrees C), and effective thread count after thermal backoff. These are
    // the metrics that matter when you mine alone: "how close did I get?".
    val bestShareDifficulty: Double = 0.0,
    val bestShareBits: Int = 0,
    val bestShareHashHex: String = "",
    val bestShareTimestampMs: Long = 0L,
    val bestShareNonce: Long = -1L,
    val hashFeed: List<HashFeedEntry> = emptyList(),
    val topHashes: List<HashFeedEntry> = emptyList(),
    val cpuSensorName: String = "unavailable",
    val batteryTempC: Double = 0.0,
    val thermalStatus: Int = -1,
    val networkBitsHex: String = "",
    val networkDifficulty: Double = 0.0,
    val bitsMissing: Int = 0,
    val cpuTempC: Double = 0.0,
    val effectiveThreads: Int = 0,
    val nativeAffinityThreads: Int = 0,
    val nativeAllowedCpus: Int = 0,
    val staleShares: Long = 0,
    val lastSubmitLatencyMs: Long = -1
)

/**
 * Stratum v1 solo miner with thermal backoff and best-share tracking.
 *
 * The miner keeps the socket alive across thermal pauses: when Android
 * reports PowerManager SEVERE or higher, nonce scanning pauses while the
 * reader thread remains active to process pool messages and responses.
 *
 * Best-share telemetry is computed for every share we submit. The hash
 * is read in little-endian uint256 order (Bitcoin consensus) to derive
 * the difficulty that share would satisfy if it had been a full block.
 */
class StratumMiner(
    private val config: PoolConfig,
    private val initialStats: MinerStats = MinerStats("Idle"),
    private val onBlockFound: (BlockFoundRecord) -> Unit = {},
    private val onStats: (MinerStats) -> Unit
) {
    private val running = AtomicBoolean(false)
    private val sessionAuthorized = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val jobRef = AtomicReference<PoolJob?>(null)
    private val difficulty = AtomicReference(1.0)
    private val targetRef = AtomicReference(targetFor(1.0))
    private val networkTargetRef = AtomicReference(ByteArray(32))
    @Volatile private var networkBitsHex = ""
    @Volatile private var networkDifficulty = 0.0
    private val reportedBlockNonces = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private val extranonce1 = AtomicReference("")
    private val extranonce2Size = AtomicReference(4)
    private val hashes = AtomicLong(initialStats.totalHashes)
    private val accepted = AtomicLong(initialStats.accepted)
    private val rejected = AtomicLong(initialStats.rejected)
    private val socketRef = AtomicReference<Socket?>(null)
    private val writerRef = AtomicReference<PrintWriter?>(null)
    private val requestId = AtomicLong(2)
    private val responses = java.util.concurrent.ConcurrentHashMap<Long, CountDownLatch>()
    private val responseValues = java.util.concurrent.ConcurrentHashMap<Long, JSONObject>()
    private data class PendingSubmit(
        val nonce: Long,
        val jobId: String,
        val sentAtNanos: Long,
        val obsolete: AtomicBoolean = AtomicBoolean(false)
    )
    private val submitIds = java.util.concurrent.ConcurrentHashMap<Long, PendingSubmit>()
    private val latencyMs = AtomicLong(-1)
    private val lastSubmitLatencyMs = AtomicLong(-1)
    private val staleShares = AtomicLong(initialStats.staleShares)
    private val suggestDifficultyRequestId = AtomicLong(-1)
    private val maxThreads = 8
    private val effectiveThreads = AtomicInteger(maxThreads)
    private val feedRateHz = AtomicInteger(4)
    private val feedLock = Any()
    private val pendingFeed = java.util.ArrayDeque<HashFeedEntry>()
    private val liveFeed = java.util.ArrayDeque<HashFeedEntry>()
    private val topFeed = ArrayList<HashFeedEntry>()
    @Volatile private var lastFeedEmitNanos = 0L
    @Volatile private var lastReportedRate = 0.0
    private val cpuTempC = AtomicReference(0.0)
    private val cpuSensorName = AtomicReference("unavailable")
    @Volatile private var officialThermalStatus = -1
    private val severeThermalLatched = AtomicBoolean(false)
    @Volatile private var thermalCoolSinceMs = 0L
    @Volatile private var bestShareDifficulty = initialStats.bestShareDifficulty
    @Volatile private var bestShareBits = initialStats.bestShareBits
    @Volatile private var bestShareHashHex = initialStats.bestShareHashHex
    @Volatile private var bestShareTimestampMs = initialStats.bestShareTimestampMs
    @Volatile private var bestShareNonce = initialStats.bestShareNonce
    @Volatile private var rejectionReason = ""
    @Volatile private var lastMessage = "Connecting"
    @Volatile private var difficultySuggestionStatus = "Not sent"

    private data class PoolJob(
        val id: String, val prevhash: String, val coinb1: String, val coinb2: String,
        val branches: List<String>, val version: String, val nbits: String,
        val ntime: String, val clean: Boolean
    )

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread(name = "thermal-monitor", isDaemon = true) { runThermalMonitor() }
        thread(name = "best-hash-poller", isDaemon = true) {
            while (running.get()) {
                try {
                    if (updateBestFromSnapshot(NativePowEngine.nativePollBestHash())) {
                        emit("Mining", lastReportedRate)
                    }
                } catch (_: Exception) {}
                try { Thread.sleep(500) } catch (_: InterruptedException) { break }
            }
        }
        thread(name = "stratum-connection") { runSession() }
    }

    fun setHashFeedRate(rate: Int) { feedRateHz.set(rate.coerceIn(2, 5)) }

    fun setSystemThermalStatus(status: Int) {
        officialThermalStatus = status
        if (status >= PowerThermalStatus.SEVERE) {
            severeThermalLatched.set(true)
            thermalCoolSinceMs = 0L
        }
    }

    fun stop() {
        running.set(false)
        try { socketRef.getAndSet(null)?.close() } catch (_: Exception) {}
        lastMessage = "Stopped"
        emit("Stopped")
    }

    /**
     * CPU temperature is telemetry only. Worker count is fixed at eight.
     * Only official PowerManager SEVERE-or-higher pauses hashing; resume
     * requires the official status to remain below SEVERE for 30 seconds.
     */
    private fun runThermalMonitor() {
        val zones = discoverThermalZones()
        while (running.get()) {
            val reading = readHottestTemp(zones)
            val temp = reading.first
            cpuTempC.set(temp)
            cpuSensorName.set(reading.second)
            val nowMs = System.currentTimeMillis()
            if (severeThermalLatched.get()) {
                if (officialThermalStatus in PowerThermalStatus.NONE..PowerThermalStatus.MODERATE) {
                    if (thermalCoolSinceMs == 0L) thermalCoolSinceMs = nowMs
                    if (nowMs - thermalCoolSinceMs >= 30_000L) severeThermalLatched.set(false)
                } else {
                    thermalCoolSinceMs = 0L
                }
            }
            if (severeThermalLatched.get()) {
                effectiveThreads.set(0)
                paused.set(true)
                lastMessage = "PAUSA TÉRMICA · PowerManager SEVERE+ · esperando 30 s por debajo de SEVERE"
            } else {
                // MODERATE and sensor temperature are telemetry only: keep all 8 workers.
                effectiveThreads.set(maxThreads)
                paused.set(false)
            }
            try { Thread.sleep(2500) } catch (_: InterruptedException) { break }
        }
    }

    private fun discoverThermalZones(): List<File> {
        val base = File("/sys/class/thermal")
        val zones = base.listFiles()?.toList() ?: return emptyList()
        return zones.filter { it.name.startsWith("thermal_zone") }
            .map { File(it, "temp") }
            .filter { it.exists() && it.canRead() }
    }

    private fun readHottestTemp(zones: List<File>): Pair<Double, String> {
        var hottest = 0.0
        var hottestName = "no readable thermal_zone sensor"
        for (zone in zones) {
            try {
                val raw = zone.readText().trim()
                val value = raw.toInt()
                val deg = if (value > 1000) value / 1000.0 else value / 10.0
                if (deg in 0.0..120.0 && deg > hottest) {
                    hottest = deg
                    val typeFile = File(zone.parentFile, "type")
                    val type = try { typeFile.readText().trim() } catch (_: Exception) { zone.parentFile?.name ?: "thermal_zone" }
                    hottestName = "${zone.parentFile?.name ?: "thermal_zone"}:$type"
                }
            } catch (_: Exception) {}
        }
        return hottest to hottestName
    }

    private fun runSession() {
        var attempt = 0
        while (running.get()) {
            sessionAuthorized.set(false)
            runSingleSession()
            if (!running.get()) break
            if (sessionAuthorized.get()) attempt = 0
            val delaySeconds = (1L shl attempt.coerceAtMost(5)).coerceAtMost(30L)
            lastMessage = "Reconnecting in " + delaySeconds + "s"
            emit("Reconnecting")
            try { Thread.sleep(delaySeconds * 1000L) } catch (_: InterruptedException) { break }
            attempt = (attempt + 1).coerceAtMost(5)
        }
    }

    private fun runSingleSession() {
        try {
            require(config.host.isNotBlank()) { "Pool host is required" }
            val addresses = InetAddress.getAllByName(config.host).sortedBy { if (it is java.net.Inet4Address) 0 else 1 }
            var connected: Socket? = null
            var connectFailure: Exception? = null
            for (address in addresses) {
                val candidate = Socket()
                try {
                    candidate.connect(InetSocketAddress(address, config.port), 15000)
                    connected = candidate
                    break
                } catch (e: Exception) {
                    connectFailure = e
                    try { candidate.close() } catch (_: Exception) {}
                }
            }
            val plain = connected ?: throw IllegalStateException(
                "Unable to connect to ${config.host}:${config.port} over IPv4 or IPv6: " +
                    (connectFailure?.message ?: "no resolved addresses"),
                connectFailure
            )
            plain.keepAlive = true
            plain.soTimeout = 45_000
            val socket = if (config.tls) {
                val sslSocket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(plain, config.host, config.port, true) as javax.net.ssl.SSLSocket
                sslSocket.startHandshake()
                sslSocket
            } else plain
            socket.keepAlive = true
            socket.soTimeout = 45_000
            socketRef.set(socket)
            jobRef.set(null)
            val writer = PrintWriter(socket.getOutputStream(), true)
            writerRef.set(writer)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            thread(name = "stratum-reader", isDaemon = true) {
                try {
                    while (running.get()) {
                        val line = try {
                            reader.readLine()
                        } catch (_: SocketTimeoutException) {
                            // A quiet pool is not a dead pool. TCP keepalive and actual
                            // socket closure/write failures govern session liveness.
                            continue
                        } ?: break
                        handleLine(line)
                    }
                } catch (_: Exception) {}
                if (running.get()) {
                    lastMessage = "Pool connection lost"
                    try { socketRef.get()?.close() } catch (_: Exception) {}
                    emit("Disconnected")
                }
            }

            val subscribeResponse = registerResponse(1)
            send(JSONObject().put("id", 1).put("method", "mining.subscribe")
                .put("params", JSONArray().put("VertilMiningBTC/0.2")))
            val sub = awaitResponse(1, subscribeResponse, 10)
            if (sub.has("error") && sub.opt("error") != JSONObject.NULL) {
                throw IllegalStateException("Subscribe rejected: ${sub.opt("error")}")
            }
            val result = sub.optJSONArray("result") ?: error("Invalid subscribe response")
            if (result.length() < 3) error("Pool did not provide extranonce data")
            val subscribedExtranonce1 = result.getString(1)
            val subscribedExtranonce2Size = result.getInt(2)
            StratumProtocol.validateExtranonce(subscribedExtranonce1, subscribedExtranonce2Size)
            extranonce1.set(subscribedExtranonce1)
            extranonce2Size.set(subscribedExtranonce2Size)

            val authorizeResponse = registerResponse(2)
            send(JSONObject().put("id", 2).put("method", "mining.authorize")
                .put("params", JSONArray().put(config.username).put(config.password)))
            val auth = awaitResponse(2, authorizeResponse, 10)
            if (auth.optBoolean("result").not()) {
                throw IllegalStateException("Pool authorization failed: ${auth.opt("error")}")
            }
            sessionAuthorized.set(true)

            val suggestId = requestId.incrementAndGet()
            difficultySuggestionStatus = "Sent mining.suggest_difficulty(${config.suggestedDifficulty})"
            suggestDifficultyRequestId.set(suggestId)
            send(JSONObject().put("id", suggestId).put("method", "mining.suggest_difficulty")
                .put("params", JSONArray().put(config.suggestedDifficulty.coerceAtLeast(0.00000001))))
            thread(name = "stratum-difficulty-timeout", isDaemon = true) {
                try { Thread.sleep(5000) } catch (_: InterruptedException) {}
                if (suggestDifficultyRequestId.compareAndSet(suggestId, -1)) {
                    difficultySuggestionStatus = "Pool did not answer mining.suggest_difficulty; unsupported or ignored"
                    emit("Mining")
                }
            }
            lastMessage = "Authorized; waiting for pool work"
            emit("Waiting for work")
            var nonce = 0L
            var extraCounter = BigInteger.ZERO
            var activeJob: PoolJob? = null
            var headerPrefix: ByteArray? = null
            targetRef.set(targetFor(difficulty.get()))
            var sessionHashes = 0L
            var lastRateAt = System.nanoTime()
            var lastRateHashes = 0L
            val useArm = NativePowEngine.nativeCapabilities().contains("arm64_sha2=true")

            while (running.get() && socketRef.get() === socket && !socket.isClosed) {
                val loopNow = System.nanoTime()
                expirePendingSubmits(loopNow)
                // Thermal pause suspends hashing but keeps Stratum keepalive and
                // submit timeouts active so the pool session does not go stale.
                if (paused.get()) {
                    val temp = cpuTempC.get()
                    lastMessage = "PAUSA TÉRMICA · PowerManager SEVERE+ · %.1f°C · reanudación tras 30 s por debajo de SEVERE".format(Locale.US, temp)
                    emit("Throttled")
                    try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
                    continue
                }
                val job = jobRef.get()
                if (job == null) {
                    Thread.sleep(100)
                    continue
                }
                if (job !== activeJob) {
                    activeJob = job
                    nonce = 0
                    val extra = StratumProtocol.extranonce2Hex(extraCounter, extranonce2Size.get())
                    val coinbase = hex(job.coinb1) + hex(extranonce1.get()) + hex(extra) + hex(job.coinb2)
                    val merkle = StratumProtocol.merkleRoot(coinbase, job.branches.map { hex(it) })
                    headerPrefix = le(job.version, 4) + swapWords(hex(job.prevhash)) + merkle +
                        le(job.ntime, 4) + le(job.nbits, 4)
                    targetRef.set(targetFor(difficulty.get()))
                    val threads = effectiveThreads.get()
                    lastMessage = "Native SHA-256d ${if (useArm) "ARMv8 SHA2" else "portable"} · $threads hilos · job ${job.id}"
                    emit("Mining")
                }
                val prefix = headerPrefix ?: continue
                val header = ByteArray(80)
                System.arraycopy(prefix, 0, header, 0, 76)
                // The first 64 header bytes are invariant for this job/extranonce2.
                // Prepare once here; the nonce loop hashes only the second SHA block.
                val midstate = NativePowEngine.nativePrepareHeader(header, useArm)
                val currentExtra = StratumProtocol.extranonce2Hex(extraCounter, extranonce2Size.get())
                while (running.get() && jobRef.get() === activeJob && nonce <= 0xffffffffL) {
                    if (paused.get()) break
                    val threads = effectiveThreads.get().coerceAtLeast(1)
                    val remaining = 0x1_0000_0000L - nonce
                    val batchCount = minOf(nativeBatchSize.toLong(), remaining).toInt()
                    var scanTarget = targetRef.get()
                    var scan = NativePowEngine.nativeScanBatch(header, midstate, nonce, batchCount, threads, useArm, scanTarget, networkTargetRef.get())
                    updateBestFromNativeBatch(scan)
                    var actualScanned = scan.getOrElse(0) { 0L }
                    while (targetRef.get() !== scanTarget && jobRef.get() === activeJob) {
                        scanTarget = targetRef.get()
                        scan = NativePowEngine.nativeScanBatch(header, nonce, batchCount, threads, useArm, scanTarget, networkTargetRef.get())
                        updateBestFromNativeBatch(scan)
                        actualScanned += scan.getOrElse(0) { 0L }
                    }
                    val scanned = scan.getOrElse(0) { 0L }
                    if (scanned <= 0L) error("Native SHA-256d scanner returned no progress")
                    hashes.addAndGet(actualScanned)
                    sessionHashes += actualScanned
                    if (jobRef.get() !== activeJob) break
                    nonce += scanned
                    val shareCount = scan.getOrElse(1) { 0L }.toInt().coerceAtLeast(0)
                    // Network-target hits are tracked independently of pool share difficulty.
                    val blockCountIndex = shareCount + 2
                    val blockCount = scan.getOrElse(blockCountIndex) { 0L }.toInt().coerceIn(0, 1024)
                    for (blockIndex in 0 until blockCount) {
                        val blockNonce = scan.getOrElse(blockCountIndex + 1 + blockIndex) { -1L }
                        if (blockNonce < 0L || !reportedBlockNonces.add(blockNonce)) continue
                        val blockHash = NativePowEngine.nativeHashHeader(header, blockNonce, useArm)
                        val blockHeader = header.copyOf()
                        blockHeader[76] = blockNonce.toByte()
                        blockHeader[77] = (blockNonce ushr 8).toByte()
                        blockHeader[78] = (blockNonce ushr 16).toByte()
                        blockHeader[79] = (blockNonce ushr 24).toByte()
                        val record = BlockFoundRecord(blockHeader, toHex(blockHash.reversedArray()),
                            blockNonce, System.currentTimeMillis(), job.nbits, networkDifficulty)
                        val blockId = requestId.incrementAndGet()
                        val blockNonceHex = String.format(Locale.US, "%08x", blockNonce)
                        val blockParams = JSONArray().put(config.username).put(job.id).put(currentExtra).put(job.ntime).put(blockNonceHex)
                        submitIds[blockId] = PendingSubmit(blockNonce, job.id, System.nanoTime())
                        addFeedEntry(blockNonce, blockHash, "block")
                        send(JSONObject().put("id", blockId).put("method", "mining.submit").put("params", blockParams))
                        lastMessage = "BLOCK FOUND · network target met · nonce $blockNonceHex"
                        onBlockFound(record)
                    }
                    for (index in 0 until shareCount) {
                        val shareNonce = scan.getOrElse(index + 2) { -1L }
                        if (shareNonce < 0L || reportedBlockNonces.contains(shareNonce)) continue
                        val currentTarget = targetRef.get()
                        val shareHash = NativePowEngine.nativeHashHeader(header, shareNonce, useArm)
                        if (currentTarget !== scanTarget) {
                            if (!meetsTarget(shareHash, currentTarget)) continue
                        }
                        val blockRecord = if (BitcoinTarget.meetsTarget(shareHash, networkTargetRef.get()) &&
                            reportedBlockNonces.add(shareNonce)) {
                            val blockHeader = header.copyOf()
                            blockHeader[76] = shareNonce.toByte()
                            blockHeader[77] = (shareNonce ushr 8).toByte()
                            blockHeader[78] = (shareNonce ushr 16).toByte()
                            blockHeader[79] = (shareNonce ushr 24).toByte()
                            BlockFoundRecord(blockHeader, toHex(shareHash.reversedArray()), shareNonce,
                                System.currentTimeMillis(), job.nbits, networkDifficulty)
                        } else null
                        val id = requestId.incrementAndGet()
                        val nonceHex = String.format(Locale.US, "%08x", shareNonce)
                        val params = JSONArray().put(config.username).put(job.id).put(currentExtra).put(job.ntime).put(nonceHex)
                        submitIds[id] = PendingSubmit(shareNonce, job.id, System.nanoTime())
                        addFeedEntry(shareNonce, shareHash, if (blockRecord != null) "block" else "pending")
                        // Submit the network-target share immediately, before alert I/O.
                        send(JSONObject().put("id", id).put("method", "mining.submit").put("params", params))
                        if (blockRecord != null) {
                            lastMessage = "BLOCK FOUND · network target met · nonce $nonceHex"
                            onBlockFound(blockRecord)
                        }
                    }
                    val now = System.nanoTime()
                    if (now - lastRateAt >= 1_000_000_000L) {
                        val delta = sessionHashes - lastRateHashes
                        val rate = delta * 1_000_000_000.0 / (now - lastRateAt)
                        lastRateAt = now
                        lastRateHashes = sessionHashes
                        emit("Mining", rate)
                    }
                }
                if (nonce > 0xffffffffL) {
                    extraCounter = extraCounter.add(BigInteger.ONE)
                    activeJob = null // rebuild coinbase, Merkle root and header for the next extranonce2
                }
            }
        } catch (e: Exception) {
            lastMessage = when (e) {
                is java.net.UnknownHostException -> "DNS resolution failed for pool host: ${config.host}"
                is java.net.SocketTimeoutException -> "Connection timed out: ${config.host}:${config.port}"
                else -> e.message ?: "Stratum session failed: ${e.javaClass.simpleName}"
            }
            if (running.get()) emit("Error")
        } finally {
            try { socketRef.getAndSet(null)?.close() } catch (_: Exception) {}
            submitIds.values.forEach { it.obsolete.set(true) }
            writerRef.set(null)
        }
    }

    private fun handleLine(line: String) {
        try {
            val obj = JSONObject(line)
            if (obj.has("method")) {
                when (obj.optString("method")) {
                    "mining.notify" -> {
                        val p = obj.getJSONArray("params")
                        val branchesJson = p.getJSONArray(4)
                        val branches = (0 until branchesJson.length()).map { branchesJson.getString(it) }
                        val newJob = PoolJob(p.getString(0), p.getString(1), p.getString(2), p.getString(3),
                            branches, p.getString(5), p.getString(6), p.getString(7), p.optBoolean(8))
                        val networkTarget = BitcoinTarget.fromCompact(newJob.nbits)
                        networkTargetRef.set(networkTarget)
                        networkBitsHex = newJob.nbits.lowercase(Locale.US)
                        networkDifficulty = BitcoinTarget.difficulty(networkTarget)
                        reportedBlockNonces.clear()
                        if (newJob.clean) {
                            submitIds.values.filter { it.jobId != newJob.id }.forEach { it.obsolete.set(true) }
                            lastMessage = "clean_jobs=true · previous nonce scan cancelled"
                        }
                        jobRef.set(newJob)
                        if (p.optBoolean(8)) lastMessage = "New clean job received"
                    }
                    "mining.set_difficulty" -> {
                        val p = obj.getJSONArray("params")
                        val nextDifficulty = p.getDouble(0).coerceAtLeast(0.00000001)
                        difficulty.set(nextDifficulty)
                        targetRef.set(targetFor(nextDifficulty))
                    }
                    "mining.set_extranonce" -> {
                        val p = obj.getJSONArray("params")
                        require(p.length() >= 2) { "mining.set_extranonce requires extranonce1 and extranonce2_size" }
                        val nextExtranonce1 = p.getString(0)
                        val nextExtranonce2Size = p.getInt(1)
                        StratumProtocol.validateExtranonce(nextExtranonce1, nextExtranonce2Size)
                        extranonce1.set(nextExtranonce1)
                        extranonce2Size.set(nextExtranonce2Size)
                        // Force the active job to rebuild its coinbase, Merkle root and header.
                        jobRef.get()?.let { current -> jobRef.set(current.copy()) }
                        lastMessage = "Pool changed extranonce parameters; rebuilding current job"
                        emit("Mining")
                    }
                    "mining.ping" -> {
                        // Some pools send a request-style ping; Stratum V1 does not require
                        // clients to originate this extension. Reply only when the pool asks.
                        val pingId = obj.opt("id")
                        if (pingId != null && pingId != JSONObject.NULL) {
                            send(JSONObject().put("id", pingId).put("result", true).put("error", JSONObject.NULL))
                        }
                    }
                    "client.reconnect" -> {
                        lastMessage = "Pool requested reconnect"
                        try { socketRef.get()?.close() } catch (_: Exception) {}
                    }
                }
            } else {
                val id = obj.optLong("id", -1)
                val latch = responses[id]
                if (latch != null) {
                    responseValues[id] = obj
                    latch.countDown()
                } else if (id == 1L || id == 2L) {
                    responseValues[id] = obj
                } else if (id == suggestDifficultyRequestId.get()) {
                    suggestDifficultyRequestId.set(-1)
                    val error = obj.opt("error")
                    difficultySuggestionStatus = if (error == null || error == JSONObject.NULL) {
                        if (obj.optBoolean("result")) "Pool accepted suggested difficulty ${config.suggestedDifficulty}"
                        else "Pool ignored suggested difficulty request"
                    } else {
                        "Pool rejected mining.suggest_difficulty: ${error}"
                    }
                    lastMessage = difficultySuggestionStatus
                    emit("Mining")
                } else if (submitIds.containsKey(id)) {
                    val pending = submitIds.remove(id) ?: return
                    val submittedNonce = pending.nonce
                    lastSubmitLatencyMs.set((System.nanoTime() - pending.sentAtNanos) / 1_000_000L)
                    if (obj.optBoolean("result")) {
                        accepted.incrementAndGet()
                        rejectionReason = ""
                        lastMessage = "Share accepted · submit ${lastSubmitLatencyMs.get()} ms"
                        updateFeedStatus(submittedNonce, "accepted")
                    } else {
                        rejected.incrementAndGet()
                        rejectionReason = obj.opt("error")?.toString() ?: "Pool rejected share without a reason"
                        val lowerError = rejectionReason.lowercase(Locale.US)
                        if (pending.obsolete.get() || lowerError.contains("stale") ||
                            lowerError.contains("job not found") || lowerError.contains("duplicate")) {
                            staleShares.incrementAndGet()
                        }
                        lastMessage = "Share rejected: " + rejectionReason
                        updateFeedStatus(submittedNonce, "rejected")
                    }
                    emit("Mining", lastReportedRate)
                }
            }
        } catch (e: Exception) {
            lastMessage = "Invalid Stratum message: " + (e.message ?: e.javaClass.simpleName)
            emit("Protocol error")
        }
    }

    private fun expirePendingSubmits(nowNanos: Long) {
        val timeoutNanos = 60_000_000_000L
        submitIds.entries.toList().forEach { entry ->
            if (nowNanos - entry.value.sentAtNanos >= timeoutNanos &&
                submitIds.remove(entry.key, entry.value)) {
                staleShares.incrementAndGet()
                lastMessage = "Share submit timeout after 60 seconds · nonce %08x".format(Locale.US, entry.value.nonce)
                updateFeedStatus(entry.value.nonce, "rejected")
                emit("Mining", lastReportedRate)
            }
        }
    }

    private fun registerResponse(id: Long): CountDownLatch {
        val latch = CountDownLatch(1)
        responses[id] = latch
        return latch
    }

    private fun awaitResponse(id: Long, latch: CountDownLatch, seconds: Long): JSONObject {
        responseValues.remove(id)?.let { responses.remove(id); return it }
        if (!latch.await(seconds, TimeUnit.SECONDS)) {
            responses.remove(id)
            throw IllegalStateException("Pool response timeout (id $id)")
        }
        responses.remove(id)
        return responseValues.remove(id) ?: error("Missing pool response")
    }

    private fun send(obj: JSONObject) {
        val writer = writerRef.get() ?: error("Pool socket is not ready")
        synchronized(writer) {
            writer.println(obj.toString())
            if (writer.checkError()) error("Failed to write to pool")
        }
    }

    /** Read the process-wide native best hash at 2 Hz; no per-hash JNI callbacks. */
    private fun updateBestFromSnapshot(snapshot: LongArray): Boolean {
        if (snapshot.size < 9) return false
        val nonce = snapshot[0]
        if (nonce < 0L) return false
        val digest = digestFromWords(snapshot, 1)
        val candidateDifficulty = computeDifficulty(digest)
        if (candidateDifficulty <= bestShareDifficulty) return false
        bestShareDifficulty = candidateDifficulty
        bestShareBits = leadingZeroBits(digest)
        bestShareHashHex = toHex(digest.reversedArray())
        bestShareTimestampMs = System.currentTimeMillis()
        bestShareNonce = nonce
        lastMessage = "NEW BEST HASH · %.3e diff · %d leading bits · nonce %08x"
            .format(Locale.US, candidateDifficulty, bestShareBits, nonce)
        return true
    }

    /** Consume worker-local hash samples returned once per native batch. */
    private fun updateBestFromNativeBatch(scan: LongArray) {
        val shareCount = scan.getOrElse(1) { 0L }.toInt().coerceAtLeast(0)
        val blockCountIndex = shareCount + 2
        val blockCount = scan.getOrElse(blockCountIndex) { 0L }.toInt().coerceIn(0, 1024)
        val feedCountIndex = blockCountIndex + 1 + blockCount
        val feedCount = scan.getOrElse(feedCountIndex) { 0L }.toInt().coerceIn(0, 4096)
        var index = feedCountIndex + 1
        synchronized(feedLock) {
            repeat(feedCount) {
                val sampleNonce = scan.getOrElse(index++) { -1L }
                val digest = digestFromWords(scan, index)
                index += 8
                if (sampleNonce >= 0L) {
                    val bits = leadingZeroBits(digest)
                    val diff = computeDifficulty(digest)
                    val entry = HashFeedEntry(sampleNonce, toHex(digest.reversedArray()), bits,
                        System.currentTimeMillis(), diff, if (bits >= 32) "threshold" else "normal")
                    pendingFeed.addLast(entry)
                    while (pendingFeed.size > 512) pendingFeed.removeFirst()
                    if (topFeed.none { it.nonce == entry.nonce }) {
                        topFeed.add(entry)
                        topFeed.sortByDescending { it.difficulty }
                        while (topFeed.size > 10) topFeed.removeAt(topFeed.lastIndex)
                    }
                }
            }
        }
        val now = System.nanoTime()
        val interval = 1_000_000_000L / feedRateHz.get().coerceAtLeast(10)
        if (now - lastFeedEmitNanos >= interval) {
            synchronized(feedLock) {
                if (pendingFeed.isNotEmpty()) {
                    liveFeed.addLast(pendingFeed.removeFirst())
                    while (liveFeed.size > 200) liveFeed.removeFirst()
                }
            }
            lastFeedEmitNanos = now
            emit("Mining", lastReportedRate)
        }
    }

    private fun digestFromWords(values: LongArray, offset: Int): ByteArray {
        val digest = ByteArray(32)
        for (word in 0 until 8) {
            val value = values.getOrElse(offset + word) { 0L }.toInt()
            val at = word * 4
            digest[at] = (value ushr 24).toByte()
            digest[at + 1] = (value ushr 16).toByte()
            digest[at + 2] = (value ushr 8).toByte()
            digest[at + 3] = value.toByte()
        }
        return digest
    }

    private fun addFeedEntry(nonce: Long, digest: ByteArray, status: String) {
        val bits = leadingZeroBits(digest)
        val entry = HashFeedEntry(nonce, toHex(digest.reversedArray()), bits,
            System.currentTimeMillis(), computeDifficulty(digest), status)
        synchronized(feedLock) {
            liveFeed.addLast(entry)
            while (liveFeed.size > 200) liveFeed.removeFirst()
            topFeed.add(entry)
            topFeed.sortByDescending { it.difficulty }
            while (topFeed.size > 10) topFeed.removeAt(topFeed.lastIndex)
        }
    }

    private fun updateFeedStatus(nonce: Long, status: String) {
        synchronized(feedLock) {
            val rows = liveFeed.map { if (it.nonce == nonce) it.copy(status = status) else it }
            liveFeed.clear()
            rows.forEach { liveFeed.addLast(it) }
        }
    }

    private fun emit(state: String, rate: Double = 0.0) {
        val nativeWorkers = NativePowEngine.nativeGetLastScanWorkerCount()
        val thermalPaused = paused.get()
        onStats(
            MinerStats(
                state = state,
                hashrate = rate,
                totalHashes = hashes.get(),
                accepted = accepted.get(),
                rejected = rejected.get(),
                difficulty = difficulty.get(),
                message = lastMessage,
                latencyMs = latencyMs.get(),
                rejectionReason = rejectionReason,
                difficultySuggestion = difficultySuggestionStatus,
                bestShareDifficulty = bestShareDifficulty,
                bestShareBits = bestShareBits,
                bestShareHashHex = bestShareHashHex,
                bestShareTimestampMs = bestShareTimestampMs,
                bestShareNonce = bestShareNonce,
                hashFeed = synchronized(feedLock) { liveFeed.toList() },
                topHashes = synchronized(feedLock) { topFeed.toList() },
                cpuSensorName = cpuSensorName.get(),
                networkBitsHex = networkBitsHex,
                networkDifficulty = networkDifficulty,
                bitsMissing = (BitcoinTarget.leadingZeroBits(networkTargetRef.get()) - bestShareBits).coerceAtLeast(0),
                cpuTempC = cpuTempC.get(),
                effectiveThreads = if (thermalPaused) 0 else nativeWorkers.getOrElse(0) { 0L }.toInt(),
                nativeAffinityThreads = if (thermalPaused) 0 else nativeWorkers.getOrElse(2) { 0L }.toInt(),
                nativeAllowedCpus = nativeWorkers.getOrElse(1) { 0L }.toInt(),
                staleShares = staleShares.get(),
                lastSubmitLatencyMs = lastSubmitLatencyMs.get()
            )
        )
    }

    private fun hex(value: String): ByteArray {
        val s = value.trim().lowercase()
        require(s.length % 2 == 0 && s.all { it in "0123456789abcdef" }) { "Invalid pool hex data" }
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun le(value: String, bytes: Int): ByteArray {
        val raw = hex(value.padStart(bytes * 2, '0').takeLast(bytes * 2))
        return raw.reversedArray()
    }

    private fun swapWords(bytes: ByteArray): ByteArray {
        require(bytes.size == 32) { "Pool prevhash must be 32 bytes" }
        return ByteArray(32) { i -> bytes[(i / 4) * 4 + (3 - i % 4)] }
    }

    private fun targetFor(diff: Double): ByteArray {
        val max = BigInteger("00000000ffff0000000000000000000000000000000000000000000000000000", 16)
        val target = max.toBigDecimal().divide(diff.toBigDecimal(), 0, RoundingMode.DOWN).toBigInteger()
            .coerceAtMost(BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE))
        val b = target.toByteArray().let { if (it.size > 32) it.takeLast(32).toByteArray() else it }
        return ByteArray(32) { i -> if (i < 32 - b.size) 0 else b[i - (32 - b.size)] }
    }

    private fun meetsTarget(hash: ByteArray, target: ByteArray): Boolean {
        for (i in 31 downTo 0) {
            val h = hash[i].toInt() and 255
            val t = target[31 - i].toInt() and 255
            if (h < t) return true
            if (h > t) return false
        }
        return true
    }

    /**
     * Bitcoin difficulty of a hash = max_target / hash_LE_uint256.
     * A difficulty >= current network difficulty means the hash would
     * have been a valid block on mainnet.
     */
    private fun computeDifficulty(hash: ByteArray): Double {
        // Fast double-precision estimate using only the most-significant 64 bits
        // of the Bitcoin uint256. This avoids BigInteger division in the hash loop.
        var high64 = 0.0
        for (i in 31 downTo 24) high64 = high64 * 256.0 + (hash[i].toInt() and 0xff)
        if (high64 == 0.0) return Double.POSITIVE_INFINITY
        // difficulty-1 target's leading 64-bit word is 0x00000000ffff0000.
        return 4_294_901_760.0 / high64
    }

    /** Leading zero bits of the hash in big-endian display order. */
    /** Leading zero bits of the displayed Bitcoin hash (reverse of raw SHA digest). */
    private fun leadingZeroBits(hash: ByteArray): Int =
        BitcoinTarget.leadingZeroBitsFromDisplayHash(toHex(hash.reversedArray()))

    private fun toHex(bytes: ByteArray): String {
        val alphabet = "0123456789abcdef"
        val chars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val value = bytes[i].toInt() and 0xff
            chars[i * 2] = alphabet[value ushr 4]
            chars[i * 2 + 1] = alphabet[value and 15]
        }
        return String(chars)
    }

    private object PowerThermalStatus {
        const val NONE = 0
        const val LIGHT = 1
        const val MODERATE = 2
        const val SEVERE = 3
    }

    companion object {
        private const val nativeBatchSize = 2097152
    }
}
