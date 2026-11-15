package com.vertil.miningbtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Bech32Test {
    @Test fun acceptsMainnetWitnessV0P2wpkh() {
        assertTrue(Bech32.isValidMainnet("bc1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq9e75rs"))
    }

    @Test fun acceptsMainnetWitnessV0P2wsh() {
        assertTrue(Bech32.isValidMainnet("bc1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqthqst8"))
    }

    @Test fun acceptsMainnetWitnessV1Taproot() {
        assertTrue(Bech32.isValidMainnet("bc1pqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqpqqenm"))
    }

    @Test fun acceptsUppercaseAddress() {
        assertTrue(Bech32.isValidMainnet("BC1QQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQ9E75RS"))
    }

    @Test fun rejectsWrongNetworkAndChecksumVariant() {
        assertFalse(Bech32.isValidMainnet("tb1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq9e75rs"))
        assertFalse(Bech32.isValidMainnet("bc1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq9e75rt"))
    }
}

class WalletConfigTest {
    private val wallet = "bc1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq9e75rs"

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
