#pragma once
#include <atomic>
#include <cstdint>
#include <mutex>
namespace powlab {
struct NonceRange { uint64_t start; uint32_t count; uint64_t extranonce_epoch; };
class NonceScheduler {
 public:
  explicit NonceScheduler(uint32_t chunk=4096): chunk_(chunk), next_(0), epoch_(0) {}
  NonceRange acquire() {
   uint64_t start=next_.fetch_add(chunk_,std::memory_order_relaxed);
   if(start < (1ULL<<32)) {
    uint32_t count=static_cast<uint32_t>(((1ULL<<32)-start)<chunk_?((1ULL<<32)-start):chunk_);
    return {start,count,epoch_.load(std::memory_order_relaxed)};
   }
   std::lock_guard<std::mutex> lock(rollover_);
   if(next_.load(std::memory_order_relaxed) >= (1ULL<<32)+chunk_) {
    next_.store(chunk_,std::memory_order_relaxed);
    epoch_.fetch_add(1,std::memory_order_relaxed);
   }
   return {0,0,epoch_.load(std::memory_order_relaxed)};
  }
 private:
  uint32_t chunk_; std::atomic<uint64_t> next_; std::atomic<uint64_t> epoch_; std::mutex rollover_;
};
}
