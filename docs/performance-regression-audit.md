# Snapdragon 865 mining performance regression audit

## Findings from the checked-in implementation

The JNI boundary was not inside the SHA-256d nonce loop: Kotlin calls nativeScanBatch once per batch. However, the Stratum scanner's C++ implementation contained several costs that the standalone native benchmark did not have:

1. A contended std::atomic<uint64_t>::fetch_add(1) for every nonce. The native benchmark uses worker-local counters and aggregates after joining, so its result was not representative of the Stratum scanner's inner-loop overhead.
2. Eight std::thread objects were created and joined for every 262,144-nonce batch. At approximately 15 MH/s this can cause frequent thread lifecycle overhead.
3. A new Midstate was computed in every scan batch even though the first 64 header bytes remain constant for one job/extranonce2.
4. Workers were assigned the full Android-allowed CPU mask. This did not deliberately prefer the Snapdragon 865's high-capacity CPU cluster.
5. The Activity initialized the live hash feed at 30 Hz, causing repeated main-thread dispatch and feed rendering.

The original parallel Stratum scanner implementation that introduced the per-hash atomic increment is commit 3e4d68e12fb0f90de359f76bea0d1d52300962b9. The later hash-accounting correction (55fd2fd) corrected counting across target rescans; it did not itself add the hot-loop atomic. The available commit history does not establish a single isolated change as the sole cause of the reported 15-to-9/11 MH/s regression. These are source-level overhead findings, not a device-profiled causal attribution.

## Implemented in this branch

- Midstate is prepared once when a job/extranonce2 header is assembled and passed to each native batch through JNI.
- Each worker owns a static strided nonce sequence: worker t hashes start+t, then adds the worker count. This guarantees disjoint coverage without a shared nonce allocator.
- Hash counters, best-hash state, share hits, block hits and feed samples are worker-local in the hot loop. Results are merged after workers finish. No mutex or JNI call is made per nonce.
- The scanner pins each worker to the faster half of CPUs allowed to the process, using cpu_capacity and falling back to cpuinfo_max_freq. If Android exposes no usable topology information or denies affinity, the worker remains under OS scheduling. The runtime log reports actual affinity success.
- Batch size increased from 262,144 to 2,097,152 to reduce thread creation, JNI crossings and result marshaling frequency.
- Hash-feed updates are limited to 4 Hz, with the setter clamped to 2–5 Hz.
- Target comparisons retain early exit from the most-significant byte. The stricter of the pool and network targets is checked first, preserving correctness whether pool difficulty is above or below network difficulty.

## What is not claimed

Midstate removes one of the three SHA-256 compression blocks per nonce after the initial job setup. That is one-third fewer compression blocks in the header SHA-256d path, not a guaranteed 50% hashrate increase. Throughput depends on the ARM crypto path, compiler output, CPU scheduling, thermal state and battery/power policy.

A green GitHub Actions run proves compilation and regression tests, not Snapdragon throughput. The acceptance target remains a sustained device measurement above 15 MH/s with CKPool accepted shares and thermal telemetry. It must be measured on the Galaxy S20 FE after installing this branch's APK.

## Device acceptance procedure

1. Use the same CKPool endpoint, wallet/worker, network connection and thermal starting condition as the prior 15 MH/s measurement.
2. Record a 60-second native benchmark, then a 10-minute live Stratum run. Do not compare a short peak to a thermally sustained rate.
3. Capture hashrate every second, CPU frequency per core, Android PowerManager thermal status, battery charge-counter delta, accepted/rejected/stale shares and actual affinity count.
4. Confirm that eight workers are created and that affinity success is nonzero when Android exposes the performance-cluster topology.
5. Stop the run if Android reaches SEVERE thermal status; the existing thermal pause remains enabled.

The two-hour stability/energy test remains separate from this short performance acceptance run.
