// Software NS16550-compatible UART model reached via DPI-C from the fake
// Chisel UART module (see npc/src/scala/device/Uart16550.scala).
//
// Ported from spike's npc/third_party/spike/riscv/ns16550.cc with two changes:
//   * Byte transport uses NVBoard's UART widget instead of the host terminal.
//   * Spike's interrupt controller callout is replaced by a level latched in
//     this translation unit and returned to RTL through `uart_tick`.
//
// The DPI entry points match the SystemVerilog imports in UartDpiHelper.sv.

#include <cstdint>
#include <queue>

#include "nvboard/nvboard_bind.h"

namespace {

// ---------- Register offsets / bit fields (NS16550 standard) ---------------

constexpr uint8_t UART_RX = 0;
constexpr uint8_t UART_TX = 0;

constexpr uint8_t UART_IER          = 1;
constexpr uint8_t UART_IER_THRI     = 0x02;
constexpr uint8_t UART_IER_RDI      = 0x01;

constexpr uint8_t UART_IIR          = 2;
constexpr uint8_t UART_IIR_NO_INT   = 0x01;
constexpr uint8_t UART_IIR_THRI     = 0x02;
constexpr uint8_t UART_IIR_RDI      = 0x04;
constexpr uint8_t UART_IIR_TYPE_BITS = 0xc0;

constexpr uint8_t UART_FCR              = 2;
constexpr uint8_t UART_FCR_ENABLE_FIFO  = 0x01;
constexpr uint8_t UART_FCR_CLEAR_RCVR   = 0x02;
constexpr uint8_t UART_FCR_CLEAR_XMIT   = 0x04;

constexpr uint8_t UART_LCR          = 3;
constexpr uint8_t UART_LCR_DLAB     = 0x80;
constexpr uint8_t UART_LCR_SBC      = 0x40;

constexpr uint8_t UART_MCR          = 4;
constexpr uint8_t UART_MCR_LOOP     = 0x10;
constexpr uint8_t UART_MCR_OUT2     = 0x08;

constexpr uint8_t UART_LSR          = 5;
constexpr uint8_t UART_LSR_TEMT     = 0x40;
constexpr uint8_t UART_LSR_THRE     = 0x20;
constexpr uint8_t UART_LSR_BI       = 0x10;
constexpr uint8_t UART_LSR_DR       = 0x01;

constexpr uint8_t UART_MSR          = 6;
constexpr uint8_t UART_MSR_DCD      = 0x80;
constexpr uint8_t UART_MSR_DSR      = 0x20;
constexpr uint8_t UART_MSR_CTS      = 0x10;

constexpr uint8_t UART_SCR          = 7;

constexpr size_t kUartQueueSize = 64;

// Throttle how often we poll nvboard for a fresh RX byte when the host has
// been idle for a while.  Mirrors spike's backoff so we are not polling the
// SDL queue every clock edge.
constexpr int kMaxBackoff = 64;

class Ns16550 {
 public:
  Ns16550() { Reset(); }

  void Reset() {
    ier_ = 0;
    iir_ = UART_IIR_NO_INT;
    fcr_ = 0;
    lcr_ = 0;
    lsr_ = UART_LSR_TEMT | UART_LSR_THRE;
    msr_ = UART_MSR_DCD | UART_MSR_DSR | UART_MSR_CTS;
    dll_ = 0x0C;
    dlm_ = 0;
    mcr_ = UART_MCR_OUT2;
    scr_ = 0;
    backoff_counter_ = 0;
    int_level_ = 0;
    while (!rx_queue_.empty()) rx_queue_.pop();
  }

  void Store(uint8_t addr, uint8_t val) {
    bool update = false;
    switch (addr & 7) {
      case UART_TX:
        update = true;
        if (lcr_ & UART_LCR_DLAB) {
          dll_ = val;
          break;
        }
        if (mcr_ & UART_MCR_LOOP) {
          if (rx_queue_.size() < kUartQueueSize) {
            rx_queue_.push(val);
            lsr_ |= UART_LSR_DR;
          }
          break;
        }
        TxByte(val);
        break;
      case UART_IER:
        if (!(lcr_ & UART_LCR_DLAB)) {
          ier_ = val & 0x0f;
        } else {
          dlm_ = val;
        }
        update = true;
        break;
      case UART_FCR:
        fcr_ = val;
        update = true;
        break;
      case UART_LCR:
        lcr_ = val;
        update = true;
        break;
      case UART_MCR:
        mcr_ = val;
        update = true;
        break;
      case UART_LSR:
        break;
      case UART_MSR:
        break;
      case UART_SCR:
        scr_ = val;
        break;
      default:
        break;
    }
    if (update) UpdateInterrupt();
  }

  uint8_t Load(uint8_t addr) {
    uint8_t val = 0;
    bool update = false;
    switch (addr & 7) {
      case UART_RX:
        if (lcr_ & UART_LCR_DLAB) {
          val = dll_;
        } else {
          val = RxByte();
        }
        update = true;
        break;
      case UART_IER:
        val = (lcr_ & UART_LCR_DLAB) ? dlm_ : ier_;
        break;
      case UART_IIR:
        val = iir_ | UART_IIR_TYPE_BITS;
        break;
      case UART_LCR:
        val = lcr_;
        break;
      case UART_MCR:
        val = mcr_;
        break;
      case UART_LSR:
        val = lsr_;
        break;
      case UART_MSR:
        val = msr_;
        break;
      case UART_SCR:
        val = scr_;
        break;
      default:
        break;
    }
    if (update) UpdateInterrupt();
    return val;
  }

  void Tick() {
    if (!(fcr_ & UART_FCR_ENABLE_FIFO) || (mcr_ & UART_MCR_LOOP) ||
        rx_queue_.size() >= kUartQueueSize) {
      return;
    }
    if (backoff_counter_ > 0 && backoff_counter_ < kMaxBackoff) {
      ++backoff_counter_;
      return;
    }

    uint8_t ch = 0;
    if (!npc::nvboard_uart_try_getchar(&ch)) {
      backoff_counter_ = 1;
      return;
    }
    backoff_counter_ = 0;

    rx_queue_.push(ch);
    lsr_ |= UART_LSR_DR;
    UpdateInterrupt();
  }

  uint8_t int_level() const { return int_level_; }

 private:
  void TxByte(uint8_t val) {
    lsr_ |= UART_LSR_TEMT | UART_LSR_THRE;
    npc::nvboard_uart_putchar(val);
  }

  uint8_t RxByte() {
    if (rx_queue_.empty()) {
      lsr_ &= ~UART_LSR_DR;
      return 0;
    }
    if (lsr_ & UART_LSR_BI) {
      lsr_ &= ~UART_LSR_BI;
      return 0;
    }
    uint8_t ret = rx_queue_.front();
    rx_queue_.pop();
    if (rx_queue_.empty()) lsr_ &= ~UART_LSR_DR;
    return ret;
  }

  void UpdateInterrupt() {
    uint8_t interrupts = 0;

    if (lcr_ & UART_FCR_CLEAR_RCVR) {
      lcr_ &= ~UART_FCR_CLEAR_RCVR;
      while (!rx_queue_.empty()) rx_queue_.pop();
      lsr_ &= ~UART_LSR_DR;
    }
    if (lcr_ & UART_FCR_CLEAR_XMIT) {
      lcr_ &= ~UART_FCR_CLEAR_XMIT;
      lsr_ |= UART_LSR_TEMT | UART_LSR_THRE;
    }

    if ((ier_ & UART_IER_RDI) && (lsr_ & UART_LSR_DR)) {
      interrupts |= UART_IIR_RDI;
    }
    if ((ier_ & UART_IER_THRI) && (lsr_ & UART_LSR_TEMT)) {
      interrupts |= UART_IIR_THRI;
    }

    if (!interrupts) {
      iir_ = UART_IIR_NO_INT;
      int_level_ = 0;
    } else {
      iir_ = interrupts;
      int_level_ = 1;
    }

    if (!(ier_ & UART_IER_THRI)) {
      lsr_ |= UART_LSR_TEMT | UART_LSR_THRE;
    }
  }

  std::queue<uint8_t> rx_queue_;
  uint8_t dll_ = 0;
  uint8_t dlm_ = 0;
  uint8_t iir_ = UART_IIR_NO_INT;
  uint8_t ier_ = 0;
  uint8_t fcr_ = 0;
  uint8_t lcr_ = 0;
  uint8_t mcr_ = 0;
  uint8_t lsr_ = 0;
  uint8_t msr_ = 0;
  uint8_t scr_ = 0;
  int backoff_counter_ = 0;
  uint8_t int_level_ = 0;
};

Ns16550 g_uart;

}  // namespace

extern "C" {

// Called once per APB transfer.  `we=1` -> register write; `we=0` -> register
// read.  `addr` carries the 3 LSBs of the byte-level offset (bit 4..7 are
// unused but reserved for future expansion).
void uart_req(unsigned char we, signed char addr, signed char wdata,
              signed char* rdata) {
  if (we) {
    g_uart.Store(static_cast<uint8_t>(addr), static_cast<uint8_t>(wdata));
    if (rdata) *rdata = 0;
  } else {
    uint8_t v = g_uart.Load(static_cast<uint8_t>(addr));
    if (rdata) *rdata = static_cast<signed char>(v);
  }
}

// Called once per rising clock edge: advance RX polling + return current
// interrupt level.
void uart_tick(unsigned char* int_o) {
  g_uart.Tick();
  if (int_o) *int_o = g_uart.int_level();
}

}  // extern "C"
