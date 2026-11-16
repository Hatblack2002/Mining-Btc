#pragma once
#include <array>
#include <cstdint>
#include <string>
#include <algorithm>
#include <vector>

namespace powlab {
using Digest = std::array<uint8_t, 32>;
using Midstate = std::array<uint32_t, 8>;

void sha256_compress_portable(uint32_t state[8], const uint8_t block[64]);
bool arm_sha2_available();
#if defined(__aarch64__)
void sha256_compress_armv8(uint32_t state[8], const uint8_t block[64]);
#endif
Midstate make_midstate(const uint8_t header[80], bool use_arm);
Digest hash_header_nonce(const uint8_t header[80], uint32_t nonce, const Midstate& midstate, bool use_arm);
bool digest_meets_target_early(const Digest& digest, const Digest& target);
bool digest_meets_stratum_target(const Digest& digest, const Digest& target);
struct FeedSample { uint32_t nonce; Digest digest; };
struct ScanResult { uint64_t hashes; std::vector<uint32_t> shares; std::vector<uint32_t> blocks; uint32_t best_nonce; Digest best_digest; bool has_best; std::vector<FeedSample> feed; };
struct BestHashSnapshot { uint32_t nonce; Digest digest; bool has_best; };
void record_best_hash(const ScanResult& scan);
BestHashSnapshot get_best_hash();
void reset_best_hash();
unsigned last_scan_worker_count();
unsigned last_scan_allowed_cpu_count();
unsigned last_scan_affinity_worker_count();
ScanResult scan_nonce_batch(const uint8_t header[80], const Midstate& midstate, uint32_t start, uint32_t count, unsigned threads, bool use_arm, const Digest& target, const Digest& block_target);
std::string self_test_report();
}
