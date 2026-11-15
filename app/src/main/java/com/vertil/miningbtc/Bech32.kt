package com.vertil.miningbtc

import java.util.Locale

/**
 * Bech32 / Bech32m validator for Bitcoin mainnet addresses.
 *
 * Implements BIP 173 (Bech32, witness v0 = P2WPKH / P2WSH) and BIP 350
 * (Bech32m, witness v1+ = P2TR and future SegWit versions).
 *
 * Used by MainActivity before allowing START: a malformed wallet would
 * silently route any block reward to a stranger even if every other piece
 * of the miner works perfectly.
 */
object Bech32 {
    private const val MAINNET_HRP = "bc"
    private const val CHECKSUM_LEN = 6
    private const val CONST_BECH32 = 1
    private const val CONST_BECH32M = 0x2bc830a3

    private val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val CHARSET_REV = IntArray(128) { -1 }.also {
        for (i in CHARSET.indices) it[CHARSET[i].code] = i
    }
    private val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    /** True if [address] is a valid Bitcoin mainnet Bech32/Bech32m address (P2WPKH, P2WSH, P2TR). */
    fun isValidMainnet(address: String): Boolean {
        val decoded = decode(address) ?: return false
        if (decoded.hrp != MAINNET_HRP) return false
        if (decoded.data.isEmpty()) return false
        val version = decoded.data[0]
        if (version !in 0..16) return false
        // Witness data is encoded as 5-bit groups. Convert it back to bytes
        // before applying BIP 141 program-length constraints.
        val program = convertBits(decoded.data.copyOfRange(1, decoded.data.size), 5, 8, false)
            ?: return false
        if (program.size !in 2..40) return false
        // Witness v0 programs must be exactly 20 (P2WPKH) or 32 (P2WSH) bytes.
        if (version == 0 && program.size != 20 && program.size != 32) return false
        return true
    }

    private data class Decoded(val hrp: String, val data: IntArray)

    private fun decode(address: String): Decoded? {
        if (address.length < 8 || address.length > 90) return null
        val sameCase = address == address.uppercase(Locale.US) || address == address.lowercase(Locale.US)
        if (!sameCase) return null
        val s = address.lowercase(Locale.US)
        val pos = s.lastIndexOf('1')
        if (pos < 1 || pos + 7 > s.length) return null
        val hrp = s.substring(0, pos)
        for (c in hrp) if (c.code < 33 || c.code > 126) return null
        val dataPart = s.substring(pos + 1)
        val data = IntArray(dataPart.length)
        for (i in dataPart.indices) {
            val v = CHARSET_REV[dataPart[i].code]
            if (v < 0) return null
            data[i] = v
        }
        val polymod = polymod(hrpExpand(hrp) + data)
        if (polymod != CONST_BECH32 && polymod != CONST_BECH32M) return null
        // BIP 350: witness v0 must use Bech32, witness v1+ must use Bech32m.
        val versionByte = data[0]
        val expectedConst = if (versionByte == 0) CONST_BECH32 else CONST_BECH32M
        if (polymod != expectedConst) return null
        return Decoded(hrp, data.copyOfRange(0, data.size - CHECKSUM_LEN))
    }

    private fun hrpExpand(hrp: String): IntArray {
        // BIP 173 expansion: high bits, separator zero, then low bits.
        val out = IntArray(hrp.length * 2 + 1)
        for (i in hrp.indices) {
            out[i] = hrp[i].code shr 5
            out[hrp.length + 1 + i] = hrp[i].code and 31
        }
        return out
    }

    /** Convert between bit-group sizes, rejecting non-zero or excessive padding. */
    private fun convertBits(data: IntArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray? {
        val maxValue = (1 shl toBits) - 1
        val maxAccumulator = (1 shl (fromBits + toBits - 1)) - 1
        var accumulator = 0
        var bits = 0
        val output = ArrayList<Byte>()
        for (value in data) {
            if (value < 0 || (value shr fromBits) != 0) return null
            accumulator = ((accumulator shl fromBits) or value) and maxAccumulator
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                output.add(((accumulator shr bits) and maxValue).toByte())
            }
        }
        if (pad) {
            if (bits > 0) output.add(((accumulator shl (toBits - bits)) and maxValue).toByte())
        } else {
            if (bits >= fromBits) return null
            if (((accumulator shl (toBits - bits)) and maxValue) != 0) return null
        }
        return ByteArray(output.size) { output[it] }
    }

    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val top = chk shr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) {
                if (((top shr i) and 1) == 1) chk = chk xor GENERATOR[i]
            }
        }
        return chk
    }
}
