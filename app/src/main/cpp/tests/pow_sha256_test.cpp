#include "pow_sha256.h"
#include "nonce_scheduler.h"
#include <iostream>
#include <string>
#include <cstdlib>
using namespace powlab;
static std::string to_hex(const Digest& d) {
 const char* h="0123456789abcdef";std::string s;
 for(uint8_t b:d){s.push_back(h[b>>4]);s.push_back(h[b&15]);}return s;
}
int main() {
 const std::string raw="01000000" "0000000000000000000000000000000000000000000000000000000000000000"
 "3ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa4b1e5e4a"
 "29ab5f49" "ffff001d" "1dac2b7c";
 uint8_t header[80];for(int i=0;i<80;i++)header[i]=uint8_t(std::stoul(raw.substr(i*2,2),nullptr,16));
 auto mid=make_midstate(header,false);auto got=hash_header_nonce(header,2083236893u,mid,false);
 const std::string expected="6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000";
 if(to_hex(got)!=expected){std::cerr<<"FAIL genesis expected="<<expected<<" got="<<to_hex(got)<<"\n";return 1;}
 std::cout<<"PASS genesis header hash\nPASS nonce 2083236893\n";
 if(arm_sha2_available()) {
  auto armMid=make_midstate(header,true);auto armHash=hash_header_nonce(header,2083236893u,armMid,true);
  if(armMid!=mid || armHash!=got){std::cerr<<"FAIL ARM SHA2 vs portable\n";return 2;}
  std::cout<<"PASS ARM SHA2 vs portable\n";
 } else std::cout<<"SKIP ARM SHA2 vs portable (host has no ARM SHA2)\n";
 NonceScheduler scheduler(4,(1ULL<<32)-2);
 auto tail=scheduler.acquire();
 if(tail.start!=(1ULL<<32)-2 || tail.count!=2 || tail.extranonce_epoch!=0) {
  std::cerr<<"FAIL final nonce range rollover boundary\\n";return 3;
 }
 auto next=scheduler.acquire();
 if(next.start!=0 || next.count!=4 || next.extranonce_epoch!=1) {
  std::cerr<<"FAIL extranonce2 epoch rotation\\n";return 4;
 }
 std::cout<<"PASS disjoint nonce range boundary\\nPASS extranonce2 epoch rotation\\n";
 auto extra=extranonce2_for_epoch(next.extranonce_epoch,4);
 if(extra.size()!=4 || extra[0]!=0 || extra[1]!=0 || extra[2]!=0 || extra[3]!=1) {
  std::cerr<<"FAIL extranonce2 encoding\\n";return 7;
 }
 std::cout<<"PASS extranonce2 encoding\\n";
 Digest candidate{}; Digest limit{}; limit[31]=0x10;
 candidate[31]=0x11;
 if(digest_meets_target_early(candidate,limit)){std::cerr<<"FAIL early target rejection\\n";return 5;}
 candidate[31]=0x0f;
 if(!digest_meets_target_early(candidate,limit)){std::cerr<<"FAIL early target acceptance\\n";return 6;}
 std::cout<<"PASS early target word rejection\\n";
 Digest maxTarget;maxTarget.fill(0xff);
 auto batch=scan_nonce_batch(header,100,513,8,false,maxTarget,maxTarget);
 if(batch.hashes!=513 || batch.shares.size()!=513 || batch.shares.front()!=100 || batch.shares.back()!=612 ||
    batch.blocks.size()!=513 || batch.blocks.front()!=100 || batch.blocks.back()!=612) {
  std::cerr<<"FAIL native parallel batch scan coverage\\n";return 8;
 }
 Digest portableBest{}; uint32_t portableBestNonce=0; bool havePortableBest=false;
 auto lessNumeric=[](const Digest& a,const Digest& b) {
  for(int i=31;i>=0;i--) if(a[size_t(i)]!=b[size_t(i)]) return a[size_t(i)]<b[size_t(i)];
  return false;
 };
 for(uint32_t n=100;n<613;n++) {
  const Digest d=hash_header_nonce(header,n,mid,false);
  if(!havePortableBest || lessNumeric(d,portableBest)) { portableBest=d; portableBestNonce=n; havePortableBest=true; }
 }
 if(!batch.has_best || batch.best_nonce!=portableBestNonce || batch.best_digest!=portableBest) {
  std::cerr<<"FAIL native best hash differs from portable reference\\n";return 10;
 }
 if(batch.feed.empty() || batch.feed.front().nonce!=100) {
  std::cerr<<"FAIL native hash feed sampling\\n";return 11;
 }
 std::cout<<"PASS native parallel batch coverage (8 workers)\\n";
 std::cout<<"PASS best hash matches portable SHA-256d reference for 513 known nonces\\n";
 std::cout<<"PASS worker-local hash feed sampling\\n";
 Digest zeroTarget{};
 auto rejectedBatch=scan_nonce_batch(header,100,128,8,false,zeroTarget,zeroTarget);
 if(rejectedBatch.hashes!=128 || !rejectedBatch.shares.empty() || !rejectedBatch.blocks.empty()) {
  std::cerr<<"FAIL native target filtering\\n";return 9;
 }
 Digest fakeEasyTarget; fakeEasyTarget.fill(0xff);
 auto blockOnlyBatch=scan_nonce_batch(header,200,128,4,false,zeroTarget,fakeEasyTarget);
 if(blockOnlyBatch.hashes!=128 || !blockOnlyBatch.shares.empty() || blockOnlyBatch.blocks.size()!=128) {
  std::cerr<<"FAIL network-target hit detection independent of pool shares\\n";return 12;
 }
 std::cout<<"PASS native target filtering\\nPASS network-target hit detection independent of pool share target\\n";
 return 0;
}
