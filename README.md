<!-- OpenRTL Lab | JPEG XL | version 1.0.0 | SPDX-License-Identifier: MIT -->
# JPEG XL hardware encoder

[简体中文](README.zh-CN.md)

Open hardware, from algorithms to RTL.

This repository provides an MIT-licensed, bounded JPEG XL lossless encoder: Chisel generators, generated SystemVerilog, native command/DMA interfaces, CSR and TileLink adapters, test environments, and bare-metal software. The supported encoder produces complete raw codestreams matching the fixed libjxl fast-lossless reference for 8-bit images with one to four channels and dimensions from 1 to 256.

## Contents

- [Start here: stage-by-stage reproduction](docs/reproduce.md): prerequisites, commands, outputs and acceptance at every stage.
- [Run simulations](docs/simulation.md): fixtures, arithmetic miter, complete encoder and fault tests, including negative checks.
- [Assemble a Chipyard SoC](docs/chipyard.md): pinned upstream, one-Rocket example, CSR/DMA tests, FIRRTL/SV and BlackBox inputs.
- [Write and port a driver](docs/driver.md): register ABI, DMA ownership, polling, interrupts, RV64 ELF and Linux integration requirements.
- [Architecture and interfaces](docs/interfaces.md): system boundaries, memory ownership, command/result channels and software ABI.
- [Build and integration](docs/build.md): pinned tools, component generation, native integration and adapter requirements.
- [Verification](docs/verification.md): host checks, RTL tests, reference fixtures and separately identified FPGA evidence.
- [Performance](docs/performance.md): three-round measurements, timing windows and limitations.
- [Third-party notices](THIRD_PARTY.md): licenses, upstream references and proprietary-tool boundaries.

The reusable encoder lives in `src/main/scala/openimage/jpegxl/fast/` and `rtl/native/`. `integration/` contains reusable digital adapters, not a board project. Other self-developed bounded encoder/decoder generators are also included in `src/main/scala/openimage/jpegxl/`; they have a different interface and are not covered by the native encoder's FPGA results.

## Scope

The primary encoder implements the fixed `JxlFastLosslessEncode` effort-1 subset, not the entire JPEG XL standard or libjxl API. It does not parse PNG/JPEG files, decode the native encoder output, or support lossy coding, HDR, animation, multiple groups or images larger than 256×256.

Three FPGA test rounds passed 3,168 complete-file and padding checks. At 50 MHz, 256×256 RGB measured 787.30 µs single-task hardware latency and 291.32 MB/s repeated-image batch input throughput. These are different measurements, not end-to-end application speed or CPU speedup; see the [measurement conditions](docs/performance.md).

Board-specific projects, identities, schematics, pin assignments, vendor IP configurations, bitstreams and boot images are not distributed. The sources permit component reuse; they are not a complete board boot package.

## License

Original work uses the [MIT License](LICENSE); preserve [third-party notices](THIRD_PARTY.md). Optional customization and integration services are available from [OpenRTL Lab](https://openrtl.top/?lang=en), without changing the open-source license.
