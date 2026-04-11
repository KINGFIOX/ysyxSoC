#ifndef NPC_NVBOARD_NVBOARD_BIND_H_
#define NPC_NVBOARD_NVBOARD_BIND_H_

#include <memory>

#include "nvboard/nvboard.h"

class VNPCSoC;

namespace npc {

std::unique_ptr<nvboard::Board> nvboard_create(VNPCSoC* top,
                                               int vga_clk_cycle);

}  // namespace npc

#endif  // NPC_NVBOARD_NVBOARD_BIND_H_
