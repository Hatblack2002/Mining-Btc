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
        if (version < 0 || version > 16) return false
        val programSize = decoded.data.size - 1
        if (programSize < 2 || programSize > 40) return false
        // Witness v0 programs must be exactly 20 (P2WPKH) or 32 (P2WSH) bytes.
        if (version == 0 && programSize != 20 && programSize != 32) return false
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
        val out = IntArray(hrp.length * 2)
        for (i in hrp.indices) {
            out[i] = hrp[i].code shr 5
            out[hrp.length + i] = hrp[i].code and 31
        }
        return out
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
