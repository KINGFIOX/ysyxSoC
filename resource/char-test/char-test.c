#define UART_BASE 0x10000000
#define UART_THR (UART_BASE + 0) // Transmit Holding Register (DLAB=0)
#define UART_DLL (UART_BASE + 0) // Divisor Latch Low (DLAB=1)
#define UART_DLM (UART_BASE + 1) // Divisor Latch High (DLAB=1)
#define UART_LCR (UART_BASE + 3) // Line Control Register

__attribute__((naked))
void _start() {
  volatile char *lcr = (volatile char *)UART_LCR;
  volatile char *dll = (volatile char *)UART_DLL;
  volatile char *dlm = (volatile char *)UART_DLM;
  volatile char *thr = (volatile char *)UART_THR;

  // Set DLAB=1 to access divisor latch
  *lcr = 0x80;
  // Set baud rate divisor (e.g., 1 for fastest simulation)
  *dll = 1;
  *dlm = 0;
  // Clear DLAB, set 8N1 format
  *lcr = 0x03;

  while (1) {
    *thr = 'A';
  }
}