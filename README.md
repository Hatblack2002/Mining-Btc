# Mining-Btc
Android-first Bitcoin solo mining client for the **Samsung Galaxy S20 FE (Snapdragon 865)**.

## Current phase: Stratum v1 solo mining + worker telemetry
Builds a debug APK that runs a real **Stratum v1** solo-mining client on the phone:
- Native ARMv8 SHA2 double-SHA-256 (with portable fallback) via JNI
- 8-thread parallel nonce scanning on the device CPU
- Stratum V1: subscribe, authorize, suggest_difficulty, notify, set_difficulty, set_extranonce, submit and client.reconnect; request-style ping is answered only when the pool initiates it (the client does not send a non-standard ping unconditionally)
- IPv4/IPv6 dual-stack connect with bounded exponential reconnect
- TLS support
- Foreground Service + WakeLock (mines while screen is off)
- Thermal protection: all eight workers remain enabled until Android PowerManager reports SEVERE or higher; hashing then pauses while the Stratum reader remains active, and resumes after the official status stays below SEVERE for 30 seconds. Sensor temperature is recorded as telemetry, not used as a fabricated universal cutoff.
- Best-share telemetry: tracks the highest share difficulty and leading-zero bit count ever produced
- Bech32 / Bech32m mainnet wallet validation before START
- Persistent stats across reboots (hashes, accepted, rejected, best share)

This is **not a toy benchmark**. The app connects to real mainnet solo pools and submits real shares. If a share meets the current network difficulty target, the pool broadcasts a real block and you collect the 3.125 BTC subsidy + fees at the configured wallet.

## Solo mining expectations
The Galaxy S20 FE Snapdragon 865 has previously measured **15.76 MH/s** in the multithreaded native benchmark (with a **16.39 MH/s** mining-session reading). Sustained throughput varies with thermal state, scheduler behavior and the active pool workload; these measurements are not a guaranteed continuous rate. The Bitcoin network hashrate is on the order of 700 EH/s; the expected time to find a block at this hashrate is astronomically large. Solo mining on a phone is a lottery ticket, not a revenue stream. You pay for electricity and battery wear; the reward is probabilistic.

If you want a meaningful probability of finding a block, switch to dedicated SHA-256 ASIC hardware (Bitaxe open-source stick at ~1 TH/s, or Bitmain/MicroBT/Canaan professional miners at 100+ TH/s). The Kotlin Stratum client in this repo is reusable as a controller for those devices.

## Wallet destination
The receiving address is configured in the app and validated as Bech32 / Bech32m on mainnet before mining starts. Use a **non-custodial** wallet you control the seed for (BlueWallet, Electrum, hardware wallet). **Never use an exchange deposit address**: exchanges do not credit mining coinbase rewards to deposit addresses, and a winning block would be lost.

Never add seed phrases, private keys, wallet backups, or credentials to this repository. The app stores only the public receiving address and worker credentials in SharedPreferences on the device.

## Build
GitHub Actions builds a debug APK using JDK 17, Android SDK 35, NDK 27.2.12479018, CMake 3.22.1, and Gradle 8.9. Every push to `main` triggers the build workflow. The APK is published as the `mining-btc-debug-apk` workflow artifact.

The CI pipeline runs native SHA-256d vectors, target and batch-scanner tests, Bitcoin genesis Merkle/header vectors, Stratum mock-session tests, Kotlin unit tests, and the Android debug build. It also disassembles the packaged ARM64 library and checks for SHA-256 ARMv8 instructions.

## Safety
- The miner can heat the phone to 75–90°C within minutes. Thermal protection follows Android PowerManager status: hashing pauses at SEVERE or higher and resumes only after 30 seconds below SEVERE. Do not disable it.
- Sustained CPU workloads degrade lithium-ion batteries. Run plugged in, ventilated, and remove any case.
- Stop if the phone becomes uncomfortably hot, the screen shows thermal warnings, or charging slows down unexpectedly.
- Do not leave the phone mining unattended for days without checking in.
- Solo mining consumes electricity. At typical smartphone power draw (3–6 W) this is negligible per day, but understand your local electricity cost before running 24/7.

## Phase 2 hardening and validation

See docs/phase2-validation-matrix.md for the exact RQ-01–RQ-15 status. The app now validates extranonce updates, rebuilds the active job after mining.set_extranonce, answers request-style mining.ping only when the pool sends it, and records battery charge-counter deltas in mining-telemetry-v3.csv. A two-hour device run is still required; the charge-counter field is reported as unavailable (-1) when the device does not expose it.

## Snapdragon 865 performance recovery

The Stratum scanner now reuses one Midstate per job/extranonce2, uses worker-local counters and static nonce striding, and applies best-effort affinity to the high-capacity CPUs allowed by Android. Scan batches are 2,097,152 nonces and the live hash feed is limited to 4 Hz. The native hash loop contains no JNI calls or shared mutex operations.

See [the performance regression audit and device acceptance procedure](docs/performance-regression-audit.md). The 15 MH/s recovery target is not considered achieved until measured on the Galaxy S20 FE during a real CKPool session with accepted shares and thermal telemetry. CI compilation alone does not establish device hashrate.

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
