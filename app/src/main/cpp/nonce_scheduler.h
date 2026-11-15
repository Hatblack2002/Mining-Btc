#pragma once
#include <atomic>
#include <cstdint>
#include <mutex>
namespace powlab {
struct NonceRange { uint64_t start; uint32_t count; uint64_t extranonce_epoch; };
class NonceScheduler {
 public:
  explicit NonceScheduler(uint32_t chunk=4096,uint64_t initial=0): chunk_(chunk), next_(initial), epoch_(0) {}
  NonceRange acquire() {
   for (;;) {
    uint64_t start=next_.load(std::memory_order_relaxed);
    if(start >= (1ULL<<32)) {
     std::lock_guard<std::mutex> lock(rollover_);
     if(next_.load(std::memory_order_relaxed) >= (1ULL<<32)) {
      next_.store(0,std::memory_order_relaxed);
      epoch_.fetch_add(1,std::memory_order_relaxed);
     }
     continue;
    }
    uint64_t end=start+chunk_;
    if(next_.compare_exchange_weak(start,end,std::memory_order_relaxed)) {
     uint32_t count=static_cast<uint32_t>((end>(1ULL<<32))?((1ULL<<32)-start):chunk_);
     return {start,count,epoch_.load(std::memory_order_relaxed)};
    }
   }
  }
 private:
  uint32_t chunk_; std::atomic<uint64_t> next_; std::atomic<uint64_t> epoch_; std::mutex rollover_;
};
}
