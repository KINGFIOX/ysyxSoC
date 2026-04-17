#include "tracer/ftrace.h"

#include <fstream>
#include <vector>

#include "absl/log/log.h"
#include "absl/strings/str_format.h"
#include "elfio/elfio.hpp"

namespace npc {

FuncTracer::FuncTracer(const std::filesystem::path& elf_path)
    : ring_buf(kFtraceCapacity) {
  ELFIO::elfio reader;
  if (!reader.load(elf_path.string())) {
    LOG(ERROR) << "Failed to load ELF: " << elf_path;
    return;
  }

  for (const auto& section : reader.sections) {
    if (section->get_type() != ELFIO::SHT_SYMTAB &&
        section->get_type() != ELFIO::SHT_DYNSYM) {
      continue;
    }
    ELFIO::symbol_section_accessor symbols(reader, section.get());
    for (ELFIO::Elf_Xword i = 0; i < symbols.get_symbols_num(); ++i) {
      std::string name;
      ELFIO::Elf64_Addr value = 0;
      ELFIO::Elf_Xword size = 0;
      unsigned char bind = 0, type = 0, other = 0;
      ELFIO::Elf_Half section_index = 0;
      symbols.get_symbol(i, name, value, size, bind, type, section_index,
                         other);
      if (type == ELFIO::STT_FUNC && size > 0 && !name.empty()) {
        symtab[value] = name;
      }
    }
  }
}

void FuncTracer::push_call(uint64_t pc, uint64_t dnpc,
                            const std::string& disasm) {
  auto it = symtab.find(dnpc);
  std::string func_name =
      (it != symtab.end()) ? it->second
                           : absl::StrFormat("0x%016x", dnpc);
  ring_buf.push(FTraceEntry(pc, dnpc, depth_, FuncType::kCall,
                             std::move(func_name), disasm));
  ++depth_;
}

void FuncTracer::push_ret(uint64_t pc, uint64_t dnpc,
                           const std::string& disasm) {
  if (depth_ > 0) --depth_;
  ring_buf.push(
      FTraceEntry(pc, dnpc, depth_, FuncType::kRet, "", disasm));
}

}  // namespace npc
