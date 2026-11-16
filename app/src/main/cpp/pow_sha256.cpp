#include "pow_sha256.h"
#include <cstring>
#include <algorithm>
#include <string>
#include <sstream>
#include <iomanip>
#include <atomic>
#include <thread>
#include <mutex>
#include <sched.h>
#include <chrono>
#include <fstream>
#include <numeric>
#if defined(__ANDROID__)
#include <android/log.h>
#endif
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

static inline void compress(uint32_t s[8],const uint8_t b[64],bool arm) {
#if defined(__aarch64__)
 if(arm && arm_sha2_available()) { sha256_compress_armv8(s,b); return; }
#else
 (void)arm;
#endif
 sha256_compress_portable(s,b);
}

Midstate make_midstate(const uint8_t header[80],bool arm) {
 Midstate m; std::copy(IV,IV+8,m.begin());
 compress(m.data(),header,arm);
 return m;
}

static inline void store_be(uint8_t* p,uint32_t x) {
 p[0]=uint8_t(x>>24);p[1]=uint8_t(x>>16);p[2]=uint8_t(x>>8);p[3]=uint8_t(x);
}

Digest hash_header_nonce(const uint8_t header[80],uint32_t nonce,const Midstate& mid,bool arm) {
 uint8_t block[64]={0};
 std::memcpy(block,header+64,12);
 block[12]=uint8_t(nonce);block[13]=uint8_t(nonce>>8);block[14]=uint8_t(nonce>>16);block[15]=uint8_t(nonce>>24);
 block[16]=0x80;block[62]=0x02;block[63]=0x80;
 uint32_t s[8];std::copy(mid.begin(),mid.end(),s);
 compress(s,block,arm);
 uint8_t first[32];for(int i=0;i<8;i++)store_be(first+4*i,s[i]);
 uint8_t second[64]={0};std::memcpy(second,first,32);second[32]=0x80;second[62]=0x01;
 uint32_t out[8];std::copy(IV,IV+8,out);compress(out,second,arm);
 Digest digest;for(int i=0;i<8;i++)store_be(digest.data()+4*i,out[i]);
 return digest;
}

// Digest bytes are the raw SHA-256 output; Bitcoin uint256 compares them
// from byte 31 down to byte 0. Target inputs from Kotlin are big-endian.
bool digest_meets_stratum_target(const Digest& h,const Digest& target) {
 for(int i=31;i>=0;i--) {
  const uint8_t value=target[size_t(31-i)];
  if(h[size_t(i)]<value) return true;
  if(h[size_t(i)]>value) return false;
 }
 return true;
}
static inline bool target_less_equal(const Digest& a,const Digest& b) {
 for(int i=0;i<32;i++) if(a[size_t(i)]!=b[size_t(i)]) return a[size_t(i)]<b[size_t(i)];
 return true;
}

static inline bool digest_numeric_less(const Digest& a,const Digest& b) {
 const uint32_t ah=(uint32_t(a[31])<<24)|(uint32_t(a[30])<<16)|(uint32_t(a[29])<<8)|uint32_t(a[28]);
 const uint32_t bh=(uint32_t(b[31])<<24)|(uint32_t(b[30])<<16)|(uint32_t(b[29])<<8)|uint32_t(b[28]);
 if(ah!=bh) return ah<bh;
 for(int i=27;i>=0;i--) if(a[size_t(i)]!=b[size_t(i)]) return a[size_t(i)]<b[size_t(i)];
 return false;
}

static std::atomic<unsigned> g_last_scan_workers{0};
static std::atomic<unsigned> g_last_scan_allowed_cpus{0};
static std::atomic<unsigned> g_last_scan_affinity_workers{0};
static std::atomic<int64_t> g_last_affinity_log_ms{0};

unsigned last_scan_worker_count() { return g_last_scan_workers.load(std::memory_order_relaxed); }
unsigned last_scan_allowed_cpu_count() { return g_last_scan_allowed_cpus.load(std::memory_order_relaxed); }
unsigned last_scan_affinity_worker_count() { return g_last_scan_affinity_workers.load(std::memory_order_relaxed); }

struct CpuCapacity {
 unsigned cpu;
 int capacity;
};

static std::vector<unsigned> performance_cpu_set(const cpu_set_t& allowed) {
 // Sysfs topology is immutable for the process lifetime; read it once rather
 // than opening CPU files on every multi-million-nonce batch.
 static const std::vector<CpuCapacity> topology=[] {
  std::vector<CpuCapacity> all;
  const unsigned count=std::min<unsigned>(CPU_SETSIZE,std::max(1u,std::thread::hardware_concurrency()));
  all.reserve(count);
  for(unsigned cpu=0;cpu<count;cpu++) {
   int capacity=0;
   {
    std::ifstream f("/sys/devices/system/cpu/cpu"+std::to_string(cpu)+"/cpu_capacity");
    if(!(f>>capacity)) capacity=0;
   }
   if(capacity<=0) {
    std::ifstream f("/sys/devices/system/cpu/cpu"+std::to_string(cpu)+"/cpufreq/cpuinfo_max_freq");
    if(f>>capacity) capacity/=1000;
   }
   all.push_back({cpu,capacity});
  }
  return all;
 }();
 std::vector<CpuCapacity> candidates;
 for(const CpuCapacity& cpu:topology) if(CPU_ISSET(cpu.cpu,&allowed)) candidates.push_back(cpu);
 if(candidates.empty()) return {};
 const bool hasCapacity=std::any_of(candidates.begin(),candidates.end(),[](const CpuCapacity& c){return c.capacity>0;});
 if(!hasCapacity) return {};
 std::stable_sort(candidates.begin(),candidates.end(),[](const CpuCapacity& a,const CpuCapacity& b){
  return a.capacity>b.capacity;
 });
 // On heterogeneous mobile SoCs the faster half is the performance cluster.
 // Keep all top-capacity CPUs, but never widen beyond Android's allowed mask.
 const size_t selected=std::max<size_t>(1,(candidates.size()+1)/2);
 std::vector<unsigned> cpus;
 cpus.reserve(selected);
 for(size_t i=0;i<selected;i++) cpus.push_back(candidates[i].cpu);
 return cpus;
}

struct WorkerResult {
 uint64_t hashes=0;
 std::vector<uint32_t> shares;
 std::vector<uint32_t> blocks;
 Digest best{};
 uint32_t bestNonce=0;
 bool hasBest=false;
 std::array<FeedSample,8> feed{};
 uint32_t feedCount=0;
};

ScanResult scan_nonce_batch(const uint8_t header[80],const Midstate& mid,uint32_t start,uint32_t count,
                            unsigned threads,bool arm,const Digest& target,const Digest& blockTarget) {
 if(count==0 || threads==0 || uint64_t(start)+uint64_t(count)>(1ULL<<32))
  return {0,{},{},0,{},false,{}};

 cpu_set_t allowed; CPU_ZERO(&allowed);
 const bool have_allowed=sched_getaffinity(0,sizeof(allowed),&allowed)==0;
 unsigned allowed_count=0;
 if(have_allowed) for(unsigned cpu=0;cpu<CPU_SETSIZE;cpu++) if(CPU_ISSET(cpu,&allowed)) allowed_count++;
 g_last_scan_allowed_cpus.store(allowed_count,std::memory_order_relaxed);
 const std::vector<unsigned> performanceCpus=have_allowed?performance_cpu_set(allowed):std::vector<unsigned>{};

 std::vector<WorkerResult> results(threads);
 std::vector<std::thread> workers;
 workers.reserve(threads);
 std::atomic<unsigned> affinity_applied{0};

 // Static nonce striding: worker t owns start+t, start+t+threads, ...
 // No shared nonce allocator and no duplicate work between workers.
 for(unsigned t=0;t<threads;t++) workers.emplace_back([&,t] {
  if(!performanceCpus.empty()) {
   cpu_set_t pin; CPU_ZERO(&pin);
   CPU_SET(performanceCpus[t%performanceCpus.size()],&pin);
   if(sched_setaffinity(0,sizeof(pin),&pin)==0)
    affinity_applied.fetch_add(1,std::memory_order_relaxed);
  }
  WorkerResult& local=results[t];
  for(uint64_t offset=t;offset<count;offset+=threads) {
   const uint32_t nonce=uint32_t(uint64_t(start)+offset);
   const Digest digest=hash_header_nonce(header,nonce,mid,arm);
   ++local.hashes;

   if(!local.hasBest || digest_numeric_less(digest,local.best)) {
    local.best=digest; local.bestNonce=nonce; local.hasBest=true;
   }
   // One display sample per 65536 nonce positions; no locks/JNI in this loop.
   if(((offset/threads)&65535u)==0u) {
    local.feed[local.feedCount%local.feed.size()]={nonce,digest};
    ++local.feedCount;
   }

   // Check the stricter target first. If it passes, the looser target
   // necessarily passes too; this remains correct for unusual pool difficulties.
   if(target_less_equal(blockTarget,target)) {
    if(digest_meets_stratum_target(digest,blockTarget)) {
     local.blocks.push_back(nonce);
     local.shares.push_back(nonce);
    } else if(digest_meets_stratum_target(digest,target)) {
     local.shares.push_back(nonce);
    }
   } else {
    if(digest_meets_stratum_target(digest,target)) {
     local.shares.push_back(nonce);
     local.blocks.push_back(nonce);
    } else if(digest_meets_stratum_target(digest,blockTarget)) {
     local.blocks.push_back(nonce);
    }
   }
  }
 });

 for(auto& worker:workers) worker.join();

 std::vector<uint32_t> found,blocks;
 std::vector<FeedSample> feed;
 uint64_t totalHashes=0;
 Digest bestDigest{}; uint32_t bestNonce=0; bool hasBest=false;
 for(const WorkerResult& local:results) {
  totalHashes+=local.hashes;
  found.insert(found.end(),local.shares.begin(),local.shares.end());
  blocks.insert(blocks.end(),local.blocks.begin(),local.blocks.end());
  const uint32_t feedEntries=std::min<uint32_t>(local.feedCount,uint32_t(local.feed.size()));
  for(uint32_t sample=0;sample<feedEntries;sample++) feed.push_back(local.feed[sample]);
  if(local.hasBest && (!hasBest || digest_numeric_less(local.best,bestDigest))) {
   bestDigest=local.best; bestNonce=local.bestNonce; hasBest=true;
  }
 }
 g_last_scan_workers.store(static_cast<unsigned>(workers.size()),std::memory_order_relaxed);
 g_last_scan_affinity_workers.store(affinity_applied.load(std::memory_order_relaxed),std::memory_order_relaxed);
 const int64_t now_ms=std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
 int64_t expected_log=g_last_affinity_log_ms.load(std::memory_order_relaxed);
 if(now_ms-expected_log>=10000 && g_last_affinity_log_ms.compare_exchange_strong(expected_log,now_ms)) {
#if defined(__ANDROID__)
  __android_log_print(ANDROID_LOG_INFO,"MiningBTC-Native",
   "workers_created=%u/%u affinity_applied=%u/%u allowed_cpus=%u performance_cpus=%zu",
   static_cast<unsigned>(workers.size()),threads,affinity_applied.load(std::memory_order_relaxed),threads,
   allowed_count,performanceCpus.size());
#endif
 }
 std::sort(found.begin(),found.end());
 std::sort(blocks.begin(),blocks.end());
 std::sort(feed.begin(),feed.end(),[](const FeedSample& a,const FeedSample& b){return a.nonce<b.nonce;});
 return {totalHashes,std::move(found),std::move(blocks),bestNonce,bestDigest,hasBest,std::move(feed)};
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
void reset_best_hash() {
 std::lock_guard<std::mutex> lock(bestHashMutex);
 globalBestHash=BestHashSnapshot{0,{},false};
}
bool digest_meets_target_early(const Digest& h,const Digest& t) {
 for(int i=31;i>=0;i--) if(h[size_t(i)]!=t[size_t(i)]) return h[size_t(i)]<t[size_t(i)];
 return true;
}
static std::string hex(const uint8_t* p,size_t n) {
 std::ostringstream s;s<<std::hex<<std::setfill('0');
 for(size_t i=0;i<n;i++) s<<std::setw(2)<<unsigned(p[i]);
 return s.str();
}
std::string self_test_report() {
 const std::string h="01000000" "0000000000000000000000000000000000000000000000000000000000000000"
 "3ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa4b1e5e4a"
 "29ab5f49" "ffff001d" "1dac2b7c";
 uint8_t header[80];for(int i=0;i<80;i++)header[i]=uint8_t(std::stoul(h.substr(size_t(i)*2,2),nullptr,16));
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
