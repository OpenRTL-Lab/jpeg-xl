<!-- OpenRTL Lab | JPEG XL | version 1.0.0 | SPDX-License-Identifier: MIT -->
# Build and integration

[简体中文](build.zh-CN.md)

## Tools and component generation

Run commands from the repository root. Component generators use Mill 1.1.7, Scala 2.13.18, Chisel 7.13.0 and CIRCT firtool 1.149.0, with a Java 17 runtime. Python 3.11 or newer and a C/C++ compiler are needed for host checks; RTL simulation needs Verilator. Dependency artifacts come from public Maven repositories; no private build outputs are required.

```sh
python3 tools/check_public_tree.py
python3 tools/check_host.py --out build/host
python3 tools/check_measurements.py
mill -i jpegxl.tool.runMain openimage.jpegxl.fast.EmitFastNative build/native
python3 tools/check_generated.py build/native
```

`check_host.py` compiles and runs the MMIO and buffer-helper tests; failure is a nonzero exit. These tests do not run the RTL. The emitter produces the native product modules and verification layers. `check_generated.py` compares every generated SV body to the published inventory, ignoring only the exact added distribution header. A comparison failure must be investigated, not suppressed by copying the supplied RTL into the output folder. Use a fresh output directory. `rtl/native/rtl.f` lists the distributed synthesis-facing modules relative to `rtl/native/`; test-only verification layers are separately retained.

To reproduce the independent arithmetic-row test, including intentional-error rejection:

```sh
python3 tools/run_row_test.py --out build/row
```

This generates two test tops from the supplied Chisel, compiles each with Verilator and requires the normal pass plus the intended negative failure. It is not a complete encoder simulation. The remaining test generators and clock drivers are included for deeper integration testing; [verification](verification.md) identifies their scope.

## Native RTL and hard blocks

The generated native top exposes the ports described in [interfaces](interfaces.md). With `JXL_FPGA_RAM` defined, the product's original wrappers explicitly instantiate `DSP48E2`, `RAMB36E2` and `URAM288_BASE`; the original primitive implementations and simulation libraries must be supplied by a licensed AMD/Xilinx installation. Without that define, the wrappers provide behavioral arithmetic and synchronous-memory models. Do not treat behavioral simulation as primitive-level or post-route signoff. The primitive wrapper source is open; vendor implementations are not redistributed.

## Digital adapters, not a complete board system

The platform adapter sources target Scala 2.13.12, Chisel 6.5.0 and the Rocket/Chipyard API; they are intentionally separate from the Chisel 7 component generator. Unit tests use chiseltest 6.0.0 and ScalaTest 3.2.19. Upstream Chipyard reference: `https://github.com/ucb-bar/chipyard.git`, commit `69eba860a352343e4ac6b6df0f3638a79a86ec78`. The reusable system trait accepts a subsystem, connects CSR/DMA/interrupts and does not select a physical board.

Add the platform source set to a compatible Chipyard project, include `WithJxl` in the system configuration and mix `CanHaveJxl` into the subsystem. Attach a generated `FastNativeEncoder` implementation to `JxlNativeBlackBox`. Match the 128-bit system-bus / 512-bit memory-adapter assumptions, DDR address range, reset conversion and interrupt routing explicitly. The platform source and tests are supplied, but a fresh full digital SoC build with this generic mixin has not been qualified as part of this distribution; it is not a turnkey system-generation promise or the privately tested physical system configuration.

## Bare-metal software

`software/jxl_benchmark.c` checks full official files and output padding. It assumes an RV64 coherent-memory platform with the documented CSR mapping, a SiFive-compatible UART at `0x10020000`, 50 MHz cycle counters, suitable DDR allocations and the supplied startup/linker layout. Adapt those digital platform contracts before use. The program does not configure an FPGA or provide a JTAG connection.

```sh
riscv64-unknown-elf-gcc -std=c11 -O2 -Wall -Wextra -Werror \
  -march=rv64imafdc -mabi=lp64d -mcmodel=medany \
  -ffreestanding -fno-builtin -nostdlib -nostartfiles \
  -Isoftware -Itests/fixtures -Wl,-T,software/link.ld \
  -Wl,--build-id=none software/start.S software/jxl_benchmark.c \
  -o build/jxl-benchmark.elf
```

Building an ELF is not executing it. Physical-board download, reset, boot firmware, pin constraints, clocks, DDR controller and proprietary IP configuration are intentionally outside this repository. Existing FPGA measurements do not prove a newly adapted system has passed.
