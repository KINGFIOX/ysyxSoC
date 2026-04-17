#include "nvboard/nvboard_bind.h"

#include "VNPCSoC.h"
#include "nvboard/pins.h"

namespace npc {

using namespace nvboard;

namespace {

// Attached board used by the ns16550 software model.  Set by
// VerilatorCpu::VerilatorCpu (via nvboard_uart_attach) and cleared in its
// destructor.
nvboard::Board* g_uart_board = nullptr;

}  // namespace

static void bind_all_pins(nvboard::Board& board, VNPCSoC* top) {
  board.BindPin(&top->externalPins_gpio_out,
                {LD15, LD14, LD13, LD12, LD11, LD10, LD9, LD8,
                 LD7,  LD6,  LD5,  LD4,  LD3,  LD2,  LD1, LD0});

  board.BindPin(&top->externalPins_gpio_in,
                {SW15, SW14, SW13, SW12, SW11, SW10, SW9, SW8,
                 SW7,  SW6,  SW5,  SW4,  SW3,  SW2,  SW1, SW0});

  board.BindPin(&top->externalPins_gpio_seg_0,
                {SEG0A, SEG0B, SEG0C, SEG0D, SEG0E, SEG0F, SEG0G, DEC0P});
  board.BindPin(&top->externalPins_gpio_seg_1,
                {SEG1A, SEG1B, SEG1C, SEG1D, SEG1E, SEG1F, SEG1G, DEC1P});
  board.BindPin(&top->externalPins_gpio_seg_2,
                {SEG2A, SEG2B, SEG2C, SEG2D, SEG2E, SEG2F, SEG2G, DEC2P});
  board.BindPin(&top->externalPins_gpio_seg_3,
                {SEG3A, SEG3B, SEG3C, SEG3D, SEG3E, SEG3F, SEG3G, DEC3P});
  board.BindPin(&top->externalPins_gpio_seg_4,
                {SEG4A, SEG4B, SEG4C, SEG4D, SEG4E, SEG4F, SEG4G, DEC4P});
  board.BindPin(&top->externalPins_gpio_seg_5,
                {SEG5A, SEG5B, SEG5C, SEG5D, SEG5E, SEG5F, SEG5G, DEC5P});
  board.BindPin(&top->externalPins_gpio_seg_6,
                {SEG6A, SEG6B, SEG6C, SEG6D, SEG6E, SEG6F, SEG6G, DEC6P});
  board.BindPin(&top->externalPins_gpio_seg_7,
                {SEG7A, SEG7B, SEG7C, SEG7D, SEG7E, SEG7F, SEG7G, DEC7P});

  board.BindPin(&top->externalPins_ps2_clk,  {PS2_CLK});
  board.BindPin(&top->externalPins_ps2_data, {PS2_DAT});

  board.BindPin(&top->externalPins_vga_vsync, {VGA_VSYNC});
  board.BindPin(&top->externalPins_vga_hsync, {VGA_HSYNC});
  board.BindPin(&top->externalPins_vga_valid, {VGA_BLANK_N});
  board.BindPin(&top->externalPins_vga_r,
                {VGA_R7, VGA_R6, VGA_R5, VGA_R4, VGA_R3, VGA_R2, VGA_R1, VGA_R0});
  board.BindPin(&top->externalPins_vga_g,
                {VGA_G7, VGA_G6, VGA_G5, VGA_G4, VGA_G3, VGA_G2, VGA_G1, VGA_G0});
  board.BindPin(&top->externalPins_vga_b,
                {VGA_B7, VGA_B6, VGA_B5, VGA_B4, VGA_B3, VGA_B2, VGA_B1, VGA_B0});

  // UART pins are intentionally not bound here: the serial TX/RX lines on the
  // SoC are stubs (see npc/src/scala/device/Uart16550.scala) and character
  // transport happens byte-wise via DPI-C instead.
}

std::unique_ptr<nvboard::Board> nvboard_create(VNPCSoC* top,
                                               int vga_clk_cycle) {
  auto board = nvboard::Board::Create(vga_clk_cycle);
  bind_all_pins(*board, top);
  return board;
}

void nvboard_uart_attach(nvboard::Board* board) { g_uart_board = board; }

void nvboard_uart_putchar(uint8_t ch) {
  if (g_uart_board == nullptr) return;
  g_uart_board->uart().Putchar(ch);
}

bool nvboard_uart_try_getchar(uint8_t* out) {
  if (g_uart_board == nullptr) return false;
  auto& u = g_uart_board->uart();
  if (!u.Available()) return false;
  if (out != nullptr) *out = u.Getchar();
  return true;
}

}  // namespace npc
