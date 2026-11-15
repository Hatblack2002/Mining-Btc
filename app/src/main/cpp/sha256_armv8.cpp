#include "pow_sha256.h"
#if defined(__aarch64__)
#include <arm_neon.h>
namespace powlab {
static constexpr uint32_t K[64] = {
0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2};
static inline uint32_t load_be(const uint8_t* p) {
 return (uint32_t(p[0])<<24)|(uint32_t(p[1])<<16)|(uint32_t(p[2])<<8)|uint32_t(p[3]);
}
void sha256_compress_armv8(uint32_t state[8], const uint8_t block[64]) {
 uint32_t words[16];
 for(int i=0;i<16;i++) words[i]=load_be(block+4*i);
 uint32x4_t m[4] = {vld1q_u32(words),vld1q_u32(words+4),vld1q_u32(words+8),vld1q_u32(words+12)};
 uint32x4_t abcd=vld1q_u32(state), efgh=vld1q_u32(state+4);
 const uint32x4_t save_abcd=abcd, save_efgh=efgh;
 for(int group=0;group<16;group++) {
  int slot=group&3;
  if(group>=4) {
   uint32x4_t sched=vsha256su0q_u32(m[slot],m[(slot+1)&3]);
   m[slot]=vsha256su1q_u32(sched,m[(slot+2)&3],m[(slot+3)&3]);
  }
  uint32x4_t wk=vaddq_u32(m[slot],vld1q_u32(K+group*4));
  uint32x4_t next_abcd=vsha256hq_u32(abcd,efgh,wk);
  efgh=vsha256h2q_u32(efgh,abcd,wk);
  abcd=next_abcd;
 }
 vst1q_u32(state,vaddq_u32(abcd,save_abcd));
 vst1q_u32(state+4,vaddq_u32(efgh,save_efgh));
}
}
#endif
