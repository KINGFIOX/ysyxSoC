#ifndef NPC_CPU_SPIKE_CORE_H_
#define NPC_CPU_SPIKE_CORE_H_

#include <cstdint>
#include <string>
#include <utility>

namespace npc {

struct SpikeResult {
  uint64_t value = 0;
  bool ok = true;
  std::string error;

  static SpikeResult Ok(uint64_t v = 0) { return {v, true, {}}; }
  static SpikeResult Err(std::string msg) { return {0, false, std::move(msg)}; }
};

// Low-level Spike wrapper that does NOT depend on absl.
// Exists solely to keep Spike's softfloat headers out of TUs that include
// ARM NEON headers (pulled in transitively by absl on aarch64).
class SpikeCore {
 public:
  SpikeCore(const uint8_t* flash_data, size_t flash_size);
  ~SpikeCore();

  SpikeCore(const SpikeCore&) = delete;
  SpikeCore& operator=(const SpikeCore&) = delete;

  uint64_t pc() const;
  void set_pc(uint64_t value);

  uint64_t gpr(int index) const;
  void set_gpr(int index, uint64_t value);

  uint64_t get_csr(uint16_t id) const;
  void put_csr(uint16_t id, uint64_t value);

  SpikeResult mem_load(uint64_t addr, uint8_t width) const;
  SpikeResult mem_store(uint64_t addr, uint64_t value, uint8_t width);

  SpikeResult step();
  void reset();

  // Returns (mnemonic, full_disassembly).
  std::pair<std::string, std::string> disasm(uint32_t inst) const;

 private:
  struct Impl;
  Impl* impl_;
};

}  // namespace npc

#endif  // NPC_CPU_SPIKE_CORE_H_
