#define UART_BASE 0x10000000
#define UART_THR (UART_BASE + 0) // Transmit Holding Register (DLAB=0)

__attribute__((naked))
void _start() {
  volatile char *thr = (volatile char *)UART_THR;
  // 5. Send data
  while (1) {
    // // Wait until transmitter holding register is empty
    // while ((*lsr & LSR_THRE) == 0);
    *thr = 'A';
  }
}