#include "pow_sha256.h"
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
 return 0;
}
