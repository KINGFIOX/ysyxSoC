.section entry, "ax"
.globl _start
.type _start, @function

_start:
    mv s0, zero
    la sp, _stack_pointer
    call uart_test
    ebreak
.size _start, . - _start