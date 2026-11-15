package com.vertil.miningbtc

/**
 * JNI entry points for the native SHA-256d engine. This class is deliberately
 * independent of the UI and Stratum client during Phase 1.
 */
object NativePowEngine {
    init { System.loadLibrary("powlab") }

    external fun nativeCapabilities(): String
    external fun nativeSelfTest(): String
    external fun nativeHashHeader(header80: ByteArray, nonce: Long, useArmSha2: Boolean): ByteArray
    /** Returns [hashes, activeThreads, detectedCpuThreads]. */
    external fun nativeBenchmark(header80: ByteArray, durationMs: Long, threads: Int, useArmSha2: Boolean): LongArray
}
