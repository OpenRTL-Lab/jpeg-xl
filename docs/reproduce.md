<!-- OpenRTL Lab | JPEG XL | version 1.0.0 | SPDX-License-Identifier: MIT -->
# Stage-by-stage reproduction

[简体中文](reproduce.zh-CN.md)

Start in an empty working directory. Component generation, RTL execution, digital SoC construction and software builds are separate milestones. Stop on a failed command and inspect its log; never replace failed outputs with shipped RTL. Paths below are relative to your working directory. Public cloning requires no GitHub credentials.

```sh
git clone https://github.com/OpenRTL-Lab/jpeg-xl.git
cd jpeg-xl
git rev-parse HEAD
python3 tools/check_public_tree.py
```

Record the full commit instead of treating floating `main` as a version lock. Source dependencies are pinned in `build.mill`; distributed file hashes are in `PUBLIC_FILES.json`. Read the [third-party notices](../THIRD_PARTY.md).

| Stage | Inputs and entry point | Outputs and acceptance |
| --- | --- | --- |
| 1. Environment | [Build](build.md); Java, Mill, Python, C++, Verilator | Pinned versions, successful inventory/privacy checks |
| 2. Chisel to SV | `mill -i jpegxl.tool.runMain openimage.jpegxl.fast.EmitFastNative build/native` | `FastNativeEncoder.sv` and dependencies; body identity from `check_generated.py` |
| 3. RTL execution | [Simulation guide](simulation.md); row miter, whole native encoder, faults/cancellation | Positive PASS and the intended assertion in negative tests |
| 4. Platform adapters | [Chipyard guide](chipyard.md); pinned upstream, adapter tests | Passing CSR/DMA ScalaTests, not codec qualification with synthetic results |
| 5. Digital SoC | Same guide; one-Rocket config, FIRRTL, firtool | Config checks, device tree, native instance and complete RTL input list |
| 6. Driver and ELF | [Driver guide](driver.md); host contracts and RV64 build | Host PASS and correct ELF header/symbols; not program execution |
| 7. System/FPGA | Your platform supplies boot, memory and physical constraints | Actual complete-file/padding PASS; historical board results do not qualify a new port |
| 8. Performance analysis | `python3 tools/check_measurements.py` and [methodology](performance.md) | All 3168 tasks, timing windows, request counts and three-round statistics agree |

Stages 2/3 use Chisel 7. Stages 4/5 compile only the Chisel 6 platform sources and consume generated SV as the BlackBox implementation. Do not add the component `src/main/scala` tree to a Chisel 6 classpath. The two compiler chains meet at RTL ports, not shared Scala classes.

## Short and complete routes

For an environment smoke test, run inventory, host checks and the row test; neither a RISC-V toolchain nor Chipyard is required. For complete encoded output, run the native RTL suite: real pixels enter the DUT and every 512-bit write is compared against the complete official file and padding, not just a signature. To integrate the IP, continue through adapter tests, digital SoC generation and driver porting.

This repository supplies digital components and generic examples, not a physical board, pins, vendor DDR IP or programming scripts. Stage 7 needs your own platform project. Missing private board files do not prevent stages 1–6, and this guide does not promise a bitstream for arbitrary boards.

## Keep your own evidence

Retain the full Git commit, tool versions, commands/exit codes, `build/*/completion.json`, generated-file hashes and logs. Select a new output directory for each run. Record host tests, real RTL, target ELF builds, system execution, board tests and implementation timing separately. The historical 136 RTL tests and three board rounds are evidence for the supplied implementation; report only the stages you actually rerun.
