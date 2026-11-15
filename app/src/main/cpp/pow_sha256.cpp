#include "pow_sha256.h"
#include <cstring>
#include <algorithm>
#include <string>
#include <sstream>
#include <iomanip>
#if defined(__ANDROID__) && defined(__aarch64__)
#include <sys/auxv.h>
#include <asm/hwcap.h>
#endif
namespace powlab {
static constexpr uint32_t IV[8]={0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
bool arm_sha2_available() {
#if defined(__ANDROID__) && defined(__aarch64__) && defined(HWCAP_SHA2)
 return (getauxval(AT_HWCAP)&HWCAP_SHA2)!=0;
#else
 return false;
#endif
}
static void compress(uint32_t s[8],const uint8_t b[64],bool arm) {
#if defined(__aarch64__)
 if(arm && arm_sha2_available()) { sha256_compress_armv8(s,b); return; }
#else
 (void)arm;
#endif
 sha256_compress_portable(s,b);
}
Midstate make_midstate(const uint8_t header[80],bool arm) {
 Midstate m; std::copy(IV,IV+8,m.begin());
 compress(m.data(),header,arm); return m;
}
static void store_be(uint8_t* p,uint32_t x) {
 p[0]=uint8_t(x>>24);p[1]=uint8_t(x>>16);p[2]=uint8_t(x>>8);p[3]=uint8_t(x);
}
Digest hash_header_nonce(const uint8_t header[80],uint32_t nonce,const Midstate& mid,bool arm) {
 uint8_t block[64]={0};
 std::memcpy(block,header+64,12);
 block[12]=uint8_t(nonce);block[13]=uint8_t(nonce>>8);block[14]=uint8_t(nonce>>16);block[15]=uint8_t(nonce>>24);
 block[16]=0x80;block[62]=0x02;block[63]=0x80; // 640-bit header length
 uint32_t s[8];std::copy(mid.begin(),mid.end(),s);
 compress(s,block,arm);
 uint8_t first[32];for(int i=0;i<8;i++)store_be(first+4*i,s[i]);
 uint8_t second[64]={0};std::memcpy(second,first,32);second[32]=0x80;second[62]=0x01;second[63]=0x00;
 uint32_t out[8];std::copy(IV,IV+8,out);compress(out,second,arm);
 Digest digest;for(int i=0;i<8;i++)store_be(digest.data()+4*i,out[i]);return digest;
}
bool digest_meets_target_early(const Digest& h,const Digest& t) {
 // Bitcoin interprets the raw SHA digest as a little-endian uint256.
 // Reject on the most-significant (last) 32-bit word before comparing the remainder.
 for(int i=31;i>=28;i--) if(h[size_t(i)]!=t[size_t(i)]) return h[size_t(i)]<t[size_t(i)];
 for(int i=27;i>=0;i--) if(h[size_t(i)]!=t[size_t(i)]) return h[size_t(i)]<t[size_t(i)];
 return true;
}
static std::string hex(const uint8_t* p,size_t n) {
 std::ostringstream s;s<<std::hex<<std::setfill('0');
 for(size_t i=0;i<n;i++)s<<std::setw(2)<<unsigned(p[i]);return s.str();
}
std::string self_test_report() {
 // Bitcoin genesis block header, serialized in wire order.
 const std::string h="01000000" "0000000000000000000000000000000000000000000000000000000000000000"
 "3ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa4b1e5e4a"
 "29ab5f49" "ffff001d" "1dac2b7c";
 uint8_t header[80];for(int i=0;i<80;i++)header[i]=uint8_t(std::stoul(h.substr(i*2,2),nullptr,16));
 Midstate m=make_midstate(header,false);Digest d=hash_header_nonce(header,2083236893u,m,false);
 const std::string expected="6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000";
 bool genesis=hex(d.data(),32)==expected;
 bool parity=true;
 if(arm_sha2_available()) {
  Midstate ma=make_midstate(header,true);Digest da=hash_header_nonce(header,2083236893u,ma,true);
  parity=(ma==m && da==d);
 }
 std::ostringstream out;
 out<<"genesis_hash="<<(genesis?"PASS":"FAIL")<<" expected="<<expected<<" actual="<<hex(d.data(),32)
 <<" nonce_2083236893="<<(genesis?"PASS":"FAIL")
 <<" arm_sha2_available="<<(arm_sha2_available()?"true":"false")
 <<" arm_vs_portable="<<(parity?"PASS":"NOT_RUN_NO_ARM_SHA2");
 return out.str();
}
}
