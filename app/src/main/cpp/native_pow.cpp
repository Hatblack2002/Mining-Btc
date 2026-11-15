#include <jni.h>
#include <atomic>
#include <chrono>
#include <thread>
#include <vector>
#include <algorithm>
#include "pow_sha256.h"
#include "nonce_scheduler.h"
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
Java_com_vertil_miningbtc_NativePowEngine_nativeBenchmark(JNIEnv* env,jclass,jbyteArray input,jlong duration,jint requested,jboolean arm) {
 if(env->GetArrayLength(input)!=80 || duration<=0)return nullptr;
 jbyte raw[80];env->GetByteArrayRegion(input,0,80,raw);
 uint8_t header[80];for(int i=0;i<80;i++)header[i]=uint8_t(raw[i]);
 const Midstate mid=make_midstate(header,arm);
 const unsigned hc=std::max(1u,std::thread::hardware_concurrency());
 const unsigned count=std::max(1,std::min<int>(requested>0?requested:int(hc),int(hc)));
 std::atomic<uint64_t> hashes{0};std::atomic<uint64_t> next{0};
 const auto until=std::chrono::steady_clock::now()+std::chrono::milliseconds(duration);
 std::vector<std::thread> workers;workers.reserve(count);
 for(unsigned t=0;t<count;t++)workers.emplace_back([&,t]{
  uint64_t local=0;
  while(std::chrono::steady_clock::now()<until) {
   uint64_t start=next.fetch_add(1024,std::memory_order_relaxed);
   if(start>0xffffffffULL)break;
   uint64_t end=std::min<uint64_t>(start+1024,1ULL<<32);
   for(uint64_t n=start;n<end && std::chrono::steady_clock::now()<until;n++) {
    (void)hash_header_nonce(header,uint32_t(n),mid,arm);local++;
   }
  }
  hashes.fetch_add(local,std::memory_order_relaxed);
 });
 for(auto& w:workers)w.join();
 jlong values[3]={jlong(hashes.load()),jlong(count),jlong(hc)};
 jlongArray out=env->NewLongArray(3);env->SetLongArrayRegion(out,0,3,values);return out;
}
