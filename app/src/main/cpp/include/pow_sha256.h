#pragma once
#include <array>
#include <cstdint>

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
std::string self_test_report();
}
