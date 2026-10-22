// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
/* SPDX-License-Identifier: MIT */
.section .text.init
.globl _start
_start:
    csrw mie,zero
    csrw satp,zero
    .option push
    .option norelax
    la gp,__global_pointer$
    .option pop
    la sp,_stack_top
    la t0,jxl_trap
    csrw mtvec,t0
    la t0,__bss_start
    la t1,__bss_end
1:  bgeu t0,t1,2f
    sd zero,0(t0)
    addi t0,t0,8
    j 1b
2:  call main
3:  wfi
    j 3b
