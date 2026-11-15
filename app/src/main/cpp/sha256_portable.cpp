#include "pow_sha256.h"
#include <cstring>

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
static inline uint32_t rotr(uint32_t x, int n) { return (x >> n) | (x << (32 - n)); }
static inline uint32_t load_be(const uint8_t* p) {
 return (uint32_t(p[0])<<24)|(uint32_t(p[1])<<16)|(uint32_t(p[2])<<8)|uint32_t(p[3]);
}
void sha256_compress_portable(uint32_t s[8], const uint8_t block[64]) {
 uint32_t w[64];
 for(int i=0;i<16;i++) w[i]=load_be(block+4*i);
 for(int i=16;i<64;i++) {
  uint32_t a=rotr(w[i-15],7)^rotr(w[i-15],18)^(w[i-15]>>3);
  uint32_t b=rotr(w[i-2],17)^rotr(w[i-2],19)^(w[i-2]>>10);
  w[i]=w[i-16]+a+w[i-7]+b;
 }
 uint32_t a=s[0],b=s[1],c=s[2],d=s[3],e=s[4],f=s[5],g=s[6],h=s[7];
 for(int i=0;i<64;i++) {
  uint32_t s1=rotr(e,6)^rotr(e,11)^rotr(e,25);
  uint32_t ch=(e&f)^(~e&g);
  uint32_t t1=h+s1+ch+K[i]+w[i];
  uint32_t s0=rotr(a,2)^rotr(a,13)^rotr(a,22);
  uint32_t maj=(a&b)^(a&c)^(b&c);
  uint32_t t2=s0+maj;
  h=g;g=f;f=e;e=d+t1;d=c;c=b;b=a;a=t1+t2;
 }
 s[0]+=a;s[1]+=b;s[2]+=c;s[3]+=d;s[4]+=e;s[5]+=f;s[6]+=g;s[7]+=h;
}
}
