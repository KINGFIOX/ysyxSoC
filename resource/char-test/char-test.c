#define UART_BASE 0x10000000L
#define UART_TX   0

// 去掉换行也能输出: verilator --autoflush

__attribute__((naked)) // disable stack(sram)
void _start(void) {
  while (1) {
    *(volatile char *)(UART_BASE + UART_TX) = 'A';
    // *(volatile char *)(UART_BASE + UART_TX) = '\n';
  }
}
