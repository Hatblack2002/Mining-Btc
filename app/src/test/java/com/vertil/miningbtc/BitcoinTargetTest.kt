package com.vertil.miningbtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BitcoinTargetTest {
    @Test fun decodesGenesisCompactTarget() {
        val target = BitcoinTarget.fromCompact("1d00ffff")
        assertEquals("00000000ffff0000000000000000000000000000000000000000000000000000",
            target.joinToString("") { "%02x".format(it.toInt() and 0xff) })
        assertEquals(1.0, BitcoinTarget.difficulty(target), 0.000001)
    }

    @Test fun genesisBlockHashMeetsItsNetworkTarget() {
        val rawDigest = "6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertTrue(BitcoinTarget.meetsTarget(rawDigest, BitcoinTarget.fromCompact("1d00ffff")))
    }

    @Test fun falseEasyTargetCanTriggerSimulationCondition() {
        val rawDigest = ByteArray(32)
        val falseEasyTarget = ByteArray(32) { 0xff.toByte() }
        assertTrue(BitcoinTarget.meetsTarget(rawDigest, falseEasyTarget))
    }
}
