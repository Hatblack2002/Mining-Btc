package com.vertil.miningbtc

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/** Bitcoin compact nBits target conversion and consensus-order comparison. */
object BitcoinTarget {
    private val maxDifficultyOne = BigInteger("00000000ffff0000000000000000000000000000000000000000000000000000", 16)
    private val uint256Max = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE)

    fun fromCompact(compactHex: String): ByteArray {
        val compact = compactHex.toLong(16)
        val exponent = ((compact ushr 24) and 0xff).toInt()
        val mantissa = compact and 0x007fffffL
        require((compact and 0x00800000L) == 0L && mantissa != 0L) { "Invalid negative or zero compact target" }
        require(exponent in 3..32) { "Compact target exponent outside uint256 range" }
        val target = BigInteger.valueOf(mantissa).shiftLeft(8 * (exponent - 3))
        require(target.signum() > 0 && target <= uint256Max) { "Compact target overflows uint256" }
        return toFixed32(target)
    }

    fun meetsTarget(rawDigest: ByteArray, targetBigEndian: ByteArray): Boolean {
        require(rawDigest.size == 32 && targetBigEndian.size == 32)
        for (i in 31 downTo 0) {
            val hashByte = rawDigest[i].toInt() and 0xff
            val targetByte = targetBigEndian[31 - i].toInt() and 0xff
            if (hashByte < targetByte) return true
            if (hashByte > targetByte) return false
        }
        return true
    }

    fun difficulty(targetBigEndian: ByteArray): Double {
        val target = BigInteger(1, targetBigEndian)
        if (target.signum() == 0) return Double.POSITIVE_INFINITY
        return maxDifficultyOne.toBigDecimal().divide(target.toBigDecimal(), 8, RoundingMode.HALF_UP).toDouble()
    }

    fun leadingZeroBits(bigEndian: ByteArray): Int {
        var bits = 0
        for (byte in bigEndian) {
            val value = byte.toInt() and 0xff
            if (value == 0) bits += 8 else {
                bits += Integer.numberOfLeadingZeros(value) - 24
                break
            }
        }
        return bits
    }

    private fun toFixed32(value: BigInteger): ByteArray {
        val raw = value.toByteArray().let { if (it.size > 32) it.takeLast(32).toByteArray() else it }
        return ByteArray(32) { i -> if (i < 32 - raw.size) 0 else raw[i - (32 - raw.size)] }
    }
}

data class BlockFoundRecord(
    val header80: ByteArray,
    val hashHex: String,
    val nonce: Long,
    val timestampMs: Long,
    val compactBits: String,
    val networkDifficulty: Double,
    val simulated: Boolean = false
)
