# Phase 2 validation matrix — RQ-01 to RQ-15

**Repository:** Hatblack2002/Mining-Btc  
**Branch:** phase2-stratum-hardening  
**Rule:** A source change or a successful APK build is not, by itself, evidence that a requirement passed. No requirement is marked Approved until its stated evidence exists.

| ID | Requirement | Implementation / evidence | Real status |
|---|---|---|---|
| RQ-01 | SHA-256 known vectors | Native C++ test now checks the standard SHA-256("abc") digest. | Verified in CI run #255 |
| RQ-02 | SHA-256d parity with Bitcoin Core | Native test checks the Bitcoin genesis header hash; Python independently checks the same known genesis proof-of-work vector. A direct Bitcoin Core command-line comparison is not yet attached. | Partial; genesis vector passed in CI run #255; direct Bitcoin Core CLI parity evidence pending |
| RQ-03 | ARM64 SHA2 instructions in packaged .so | ARMv8 source is compiled with +crypto; CI now disassembles libpowlab.so and requires SHA256H/H2/SU0/SU1 instructions. | Verified in CI run #255; ARM64 disassembly artifact uploaded |
| RQ-04 | Reproducible Merkle-root vectors | Kotlin helper computes coinbase double-SHA256 and folds each 32-byte branch in order; JVM tests cover the genesis coinbase and branch counts 0, 1, 2, 4 and 9. | Verified in CI run #255 |
| RQ-05 | Exact 80-byte block header | Python regression checks the genesis header length, field offsets and resulting proof-of-work hash. | Verified in CI run #255 |
| RQ-06 | Target boundaries and difficulty | Native tests cover target rejection, acceptance and equality; batch scanner checks pool-share and network-target filtering separately. | Verified in CI run #255 |
| RQ-07 | Complete simulated Stratum session | Python mock fixture exercises subscribe, authorize, set_difficulty, notify, suggest_difficulty and submit. It currently drives the mock server directly; it does not instantiate the Android Kotlin client. | Partial; Android-client integration evidence pending |
| RQ-08 | Accepted and rejected shares | Android client records accepted/rejected responses and stale/timeout outcomes. The mock currently proves the accepted response path only; a deterministic rejected-share integration test is not yet present. | Partial; rejected-share test pending |
| RQ-09 | Two-hour stability | Requires a continuous run on the Galaxy S20 FE with no crash, deadlock, runaway memory or lost session. | Pending physical-device run |
| RQ-10 | Reconnect after network loss | Client has bounded exponential reconnect after a session ends. No automated test yet proves recovery after an induced network interruption. | Reconnect backoff resets after successful authorization; induced network-loss test pending |
| RQ-11 | Real Bitcoin block regression | Bitcoin genesis coinbase/Merkle root and 80-byte genesis header/hash are used as reproducible real-mainnet fixtures. | Verified in CI run #255 |
| RQ-12 | Variable merkle_branch length | Kotlin tests exercise zero, one, two and multiple branch elements, validate 32-byte elements, and compare the fold against an independent test implementation. | Implemented; CI result pending |
| RQ-13 | mining.set_extranonce update | Client validates new extranonce1/size, stores them, and replaces the active job object so coinbase, Merkle root and header are rebuilt. | Handler and validation helper implemented; app-level event test pending |
| RQ-14 | Ping/pong and session persistence | Client no longer sends a non-standard ping unconditionally. It responds only to a request-style mining.ping received from the pool; ordinary session liveness uses TCP keepalive; a read timeout merely resumes polling and does not disconnect an otherwise quiet pool. | Conditional pool-initiated ping response implemented; live pool interoperability test pending |
| RQ-15 | Two-hour telemetry: hashrate, CPU frequency, battery mAh | CSV v3 records timestamps, CPU/battery temperature, Android thermal status, hashrate, worker/affinity counts, per-CPU frequencies, app state, battery charge-counter µAh and net consumed mAh. Charge-counter values are -1 when unsupported. | Instrumentation implemented; two-hour device capture pending |

## Required evidence before Phase 2 approval

1. GitHub Actions green: native C++ tests, Python Bitcoin vectors, Stratum mock tests, Kotlin JVM tests and Android ARM64 debug APK.
2. Downloadable APK artifact from that successful run.
3. Native disassembly artifact showing ARMv8 SHA2 instructions in the packaged ARM64 shared library.
4. Device log/CSV from a continuous two-hour Galaxy S20 FE run, including actual battery charge-counter availability.
5. A real Android-client Stratum test covering a rejected share, mining.set_extranonce, optional pool-initiated ping, and reconnect after network loss.

CI evidence: GitHub Actions run #255 completed successfully on commit 20b31482d457d68f9f00c980e3eba5960938b3fd. Native tests, Python Bitcoin vectors, Stratum mock tests, Kotlin JVM tests, APK build and packaged ARM64 SHA2 disassembly check all passed. Artifacts: mining-btc-debug-apk and mining-btc-native-disassembly. The branch has since received the reconnect-backoff reset and documentation updates; their latest workflow run is tracked separately.

Until these items are attached, Phase 2 remains **in validation**, not approved. Phase 3 is not included in this branch's scope.
