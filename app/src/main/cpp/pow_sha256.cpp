#include "pow_sha256.h"
#include <cstring>
#include <algorithm>
#include <string>
#include <sstream>
#include <iomanip>
#include <atomic>
#include <thread>
#include <mutex>
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
bool digest_meets_stratum_target(const Digest& h,const Digest& target) {
 for(int i=31;i>=0;i--) {
  const uint8_t value=target[size_t(31-i)];
  if(h[size_t(i)]<value) return true;
  if(h[size_t(i)]>value) return false;
 }
 return true;
}
static bool digest_numeric_less(const Digest& a,const Digest& b) {
 // Bitcoin uint256 uses the reversed SHA digest byte order. Compare its
 // most-significant 32-bit word first, then the remaining words only on ties.
 const uint32_t ah=(uint32_t(a[31])<<24)|(uint32_t(a[30])<<16)|(uint32_t(a[29])<<8)|uint32_t(a[28]);
 const uint32_t bh=(uint32_t(b[31])<<24)|(uint32_t(b[30])<<16)|(uint32_t(b[29])<<8)|uint32_t(b[28]);
 if(ah!=bh) return ah<bh;
 for(int i=27;i>=0;i--) if(a[size_t(i)]!=b[size_t(i)]) return a[size_t(i)]<b[size_t(i)];
 return false;
}
ScanResult scan_nonce_batch(const uint8_t header[80],uint32_t start,uint32_t count,unsigned threads,bool arm,const Digest& target,const Digest& blockTarget) {
 if(count==0 || threads==0 || uint64_t(start)+uint64_t(count)>(1ULL<<32)) return {0,{},{},0,{},false,{}};
 const Midstate mid=make_midstate(header,arm);
 std::atomic<uint32_t> next{0};
 std::atomic<uint64_t> scanned{0};
 std::mutex resultMutex;
 std::vector<uint32_t> found; std::vector<uint32_t> blocks;
 Digest bestDigest{}; uint32_t bestNonce=0; bool hasBest=false; std::vector<FeedSample> feed;
 std::vector<std::thread> workers;workers.reserve(threads);
 for(unsigned t=0;t<threads;t++) workers.emplace_back([&] {
  std::vector<uint32_t> local; std::vector<uint32_t> localBlocks;
  Digest localBest{}; uint32_t localBestNonce=0; bool localHasBest=false; std::vector<FeedSample> localFeed;
  for(;;) {
   const uint32_t offset=next.fetch_add(256,std::memory_order_relaxed);
   if(offset>=count) break;
   const uint32_t end=std::min<uint32_t>(count,offset+256);
   for(uint32_t i=offset;i<end;i++) {
    const uint32_t nonce=uint32_t(uint64_t(start)+i);
    const Digest digest=hash_header_nonce(header,nonce,mid,arm);
    scanned.fetch_add(1,std::memory_order_relaxed);
    if(!localHasBest || digest_numeric_less(digest,localBest)) {
     localBest=digest; localBestNonce=nonce; localHasBest=true;
    }
    // Sample one hash per 65536 nonces. Workers keep samples locally, so
    // the hot hash loop performs no locks and no JNI callbacks.
    if((i & 8191u)==0u) localFeed.push_back({nonce,digest});
    if(digest_meets_stratum_target(digest,target)) local.push_back(nonce);
    if(digest_meets_stratum_target(digest,blockTarget)) localBlocks.push_back(nonce);
   }
  }
  std::lock_guard<std::mutex> lock(resultMutex);
  found.insert(found.end(),local.begin(),local.end());
  blocks.insert(blocks.end(),localBlocks.begin(),localBlocks.end());
  feed.insert(feed.end(),localFeed.begin(),localFeed.end());
  if(localHasBest && (!hasBest || digest_numeric_less(localBest,bestDigest))) {
   bestDigest=localBest; bestNonce=localBestNonce; hasBest=true;
  }
 });
 for(auto& worker:workers) worker.join();
 std::sort(found.begin(),found.end());
 std::sort(blocks.begin(),blocks.end());
 std::sort(feed.begin(),feed.end(),[](const FeedSample& a,const FeedSample& b){return a.nonce<b.nonce;});
 return {scanned.load(std::memory_order_relaxed),std::move(found),std::move(blocks),bestNonce,bestDigest,hasBest,std::move(feed)};
}
static std::mutex bestHashMutex;
static BestHashSnapshot globalBestHash{0,{},false};
void record_best_hash(const ScanResult& scan) {
 if(!scan.has_best) return;
 std::lock_guard<std::mutex> lock(bestHashMutex);
 if(!globalBestHash.has_best || digest_numeric_less(scan.best_digest,globalBestHash.digest)) {
  globalBestHash.nonce=scan.best_nonce;
  globalBestHash.digest=scan.best_digest;
  globalBestHash.has_best=true;
 }
}
BestHashSnapshot get_best_hash() {
 std::lock_guard<std::mutex> lock(bestHashMutex);
 return globalBestHash;
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
 const std::string h="01000000" "0000000000000000000000000000000000000000000000000000000000000000"
 "3ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa4b1e5e4a"
 "29ab5f49" "ffff001d" "1dac2b7c";
 uint8_t header[80];for(int i=0;i<80;i++)header[i]=uint8_t(std::stoul(h.substr(i*2,2),nullptr,16));
 Midstate mp=make_midstate(header,false);Digest dp=hash_header_nonce(header,2083236893u,mp,false);
 const std::string expected="6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000";
 bool genesis=hex(dp.data(),32)==expected; bool parity=false; std::string armHash="NOT_AVAILABLE";
 if(arm_sha2_available()){Midstate ma=make_midstate(header,true);Digest da=hash_header_nonce(header,2083236893u,ma,true);armHash=hex(da.data(),32);parity=(ma==mp&&da==dp);}
 std::ostringstream out;out<<"genesis_portable="<<(genesis?"OK":"FAIL")<<" hash="<<hex(dp.data(),32)<<" nonce=2083236893"
 <<" arm_sha2_available="<<(arm_sha2_available()?"true":"false")<<" genesis_arm="<<(arm_sha2_available()?(parity?"OK":"FAIL"):"NOT_AVAILABLE")
 <<" arm_hash="<<armHash<<" portable_hash="<<hex(dp.data(),32)<<" arm_vs_portable="<<(arm_sha2_available()?(parity?"OK":"FAIL"):"NOT_AVAILABLE");
 return out.str();
}
}