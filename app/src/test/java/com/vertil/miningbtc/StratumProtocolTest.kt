package com.vertil.miningbtc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest

class StratumProtocolTest {
    @Test
    fun merkleRootMatchesBitcoinGenesisCoinbaseWithoutBranches() {
        val coinbase = hex(
            "0100000001" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "ffffffff4d" +
                "04ffff001d0104455468652054696d65732030332f4a616e2f32303039204368616e63656c6c6f72206f6e206272696e6b206f66207365636f6e64206261696c6f757420666f722062616e6b73" +
                "ffffffff01" +
                "00f2052a01000000" +
                "4341" +
                "04678afdb0fe5548271967f1a67130b7105cd6a828e03909a67962e0ea1f61deb649f6bc3f4cef38c4f35504e51ec112de5c384df7ba0b8d578a4c702b6bf11d5fac" +
                "00000000"
        )
        assertEquals(
            "4a5e1e4baab89f3a32518a88c31bc87f618f76673e2cc77ab2127b7afdeda33b",
            StratumProtocol.merkleRoot(coinbase, emptyList()).reversedArray().toHex()
        )
    }

    @Test
    fun merkleRootFoldsVariableBranchLengthsInPoolOrder() {
        val coinbase = hex("010203040506")
        for (count in listOf(0, 1, 2, 4, 9)) {
            val branches = (0 until count).map { index ->
                ByteArray(32) { offset -> (index * 31 + offset).toByte() }
            }
            var expected = sha256d(coinbase)
            branches.forEach { branch -> expected = sha256d(expected + branch) }
            assertArrayEquals(expected, StratumProtocol.merkleRoot(coinbase, branches))
        }
    }

    @Test
    fun rejectsMalformedMerkleBranch() {
        assertThrows(IllegalArgumentException::class.java) {
            StratumProtocol.merkleRoot(byteArrayOf(1), listOf(ByteArray(31)))
        }
    }

    @Test
    fun extranonce2IsFixedWidthAndWrapsAtItsDeclaredSize() {
        assertEquals("00000001", StratumProtocol.extranonce2Hex(BigInteger.ONE, 4))
        assertEquals("ffffffff", StratumProtocol.extranonce2Hex(BigInteger("ffffffff", 16), 4))
        assertEquals("00000000", StratumProtocol.extranonce2Hex(BigInteger("100000000", 16), 4))
        assertEquals("0001", StratumProtocol.extranonce2Hex(BigInteger.ONE, 2))
    }

    @Test
    fun validatesExtranonceParametersInsteadOfSilentlyClampingThem() {
        StratumProtocol.validateExtranonce("00a1ff", 4)
        assertThrows(IllegalArgumentException::class.java) {
            StratumProtocol.validateExtranonce("0xz1", 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StratumProtocol.validateExtranonce("00", 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StratumProtocol.validateExtranonce("0", 4)
        }
    }

    private fun sha256d(data: ByteArray): ByteArray {
        val first = MessageDigest.getInstance("SHA-256").digest(data)
        return MessageDigest.getInstance("SHA-256").digest(first)
    }

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
