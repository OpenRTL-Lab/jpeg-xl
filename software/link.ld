/* OpenRTL Lab | JPEG XL | version 1.0.0 | SPDX-License-Identifier: MIT */
/* SPDX-License-Identifier: MIT */
OUTPUT_ARCH(riscv)
ENTRY(_start)
MEMORY { ram (rwx) : ORIGIN = 0x80000000, LENGTH = 8M }
SECTIONS {
  .text : { KEEP(*(.text.init)) *(.text .text.*) } > ram
  .rodata : { *(.rodata .rodata.*) } > ram
  .data : { *(.data .data.*) *(.sdata .sdata.*) } > ram
  PROVIDE(__global_pointer$ = ADDR(.data) + 0x800);
  .tohost : ALIGN(64) { *(.tohost) } > ram
  .bss (NOLOAD) : ALIGN(8) {
    __bss_start = .; *(.bss .bss.*) *(.sbss .sbss.*) *(COMMON) . = ALIGN(8); __bss_end = .;
  } > ram
  _stack_top = ORIGIN(ram) + LENGTH(ram);
  ASSERT(__bss_end < _stack_top - 0x10000, "firmware overlaps stack")
}
