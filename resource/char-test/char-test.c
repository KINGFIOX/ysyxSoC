#define UART_BASE 0x10000000

// UART 16550 寄存器定义
#define UART_THR (UART_BASE + 0) // Transmit Holding Register (DLAB=0)
#define UART_RBR (UART_BASE + 0) // Receiver Buffer Register (DLAB=0)
#define UART_DLL (UART_BASE + 0) // Divisor Latch Low (DLAB=1)
#define UART_DLM (UART_BASE + 1) // Divisor Latch High (DLAB=1)
#define UART_IER (UART_BASE + 1) // Interrupt Enable Register (DLAB=0)
#define UART_FCR (UART_BASE + 2) // FIFO Control Register (写)
#define UART_LCR (UART_BASE + 3) // Line Control Register
#define UART_LSR (UART_BASE + 5) // Line Status Register

// LCR 位定义
#define LCR_DLAB 0x80 // Divisor Latch Access Bit
#define LCR_8N1  0x03 // 8 data bits, no parity, 1 stop bit

// FCR 位定义
#define FCR_FIFO_ENABLE 0x01 // FIFO Enable
#define FCR_RX_RESET    0x02 // Receiver FIFO Reset
#define FCR_TX_RESET    0x04 // Transmitter FIFO Reset

// LSR 位定义
#define LSR_DR   0x01 // Data Ready
#define LSR_THRE 0x20 // Transmitter Holding Register Empty
#define LSR_TEMT 0x40 // Transmitter Empty

static void uart_init(void) {
  volatile char *lcr = (volatile char *)UART_LCR;
  volatile char *dll = (volatile char *)UART_DLL;
  volatile char *dlm = (volatile char *)UART_DLM;
  volatile char *fcr = (volatile char *)UART_FCR;
  volatile char *ier = (volatile char *)UART_IER;

  // 1. 禁用所有中断
  *ier = 0x00;

  // 2. 使能 DLAB 以设置波特率
  *lcr = LCR_DLAB;

  // 3. 设置除数为1 (最快波特率: clk / 16)
  *dll = 0x00; // Divisor Latch Low
  *dlm = 0x00; // Divisor Latch High

  // 4. 禁用 DLAB，设置数据格式: 8N1 (8数据位, 无奇偶校验, 1停止位)
  *lcr = LCR_8N1;

  // 5. 使能并复位 FIFO
  *fcr = FCR_FIFO_ENABLE | FCR_RX_RESET | FCR_TX_RESET;
}

static void uart_putchar(char c) {
  volatile char *thr = (volatile char *)UART_THR;
  volatile char *lsr = (volatile char *)UART_LSR;

  // 等待发送 FIFO 有空间 (THRE=1 表示可以写入)
  while ((*lsr & LSR_THRE) == 0)
    ;

  *thr = c;
}

void uart_test(void) {
  // 初始化 UART
  uart_init();

  // 发送数据
  while (1) {
    uart_putchar('A');
  }
}