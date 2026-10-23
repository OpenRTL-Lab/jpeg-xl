<!-- OpenRTL Lab | JPEG XL | version 1.0.0 | SPDX-License-Identifier: MIT -->
# Write, build and debug a driver

[简体中文](driver.zh-CN.md)

Finish [digital SoC wiring](chipyard.md) first. The executable reference is `software/jxl_mmio.h` plus bare-metal `software/jxl_benchmark.c`, not a Linux kernel driver. Start with exclusive single-producer polling before adding multiple jobs and interrupts. Host fake-register tests are not device execution.

## 1. Registers and the minimal state machine

The default 4 KiB CSR aperture is at `0x10040000`; use aligned 32-bit accesses, not byte writes, 64-bit MMIO or optimizable ordinary pointers. Probe ID `0x4a584c31` and ABI `0x00010000` before any DMA submission.

| Offset | Meaning |
| --- | --- |
| `0x00/0x04` | Read-only ID/ABI |
| `0x08` | FLAGS: bit0 submit-ready, bit1 result-valid, bit2 cancel-ACK-valid, bit3 quiescent |
| `0x0c` | Outstanding count; do not infer hardware state only from software counters |
| `0x10/0x14` | IRQ enable/read-only pending; bit0 result, bit1 cancel ACK |
| `0x20..0x48` | Tag, dimensions/channels, low/high source/destination, spans and output limit |
| `0x4c` | Write1 SUBMIT; shadow descriptor transfers at handshake |
| `0x50/0x54` | Cancel tag/write1 request |
| `0x58/0x5c` | Write1 result/cancel-ACK POP |
| `0x60..0x90` | Stable result snapshot: tag/status/length/fault address, three 64-bit cycles, request counts |
| `0x98/0x9c` | Cancel-ACK tag/found |

See [the header](../software/jxl_mmio.h) for exact fields and widths. Read low/high 32-bit halves before POP: the snapshot is stable, unlike a free-running counter. Check FLAGS valid before result registers, or accesses may be denied. Copy the entire result before POP.

Native status codes are 0 success, 1 invalid command, 2 overlapping regions, 3 duplicate tag, 4 read fault, 5 write fault, 6 output limit, 7 cancelled. `jxl_try_complete` returning `JXL_OK` only means a result was retrieved, **not** that `completion.status` is zero.

## 2. DMA buffers and ownership

Pixels are interleaved 8-bit Gray/GrayA/RGB/RGBA without stride. Source/destination DMA addresses and spans must be 64-byte aligned; source span covers `roundUp(width*height*channels,64)`. Round output allocation up and provide sufficient capacity; compression is not guaranteed to shrink input. The platform permits `[0x80000000,0x180000000)`. Program DMA bus addresses, not CPU/user virtual pointers.

CPU fills pixels → platform DMA synchronization → descriptor writes → fence → SUBMIT → poll/IRQ → read stable snapshot and POP → synchronize for CPU → check status/length → consume output. Input/output and all retained jobs' regions must be disjoint. Input padding needs allocated backing memory; valid output ends at `output_bytes`, followed by zero padding to 64-byte alignment.

RISC-V `fence iorw,iorw` orders accesses; it does **not** clean/invalidate a noncoherent cache. The example relies on coherent SBUS. Add proper maintenance or DMA allocations on other platforms. `volatile` is not coherency.

## 3. Build a single-job polling example

`software/examples/poll_once.c` exports `jxl_example_poll_once`. Inject the three `jxl_io` callbacks and your allocated buffers. It requires exclusive/quiescent ownership, disables IRQs, then submits/polls nonblockingly. Timeout returns `JXL_EXAMPLE_STILL_OWNED`: buffers remain allocated, with no automatic reset/free.

```sh
mkdir -p build/driver
cc -std=c11 -Wall -Wextra -Werror -Isoftware \
  -c software/examples/poll_once.c -o build/driver/poll-host.o
python3 tools/check_host.py --out build/driver/host
python3 tools/check_driver_example.py --out build/driver/example
riscv64-unknown-elf-gcc -std=c11 -O2 -Wall -Wextra -Werror \
  -march=rv64imafdc -mabi=lp64d -mcmodel=medany -ffreestanding \
  -Isoftware -c software/examples/poll_once.c -o build/driver/poll-rv64.o
```

Bare-metal callbacks/job example, only after actually reserving these addresses on your platform:

```c
struct jxl_io io = { (void *)(uintptr_t)JXL_MMIO_BASE,
                    jxl_phys_read, jxl_phys_write, jxl_phys_fence };
struct jxl_job job = { .tag = 1, .width = 256, .height = 256, .channels = 3,
  .source = UINT64_C(0x90000000), .destination = UINT64_C(0x90080000),
  .source_span = 196608, .destination_span = 524288, .output_limit = 524288 };
/* Fill source pixels and synchronize DMA before jxl_try_submit(&io, &job).
 * Reap by tag with jxl_try_complete; check completion.status before length.
 * Do not release either buffer on timeout or a cancel acknowledgment. */
```

Host protocol checks must print `JXL_MMIO_HOST_CONTRACT_PASS` and `JXL_CPU_BUFFERS_PASS`; they never access physical MMIO. Cross-compile RV64 objects separately; a host object is not a loadable target ELF.

## 4. Build the complete test ELF

```sh
riscv64-unknown-elf-gcc -std=c11 -O2 -Wall -Wextra -Werror \
  -march=rv64imafdc -mabi=lp64d -mcmodel=medany \
  -ffreestanding -fno-builtin -nostdlib -nostartfiles \
  -Isoftware -Itests/fixtures -Wl,-T,software/link.ld \
  -Wl,--build-id=none software/start.S software/jxl_benchmark.c \
  -o build/driver/jxl-benchmark.elf
riscv64-unknown-elf-readelf -h -l build/driver/jxl-benchmark.elf
riscv64-unknown-elf-nm build/driver/jxl-benchmark.elf
```

Require ELF64/RISC-V and `_start`, `main`, `jxl_trap`. The linker places firmware in 8 MiB from `0x80000000`, reserving 64 KiB stack. Tests additionally use 32 slots spaced 2 MiB from `0x90000000`, destination at source `+0x80000`. Your loader must reserve real backing memory. Startup assumes one hart, M-mode, `satp=0`, matching FPU/D support and trap setup; port it for other execution modes.

Default SiFive-compatible UART `0x10020000` and 50 MHz cycle scaling are digital platform assumptions, not arbitrary Chipyard defaults. Read DTS and adapt UART/frequency/memory before execution. `-DJXL_SOC_SIM -DJXL_FIXTURE=0` selects HTIF signatures instead of UART, requiring your own FESVR/boot environment. Defining those macros does not run the program.

Execute fixture0's 1+32 tasks first, checking complete files/padding, then all 32 images/1056 tasks. Require `JXL_BOARD_PASS` or `JXL_SOC_PASS` plus all task records. A trap, FAIL or startup-only UART is not PASS. Your platform supplies loading/JTAG/programming; this repository does not distribute physical board configuration.

The `JXL_SOC_SIM` branch also checks PLIC interrupt 5 at fixed PLIC addresses. Update those constants from **your generated DTS** before using that branch; the generic one-Rocket example does not guarantee IRQ 5. This is separate from the polling example, which disables IRQs.

## 5. Multiple jobs, cancellation, IRQs and timeout

Hardware retains four task/result slots. `jxl_try_submit` returns `JXL_AGAIN` when FLAGS bit0 is clear. Never block the CPU on a fifth SUBMIT while failing to POP results. Protect the shadow-CSR write sequence and result snapshot with one lock. Match results by tag, not submission order; never reuse an outstanding tag.

Cancel by tag/doorbell, wait for FLAGS bit2, read ACK tag/found, then POP. Found is not DMA completion: retain buffers until the matching terminal task result and drained transactions. Software timeout does not authorize freeing/reusing DMA buffers. Recover by terminal completion or a system-level guarantee that the master is isolated and all transactions ended.

IRQ is level-triggered from result/cancel-ACK valid, not a W1C pending register. Register the PLIC ISR before enabling the corresponding `IRQ_ENABLE` bits. Save snapshots, POP, wake waiters, then complete the PLIC protocol. Obtain IRQ numbers from DTS. Historical board tests polled; a new ISR needs its own arrival/masking/retrigger/full-queue/teardown tests.

## 6. Porting a Linux platform driver

This is a porting procedure, not an existing qualified `.ko`. Do not directly include bare-metal `stdint.h` code in the kernel; implement the same ABI with `u32/u64` and `readl/writel`. Pin your kernel commit/config, RISC-V compiler and device tree. Follow the [Linux DMA guide](https://docs.kernel.org/core-api/dma-api-howto.html) for CPU-versus-DMA addresses.

1. Match `openrtl,jpegxl-fast-v1` with `of_match_table`, map the 4 KiB aperture using `devm_platform_ioremap_resource`, and probe ID/ABI. Set `dma_set_mask_and_coherent(dev,DMA_BIT_MASK(33))` and separately validate the hardware DDR window.
2. Start with kernel-owned `dma_alloc_coherent` bounce buffers, or correct streaming mapping/synchronization. Check allocation/mapping errors, alignment, overflow, sizes, access rights and disjoint ownership. Never program user pointers/`virt_to_phys` or accept arbitrary user physical addresses. IOMMU mappings must also satisfy the bridge window.
3. Define fixed-width SUBMIT ioctl fields for tag/dimensions/channels, user data pointer/length and output capacity. Use `copy_from_user`, check multiplication overflow, allocate/copy and derive DMA addresses in the kernel. Serialize CSRs with a mutex and order memory with `dma_wmb()` before the doorbell. POLL/WAIT/READ_RESULT should reap by tag, validate hardware status and bounded length, then `copy_to_user`.
4. Use `platform_get_irq`/`devm_request_irq`. Save and POP the entire completion before returning ownership. Use `dma_sync_*` for streaming mappings; coherent allocations still need ordering. Bare-metal volatile callbacks do not replace kernel MMIO APIs.
5. On close/timeout/cancel/process exit/remove, stop submissions, disable/synchronize IRQ and drain DMA before freeing/unmapping. Start with one exclusive open, then extend concurrency or pinned user buffers.
6. Separately validate Kbuild cross-compilation, kernel negative tests and real hardware. Include bad user pointers/lengths/tags, four-slot saturation, reordered results, cancellation, noncoherent memory, compat ioctl and remove races. Bare-metal evidence does not qualify a Linux driver.
