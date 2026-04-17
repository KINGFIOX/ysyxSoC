#ifndef NPC_NVBOARD_NVBOARD_BIND_H_
#define NPC_NVBOARD_NVBOARD_BIND_H_

#include <cstdint>
#include <memory>

#include "nvboard/nvboard.h"

class VNPCSoC;

namespace npc {

std::unique_ptr<nvboard::Board> nvboard_create(VNPCSoC* top,
                                               int vga_clk_cycle);

// Wire the supplied board as the UART sink/source used by the ns16550 DPI
// model.  Pass nullptr on teardown.  Not thread-safe; intended to be called
// from VerilatorCpu's ctor/dtor only.
void nvboard_uart_attach(nvboard::Board* board);

// Forward a byte from the software ns16550 TX path to the NVBoard UART widget.
// No-op if no board has been attached.
void nvboard_uart_putchar(uint8_t ch);

// Pull one byte out of the NVBoard UART RX queue, if any.  Returns false when
// the queue is empty (or no board attached).
bool nvboard_uart_try_getchar(uint8_t* out);

}  // namespace npc

#endif  // NPC_NVBOARD_NVBOARD_BIND_H_
