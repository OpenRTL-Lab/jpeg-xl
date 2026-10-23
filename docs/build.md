<!-- OpenRTL Lab | JPEG XL | version 1.0.0 | SPDX-License-Identifier: MIT -->
# Build and integration

[简体中文](build.zh-CN.md)

## Tools and component generation

Run commands from the repository root. Component generators use Mill 1.1.7, Scala 2.13.18, Chisel 7.13.0 and CIRCT firtool 1.149.0, with Java 17 or newer (fresh public-tree checks used Java 21). Python 3.11 or newer and a C/C++ compiler are needed for host checks; RTL simulation uses Verilator 5.050 and Make. Dependency artifacts come from public Maven repositories; no private build outputs are required. Start with the [stage map](reproduce.md).

Install Java and a C++ toolchain using your OS package manager; install the pinned [Mill release](https://github.com/com-lihaoyi/mill/releases/tag/1.1.7) on PATH. `build.mill` resolves Scala, Chisel and its matching firtool. Do not point `CHISEL_FIRTOOL_PATH` at the Chipyard firtool; unset it unless it contains 1.149.0. Record versions before building:

```sh
java -version
mill --version
python3 --version
c++ --version
verilator --version
make --version
```

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

Follow the [Chipyard tutorial](chipyard.md), including the installer, two SBT projects, adapter tests, generic one-Rocket configuration, device-tree inspection, system lowering and explicit BlackBox file list. This is a digital example, not the privately tested physical system configuration.

## Bare-metal software

See the [driver tutorial](driver.md) for the register map, DMA allocation/cache rules, a polling example, interrupts and a complete ELF build. For full encoder RTL tests rather than the row-only smoke test, follow the [simulation tutorial](simulation.md).

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
