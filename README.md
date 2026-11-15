# Mining-Btc

Android-first Bitcoin mining research project for the Samsung Galaxy S20 FE (Snapdragon 865).

## Current phase: SHA-256d benchmark

The first build is an **experimental SHA-256d benchmark**, not a production Bitcoin miner. It measures how quickly the phone can perform Bitcoin-style double-SHA-256 operations and validates the configured public Bitcoin receiving address.

It does not connect to Stratum, construct valid Bitcoin block headers, submit shares, mine mainnet blocks, or generate BTC. A real solo-mining client requires correct Stratum job parsing, coinbase/merkle construction, header serialization, target comparison, share submission, reconnect logic, and protocol tests.

## Wallet destination

The public receiving address is configured in the app. Never add seed phrases, private keys, wallet backups, or credentials to this repository.

## Build

GitHub Actions builds a debug APK using JDK 17, Android SDK 35, and Gradle 8.9. Every push to `main` starts the Android build workflow. The APK is published as the `mining-btc-debug-apk` workflow artifact after a successful build.

## Safety

The benchmark can heat the phone and drain its battery. Run short tests, keep the device ventilated, and stop if it becomes hot. Do not leave sustained CPU workloads running unattended.

## Roadmap

1. Android SHA-256d benchmark and Bech32 address validation.
2. Measure sustained hashrate, thermal throttling, and energy use on real hardware.
3. Implement and test Stratum solo protocol support.
4. Add pool configuration and worker telemetry.
5. Assess whether phone-based solo mining has practical value.
