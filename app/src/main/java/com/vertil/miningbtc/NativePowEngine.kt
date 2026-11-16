package com.vertil.miningbtc

/**
 * JNI entry points for the native SHA-256d engine. This class is deliberately
 * independent of the UI and Stratum client during Phase 1.
 */
object NativePowEngine {
    init { System.loadLibrary("powlab") }

    external fun nativeCapabilities(): String
    external fun nativeSelfTest(): String
    /** Returns [bestNonce, digestWord0..7]; polled twice per second, never per hash. */
    external fun nativePollBestHash(): LongArray
    external fun nativeGetLastScanWorkerCount(): LongArray
    external fun nativeResetBestHash()
    external fun nativeHashHeader(header80: ByteArray, nonce: Long, useArmSha2: Boolean): ByteArray
    /** Returns scan counts, pool-share nonces, network-target nonces, then sampled (nonce,digest) entries. */
    external fun nativeScanBatch(header80: ByteArray, startNonce: Long, nonceCount: Int, threads: Int, useArmSha2: Boolean, target32: ByteArray, networkTarget32: ByteArray): LongArray
    /** Returns [hashes, activeThreads, detectedCpuThreads]. affinityMode: 0=OS, 1=performance, 2=efficiency. */
    external fun nativeBenchmark(header80: ByteArray, durationMs: Long, threads: Int, useArmSha2: Boolean, affinityMode: Int): LongArray
}
