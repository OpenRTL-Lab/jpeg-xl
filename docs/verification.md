<!-- OpenRTL Lab | JPEG XL | version 1.0.0 | SPDX-License-Identifier: MIT -->
# Verification and limits

[简体中文](verification.zh-CN.md)

## Qualified implementation

For executable instructions, use [simulation](simulation.md), [Chipyard integration](chipyard.md) and [driver development](driver.md), starting from the [stage map](reproduce.md). Their commands distinguish component RTL, adapters, digital generation, ELF construction and device execution.

The supplied native encoder was checked before FPGA implementation with 136 RTL simulation cases and 1,056 CPU reference encodes. The suite includes component checks, complete native files, stalls, reset/cancel/error cases, arithmetic-row comparisons and intentional-error tests. Reference observers call the actual upstream fast-lossless implementation; they are not independent replacement codec models. The 32 distributed fixture pairs preserve complete captured files, not only headers or hashes.

Subsequent bare-metal FPGA checks completed 3,168 tasks in three measurement rounds with complete file-byte and padding comparisons. This is finite-vector evidence, not a mathematical proof of all possible inputs. The publication preparation does not rerun that historical multi-hour suite. Current distribution checks and commands are described in [build](build.md); a successful host check is not an RTL or FPGA pass.

## Reproduction-guide checks

The added public one-Rocket example was compiled against the pinned public Chipyard sources, with successful CSR and DMA Verilator tests, configuration checks, FIRRTL/device-tree generation, firtool lowering and a 612-file combined RTL list. The generated DTS contains one CPU, 4 GiB memory and encoder interrupt 2. The RV64 polling example and benchmark ELF cross-compiled; the ELF was checked as ELF64/RISC-V with `_start`, `main` and `jxl_trap`. The polling example's seven fake-register host cases also passed. Public downloaded dependency caches were reused; no private compiled SoC outputs were copied into the example.

These checks do not execute an ELF in the new digital SoC and do not build a simulator, bitstream or Linux driver. The simulation branch's fixed PLIC constants must be ported before such execution. The public TL-UL test skips nonexistent BCE channels; this test-harness correction changes no product RTL.

The public fixture preparation and fresh native harness also produced 64 complete matching RTL files. The runtime-ROM negative was checked using that already-passed executable, retaining the normal result rather than rerunning it; it hit `FAST_NATIVE_OFFICIAL_BYTES` with unchanged executable SHA-256. The directed fault recipe passed all 16 scenarios / 20 task results, and its intentional-error run hit `FAST_NATIVE_FAULT_STATUS`. Host tests additionally reject unrelated negative failures and check reference restoration. These are targeted recipe checks, not a rerun of all 136 historical cases.

## Implementation evidence

The tested implementation's synthesis setup WNS was +1.193 ns with zero failing setup endpoints. The representative depth population contained 5,568 paths above 20 levels out of 1,969,649 (0.283%); all remaining long paths belonged to the shared baseline, not the encoder. This is the reported representative population, including zero-level entries and excluding the separately reported recovery group, not enumeration of every mathematical path.

After routing and a local DDR skew repair, setup / hold / pulse-width worst slacks were +0.131 / +0.010 / +0.039 ns. All 1,700,070 routable nets were routed, with zero routing errors and zero default DRC errors or critical violations. These are implementation observations, not a new physical-board build supplied by this repository. Baseline input/output delay omissions and a JTAG non-dedicated-clock exception remain; do not call this full interface/PVT signoff.

## Supported scope and exclusions

The qualified path is 8-bit lossless, single group, dimensions 1–256 in each axis and 1–4 channels using the fixed fast-lossless reference profile. The bounded encoder/decoder under `src/main/scala/openimage/jpegxl/` have separate experimental interfaces and do not inherit native encoder qualification.

No claim is made for general JPEG XL decoding, lossy coding, HDR, animation, multiple groups, larger images, Linux drivers, Ethernet application delivery, board-level interrupt operation or long-term stability. FPGA tests used polling. The generic platform mixin is supplied for adaptation, not as a newly qualified complete SoC. Vendor primitive implementations, board files and download utilities are excluded.

## Integrity checks

`SOURCE_MANIFEST.json` records distributed source and generated-body digests. `PUBLIC_FILES.json` inventories every distributed file except itself; it is an integrity index, not a cryptographic signature. Checkers reject missing, extra or changed payload files, invalid relative links and common sensitive literals. They cannot prove absence of every possible secret or third-party right; human source and licensing review is also required before public release. The repository remains private pending review.
