#define UART_BASE 0x10000000
#define UART_RBR (UART_BASE + 0) // Receiver Buffer Register (DLAB=0)
#define UART_THR (UART_BASE + 0) // Transmit Holding Register (DLAB=0)
#define UART_DLL (UART_BASE + 0) // Divisor Latch Low (DLAB=1)
#define UART_DLM (UART_BASE + 1) // Divisor Latch High (DLAB=1)
#define UART_IER (UART_BASE + 1) // Interrupt Enable Register (DLAB=0)
#define UART_FCR (UART_BASE + 2) // FIFO Control Register (Write Only)
#define UART_LCR (UART_BASE + 3) // Line Control Register
#define UART_LSR (UART_BASE + 5) // Line Status Register

// LSR bit definitions
#define LSR_THRE (1 << 5) // Transmitter Holding Register Empty
#define LSR_TEMT (1 << 6) // Transmitter Empty

// UART 时钟频率，编译时可通过 -DUART_CLOCK_FREQ=xxx 覆盖
// 仿真常用 50MHz，流片可能 100MHz~1GHz
#ifndef UART_CLOCK_FREQ
#define UART_CLOCK_FREQ 50000000
#endif
#define UART_BAUD_RATE 115200
#define UART_DIVISOR ((UART_CLOCK_FREQ) / (16 * (UART_BAUD_RATE)))

__attribute__((naked))
void _start() {
  volatile char *lcr = (volatile char *)UART_LCR;
  volatile char *dll = (volatile char *)UART_DLL;
  volatile char *dlm = (volatile char *)UART_DLM;
  volatile char *fcr = (volatile char *)UART_FCR;
  volatile char *thr = (volatile char *)UART_THR;
  volatile char *lsr = (volatile char *)UART_LSR;

  // 1. Set DLAB=1 to access divisor latch registers
  *lcr = 0x80;
  
  // 2. Set baud rate divisor for 115200
  // Divisor = UART_CLOCK_FREQ / (16 * Baud_Rate)
  *dll = (char)(UART_DIVISOR & 0xFF);
  *dlm = (char)((UART_DIVISOR >> 8) & 0xFF);
  
  // 3. Set 8N1 format (8 data bits, No parity, 1 stop bit) and clear DLAB
  *lcr = 0x03;
  
  // 4. Enable and reset FIFOs
  *fcr = 0x07; // Enable FIFO, clear RX/TX FIFOs
  
  // 5. Send data
  while (1) {
    // // Wait until transmitter holding register is empty
    while ((*lsr & LSR_THRE) == 0);
    *thr = 'A';
  }
}