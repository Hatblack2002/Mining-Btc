# Mining-Btc
Android-first Bitcoin solo mining client for the **Samsung Galaxy S20 FE (Snapdragon 865)**.

## Current phase: Stratum v1 solo mining + worker telemetry
Builds a debug APK that runs a real **Stratum v1** solo-mining client on the phone:
- Native ARMv8 SHA2 double-SHA-256 (with portable fallback) via JNI
- 8-thread parallel nonce scanning on the device CPU
- Full Stratum v1 protocol: subscribe, authorize, suggest_difficulty, notify, set_difficulty, ping, submit, client.reconnect
- IPv4/IPv6 dual-stack connect with bounded exponential reconnect
- TLS support
- Foreground Service + WakeLock (mines while screen is off)
- Thermal backoff: 8 threads below 75°C, 4 threads 75–85°C, paused above 85°C
- Best-share telemetry: tracks the highest share difficulty and leading-zero bit count ever produced
- Bech32 / Bech32m mainnet wallet validation before START
- Persistent stats across reboots (hashes, accepted, rejected, best share)

This is **not a toy benchmark**. The app connects to real mainnet solo pools and submits real shares. If a share meets the current network difficulty target, the pool broadcasts a real block and you collect the 3.125 BTC subsidy + fees at the configured wallet.

## Solo mining expectations
A Snapdragon 865 typically sustains **50–200 kH/s** of SHA-256d with 8 ARMv8 SHA2 threads. The Bitcoin network hashrate is on the order of 700 EH/s; the expected time to find a block at this hashrate is astronomically large. Solo mining on a phone is a lottery ticket, not a revenue stream. You pay for electricity and battery wear; the reward is probabilistic.

If you want a meaningful probability of finding a block, switch to dedicated SHA-256 ASIC hardware (Bitaxe open-source stick at ~1 TH/s, or Bitmain/MicroBT/Canaan professional miners at 100+ TH/s). The Kotlin Stratum client in this repo is reusable as a controller for those devices.

## Wallet destination
The receiving address is configured in the app and validated as Bech32 / Bech32m on mainnet before mining starts. Use a **non-custodial** wallet you control the seed for (BlueWallet, Electrum, hardware wallet). **Never use an exchange deposit address**: exchanges do not credit mining coinbase rewards to deposit addresses, and a winning block would be lost.

Never add seed phrases, private keys, wallet backups, or credentials to this repository. The app stores only the public receiving address and worker credentials in SharedPreferences on the device.

## Build
GitHub Actions builds a debug APK using JDK 17, Android SDK 35, NDK 27.2.12479018, CMake 3.22.1, and Gradle 8.9. Every push to `main` triggers the build workflow. The APK is published as the `mining-btc-debug-apk` workflow artifact.

The native SHA-256d unit tests (genesis hash parity, target comparison, batch scanner) run before the APK build.

## Safety
- The miner can heat the phone to 75–90°C within minutes. The built-in thermal backoff throttles threads at 75°C and pauses at 85°C. Do not disable it.
- Sustained CPU workloads degrade lithium-ion batteries. Run plugged in, ventilated, and remove any case.
- Stop if the phone becomes uncomfortably hot, the screen shows thermal warnings, or charging slows down unexpectedly.
- Do not leave the phone mining unattended for days without checking in.
- Solo mining consumes electricity. At typical smartphone power draw (3–6 W) this is negligible per day, but understand your local electricity cost before running 24/7.

## Roadmap
1. ✅ Android SHA-256d benchmark and Bech32 address validation.
2. ✅ Measure sustained hashrate, thermal throttling, and energy use on real hardware.
3. ✅ Implement and test Stratum solo protocol support.
4. ✅ Add pool configuration and worker telemetry.
5. ⚠️ Assess whether phone-based solo mining has practical value. (Documented above: it does not, as a revenue stream. It does, as a learning tool and as a lottery ticket the user is free to buy.)

## Repository layout
- `app/src/main/java/com/vertil/miningbtc/`
  - `MainActivity.kt` — UI, Bech32 validation, binds to MiningService.
  - `MiningService.kt` — Foreground Service hosting the miner; WakeLock; persistent stats.
  - `StratumMiner.kt` — Stratum v1 client with thermal backoff and best-share tracking.
  - `Bech32.kt` — BIP 173 / BIP 350 address validation.
  - `NativePowEngine.kt` — JNI bridge to native SHA-256d.
- `app/src/main/cpp/` — Native SHA-256d (ARMv8 SHA2 + portable) and batch scanner.
- `tools/stratum_mock_pool.py` — Local Stratum V1 test pool for Termux.
