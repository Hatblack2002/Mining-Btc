#include <jni.h>
#include <atomic>
#include <mutex>
#include <chrono>
#include <thread>
#include <vector>
#include <algorithm>
#include <sched.h>
#include <fstream>
#include <string>
#include "nonce_scheduler.h"
#include "pow_sha256.h"
using namespace powlab;
extern "C" JNIEXPORT jstring JNICALL
Java_com_vertil_miningbtc_NativePowEngine_nativeCapabilities(JNIEnv* env,jclass) {
 std::string s=std::string("arm64_sha2=")+(arm_sha2_available()?"true":"false")+
 " cpu_threads="+std::to_string(std::max(1u,std::thread::hardware_concurrency()));
 return env->NewStringUTF(s.c_str());
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_vertil_miningbtc_NativePowEngine_nativeSelfTest(JNIEnv* env,jclass) {
 std::string s=self_test_report();return env->NewStringUTF(s.c_str());
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_vertil_miningbtc_NativePowEngine_nativeHashHeader(JNIEnv* env,jclass,jbyteArray input,jlong nonce,jboolean arm) {
 if(env->GetArrayLength(input)!=80)return nullptr;
 jbyte raw[80];env->GetByteArrayRegion(input,0,80,raw);
 uint8_t header[80];for(int i=0;i<80;i++)header[i]=uint8_t(raw[i]);
 Midstate m=make_midstate(header,arm);Digest d=hash_header_nonce(header,uint32_t(nonce),m,arm);
 jbyteArray out=env->NewByteArray(32);env->SetByteArrayRegion(out,0,32,reinterpret_cast<const jbyte*>(d.data()));return out;
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_vertil_miningbtc_NativePowEngine_nativeScanBatch(JNIEnv* env,jclass,jbyteArray input,jlong startNonce,jint nonceCount,jint requested,jboolean arm,jbyteArray targetInput,jbyteArray blockTargetInput) {
 if(env->GetArrayLength(input)!=80 || env->GetArrayLength(targetInput)!=32 || env->GetArrayLength(blockTargetInput)!=32 || startNonce<0 || nonceCount<=0 || requested<=0 || uint64_t(startNonce)+uint64_t(nonceCount)>(1ULL<<32)) return nullptr;
 jbyte raw[80];env->GetByteArrayRegion(input,0,80,raw);
 jbyte targetRaw[32];env->GetByteArrayRegion(targetInput,0,32,targetRaw);
 jbyte blockTargetRaw[32];env->GetByteArrayRegion(blockTargetInput,0,32,blockTargetRaw);
 uint8_t header[80];for(int i=0;i<80;i++)header[i]=uint8_t(raw[i]);
 Digest target,blockTarget;for(int i=0;i<32;i++){target[size_t(i)]=uint8_t(targetRaw[i]);blockTarget[size_t(i)]=uint8_t(blockTargetRaw[i]);}
 ScanResult scan=scan_nonce_batch(header,uint32_t(startNonce),uint32_t(nonceCount),unsigned(requested),arm,target,blockTarget);
 record_best_hash(scan);
 std::vector<jlong> values;values.reserve(scan.shares.size()+scan.blocks.size()+4+scan.feed.size()*9);
 values.push_back(jlong(scan.hashes));values.push_back(jlong(scan.shares.size()));
 for(uint32_t nonce:scan.shares) values.push_back(jlong(nonce));
 values.push_back(jlong(scan.blocks.size()));
 for(uint32_t nonce:scan.blocks) values.push_back(jlong(nonce));
 auto appendDigest=[&values](const Digest& digest) {
  for(int word=0;word<8;word++) {
   const int i=word*4;
   const uint32_t packed=(uint32_t(digest[size_t(i)])<<24)|
       (uint32_t(digest[size_t(i+1)])<<16)|
       (uint32_t(digest[size_t(i+2)])<<8)|
       uint32_t(digest[size_t(i+3)]);
   values.push_back(jlong(packed));
  }
 };
 values.push_back(jlong(scan.feed.size()));
 for(const FeedSample& sample:scan.feed) {
  values.push_back(jlong(sample.nonce));
  appendDigest(sample.digest);
 }
 jlongArray out=env->NewLongArray(jsize(values.size()));
 env->SetLongArrayRegion(out,0,jsize(values.size()),values.data());
 return out;
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_vertil_miningbtc_NativePowEngine_nativePollBestHash(JNIEnv* env,jclass) {
 BestHashSnapshot snapshot=get_best_hash();
 if(!snapshot.has_best) return env->NewLongArray(0);
 jlong values[9]; values[0]=jlong(snapshot.nonce);
 for(int word=0;word<8;word++) {
  const int i=word*4;
  const uint32_t packed=(uint32_t(snapshot.digest[size_t(i)])<<24)|
      (uint32_t(snapshot.digest[size_t(i+1)])<<16)|
      (uint32_t(snapshot.digest[size_t(i+2)])<<8)|
      uint32_t(snapshot.digest[size_t(i+3)]);
  values[word+1]=jlong(packed);
 }
 jlongArray out=env->NewLongArray(9);
 env->SetLongArrayRegion(out,0,9,values);
 return out;
}
extern "C" JNIEXPORT void JNICALL
Java_com_vertil_miningbtc_NativePowEngine_nativeResetBestHash(JNIEnv*,jclass) {
 reset_best_hash();
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_vertil_miningbtc_NativePowEngine_nativeBenchmark(JNIEnv* env,jclass,jbyteArray input,jlong duration,jint requested,jboolean arm,jint affinityMode) {
 if(env->GetArrayLength(input)!=80 || duration<=0)return nullptr;
 jbyte raw[80];env->GetByteArrayRegion(input,0,80,raw);
 uint8_t header[80];for(int i=0;i<80;i++)header[i]=uint8_t(raw[i]);
 const Midstate mid=make_midstate(header,arm);
 const unsigned hc=std::max(1u,std::thread::hardware_concurrency());
 const unsigned count=static_cast<unsigned>(std::max(1,std::min<int>(requested>0?requested:int(hc),int(hc))));
 std::atomic<uint64_t> hashes{0};
 NonceScheduler scheduler(2048);
 std::vector<std::atomic<uint64_t>> counters(count);
 for(auto& c:counters)c.store(0,std::memory_order_relaxed);
 std::vector<int> capacities;
 for(unsigned cpu=0;cpu<hc;cpu++) {
  std::ifstream f("/sys/devices/system/cpu/cpu"+std::to_string(cpu)+"/cpu_capacity");
  int cap=0;if(f>>cap)capacities.push_back(cap);else capacities.push_back(0);
 }
 std::vector<unsigned> order(hc);for(unsigned i=0;i<hc;i++)order[i]=i;
 if(affinityMode==1)std::stable_sort(order.begin(),order.end(),[&](unsigned a,unsigned b){return capacities[a]>capacities[b];});
 if(affinityMode==2)std::stable_sort(order.begin(),order.end(),[&](unsigned a,unsigned b){return capacities[a]<capacities[b];});
 const auto until=std::chrono::steady_clock::now()+std::chrono::milliseconds(duration);
 std::vector<std::thread> workers;workers.reserve(count);
 for(unsigned t=0;t<count;t++)workers.emplace_back([&,t]{
  if(affinityMode!=0 && !order.empty()) {
   cpu_set_t set;CPU_ZERO(&set);CPU_SET(order[t%order.size()],&set);
   (void)sched_setaffinity(0,sizeof(set),&set);
  }
  uint64_t local=0;
  while(std::chrono::steady_clock::now()<until) {
   const NonceRange range=scheduler.acquire();
   // A range is exclusively owned by this worker; its epoch identifies extranonce2 rollover.
   for(uint64_t n=range.start;n<uint64_t(range.start)+range.count && std::chrono::steady_clock::now()<until;n++) {
    (void)hash_header_nonce(header,uint32_t(n),mid,arm);local++;
   }
   counters[t].store(local,std::memory_order_relaxed);
  }
  counters[t].store(local,std::memory_order_relaxed);
 });
 for(auto& w:workers)w.join();
 for(const auto& c:counters)hashes.fetch_add(c.load(std::memory_order_relaxed),std::memory_order_relaxed);
 jlong values[3]={jlong(hashes.load()),jlong(count),jlong(hc)};
 jlongArray out=env->NewLongArray(3);env->SetLongArrayRegion(out,0,3,values);return out;
}
