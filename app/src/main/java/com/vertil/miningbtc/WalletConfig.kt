package com.vertil.miningbtc

/** Separates the payout address shown in the UI from the Stratum username. */
object WalletConfig {
    /**
     * New installs store the payout address separately. Older builds stored
     * wallet.worker in "user", so strip only the final worker suffix on migration.
     */
    fun restoreWallet(savedWallet: String?, savedUser: String?): String {
        if (!savedWallet.isNullOrBlank()) return savedWallet
        val legacyUser = savedUser.orEmpty()
        return legacyUser.substringBeforeLast(".", legacyUser)
    }

    fun stratumUsername(wallet: String, worker: String): String {
        val baseWallet = wallet.substringBeforeLast(".", wallet)
        return if (worker.isBlank()) baseWallet else "$baseWallet.$worker"
    }
}
