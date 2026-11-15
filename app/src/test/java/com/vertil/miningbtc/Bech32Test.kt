package com.vertil.miningbtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Bech32Test {
    @Test fun acceptsMainnetWitnessV0P2wpkh() {
        assertTrue(Bech32.isValidMainnet("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"))
    }

    @Test fun acceptsMainnetWitnessV0P2wsh() {
        assertTrue(Bech32.isValidMainnet("bc1qqqqsyqcyq5rqwzqfpg9scrgwpugpzysnzs23v9ccrydpk8qarc0szrtjt7"))
    }

    @Test fun acceptsMainnetWitnessV1Taproot() {
        assertTrue(Bech32.isValidMainnet("bc1pqqqsyqcyq5rqwzqfpg9scrgwpugpzysnzs23v9ccrydpk8qarc0sg5tmnz"))
    }

    @Test fun acceptsUppercaseAddress() {
        assertTrue(Bech32.isValidMainnet("BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4"))
    }

    @Test fun rejectsWrongNetworkAndChecksumVariant() {
        assertFalse(Bech32.isValidMainnet("tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"))
        assertFalse(Bech32.isValidMainnet("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kemeawh"))
    }
}

class WalletConfigTest {
    private val wallet = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"

    @Test fun restoresWalletFromNewDedicatedPreference() {
        assertEquals(wallet, WalletConfig.restoreWallet(wallet, "other.worker"))
    }

    @Test fun migratesLegacyWalletWorkerPreference() {
        assertEquals(wallet, WalletConfig.restoreWallet(null, "$wallet.worker1"))
    }

    @Test fun composesStratumUsernameWithoutChangingPayoutAddress() {
        assertEquals("$wallet.worker1", WalletConfig.stratumUsername(wallet, "worker1"))
        assertEquals(wallet, WalletConfig.stratumUsername(wallet, ""))
    }
}
