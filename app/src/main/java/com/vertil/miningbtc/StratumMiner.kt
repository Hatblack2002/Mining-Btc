package com.vertil.miningbtc

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

data class PoolConfig(val host: String, val port: Int, val username: String, val password: String, val tls: Boolean)

data class MinerStats(
    val state: String,
    val hashrate: Double = 0.0,
    val hashes: Long = 0,
    val accepted: Long = 0,
    val rejected: Long = 0,
    val difficulty: Double = 0.0,
    val message: String = "",
    val latencyMs: Long = -1,
    val rejectionReason: String = ""
)

class StratumMiner(private val config: PoolConfig, private val onStats: (MinerStats) -> Unit) {
    private val running = AtomicBoolean(false)
    private val jobRef = AtomicReference<PoolJob?>(null)
    private val difficulty = AtomicReference(1.0)
    private val targetRef = AtomicReference(targetFor(1.0))
    private val extranonce1 = AtomicReference("")
    private val extranonce2Size = AtomicReference(4)
    private val hashes = AtomicLong(0)
    private val accepted = AtomicLong(0)
    private val rejected = AtomicLong(0)
    private val socketRef = AtomicReference<Socket?>(null)
    private val writerRef = AtomicReference<PrintWriter?>(null)
    private val requestId = AtomicLong(2)
    private val responses = java.util.concurrent.ConcurrentHashMap<Long, CountDownLatch>()
    private val responseValues = java.util.concurrent.ConcurrentHashMap<Long, JSONObject>()
    private val submitIds = java.util.concurrent.ConcurrentHashMap<Long, Boolean>()
    private val pingSentAt = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private val latencyMs = AtomicLong(-1)
    @Volatile private var rejectionReason = ""
    @Volatile private var lastMessage = "Connecting"

    private data class PoolJob(
        val id: String, val prevhash: String, val coinb1: String, val coinb2: String,
        val branches: List<String>, val version: String, val nbits: String,
        val ntime: String, val clean: Boolean
    )

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread(name = "stratum-connection") { runSession() }
    }

    fun stop() {
        running.set(false)
        try { socketRef.getAndSet(null)?.close() } catch (_: Exception) {}
        lastMessage = "Stopped"
        emit("Stopped")
    }

    private fun runSession() {
        var attempt = 0
        while (running.get()) {
            runSingleSession()
            if (!running.get()) break
            val delaySeconds = (1L shl attempt.coerceAtMost(5)).coerceAtMost(30L)
            lastMessage = "Reconnecting in " + delaySeconds + "s"
            emit("Reconnecting")
            try { Thread.sleep(delaySeconds * 1000L) } catch (_: InterruptedException) { break }
            attempt++
        }
    }

    private fun runSingleSession() {
        try {
            require(config.host.isNotBlank()) { "Pool host is required" }
            val addresses = InetAddress.getAllByName(config.host).sortedBy { if (it is java.net.Inet6Address) 0 else 1 }
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
            val plain = connected ?: throw IllegalStateException("IPv6/IPv4 connection failed: " + (connectFailure?.message ?: "no resolved addresses"), connectFailure)
            plain.soTimeout = 0
            val socket = if (config.tls) {
                val sslSocket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(plain, config.host, config.port, true) as javax.net.ssl.SSLSocket
                sslSocket.startHandshake()
                sslSocket
            } else plain
            socketRef.set(socket)
            val writer = PrintWriter(socket.getOutputStream(), true)
            writerRef.set(writer)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            thread(name = "stratum-reader", isDaemon = true) {
                try {
                    while (running.get()) {
                        val line = reader.readLine() ?: break
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
            extranonce1.set(result.getString(1))
            extranonce2Size.set(result.getInt(2).coerceIn(1, 16))

            val authorizeResponse = registerResponse(2)
            send(JSONObject().put("id", 2).put("method", "mining.authorize")
                .put("params", JSONArray().put(config.username).put(config.password)))
            val auth = awaitResponse(2, authorizeResponse, 10)
            if (auth.optBoolean("result").not()) {
                throw IllegalStateException("Pool authorization failed: ${auth.opt("error")}")
            }

            lastMessage = "Authorized; waiting for pool work"
            emit("Waiting for work")
            val digest1 = MessageDigest.getInstance("SHA-256")
            val digest2 = MessageDigest.getInstance("SHA-256")
            var nonce = 0L
            var extraCounter = BigInteger.ZERO
            var activeJob: PoolJob? = null
            var headerPrefix: ByteArray? = null
            targetRef.set(targetFor(difficulty.get()))
            var sessionHashes = 0L
            var lastRateAt = System.nanoTime()
            var lastRateHashes = 0L
            var lastPingAt = System.nanoTime()

            while (running.get()) {
                val job = jobRef.get()
                if (job == null) {
                    Thread.sleep(100)
                    continue
                }
                if (job !== activeJob) {
                    activeJob = job
                    nonce = 0
                    val extra = extraCounter.toString(16).padStart(extranonce2Size.get() * 2, '0')
                        .takeLast(extranonce2Size.get() * 2)
                    val coinbase = hex(job.coinb1) + hex(extranonce1.get()) + hex(extra) + hex(job.coinb2)
                    var merkle = doubleSha(coinbase, digest1, digest2)
                    for (branch in job.branches) merkle = doubleSha(merkle + hex(branch), digest1, digest2)
                    headerPrefix = le(job.version, 4) + swapWords(hex(job.prevhash)) + merkle +
                        le(job.ntime, 4) + le(job.nbits, 4)
                    targetRef.set(targetFor(difficulty.get()))
                    lastMessage = "Mining job ${job.id}"
                    emit("Mining")
                }
                val prefix = headerPrefix ?: continue
                val header = ByteArray(80)
                System.arraycopy(prefix, 0, header, 0, 76)
                val currentExtra = extraCounter.toString(16).padStart(extranonce2Size.get() * 2, '0')
                    .takeLast(extranonce2Size.get() * 2)
                while (running.get() && jobRef.get() === activeJob && nonce <= 0xffffffffL) {
                    header[76] = nonce.toByte()
                    header[77] = (nonce ushr 8).toByte()
                    header[78] = (nonce ushr 16).toByte()
                    header[79] = (nonce ushr 24).toByte()
                    val hash = digest2.digest(digest1.digest(header))
                    hashes.incrementAndGet()
                    sessionHashes++
                    if (meetsTarget(hash, targetRef.get())) {
                        val id = requestId.incrementAndGet()
                        val nonceHex = String.format(Locale.US, "%08x", nonce)
                        val params = JSONArray().put(config.username).put(job.id).put(currentExtra)
                            .put(job.ntime).put(nonceHex)
                        submitIds[id] = true
                        send(JSONObject().put("id", id).put("method", "mining.submit").put("params", params))
                        lastMessage = "Share submitted"
                        emit("Mining")
                    }
                    nonce++
                    val now = System.nanoTime()
                    if (now - lastPingAt >= 30_000_000_000L) {
                        val pingId = requestId.incrementAndGet()
                        pingSentAt[pingId] = now
                        send(JSONObject().put("id", pingId).put("method", "mining.ping").put("params", JSONArray()))
                        lastPingAt = now
                    }
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
            lastMessage = e.message ?: "Mining error"
            if (running.get()) emit("Error")
        } finally {
            try { socketRef.getAndSet(null)?.close() } catch (_: Exception) {}
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
                        jobRef.set(PoolJob(p.getString(0), p.getString(1), p.getString(2), p.getString(3),
                            branches, p.getString(5), p.getString(6), p.getString(7), p.optBoolean(8)))
                        if (p.optBoolean(8)) lastMessage = "New clean job received"
                    }
                    "mining.set_difficulty" -> {
                        val p = obj.getJSONArray("params")
                        val nextDifficulty = p.getDouble(0).coerceAtLeast(0.00000001)
                        difficulty.set(nextDifficulty)
                        targetRef.set(targetFor(nextDifficulty))
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
                } else if (pingSentAt.containsKey(id)) {
                    val sentAt = pingSentAt.remove(id) ?: 0L
                    if (sentAt > 0L) latencyMs.set((System.nanoTime() - sentAt) / 1_000_000L)
                    emit("Mining")
                } else if (submitIds.remove(id) != null) {
                    if (obj.optBoolean("result")) {
                        accepted.incrementAndGet()
                        rejectionReason = ""
                        lastMessage = "Share accepted"
                    } else {
                        rejected.incrementAndGet()
                        rejectionReason = obj.opt("error")?.toString() ?: "Pool rejected share without a reason"
                        lastMessage = "Share rejected: " + rejectionReason
                    }
                    emit("Mining")
                }
            }
        } catch (_: Exception) {}
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

    private fun emit(state: String, rate: Double = 0.0) {
        onStats(MinerStats(state, rate, hashes.get(), accepted.get(), rejected.get(), difficulty.get(), lastMessage, latencyMs.get(), rejectionReason))
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

    private fun doubleSha(data: ByteArray, first: MessageDigest, second: MessageDigest): ByteArray =
        second.digest(first.digest(data))

    private fun targetFor(diff: Double): ByteArray {
        val max = BigInteger("00000000ffff0000000000000000000000000000000000000000000000000000", 16)
        val target = max.toBigDecimal().divide(diff.toBigDecimal(), 0, java.math.RoundingMode.DOWN).toBigInteger()
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
}
