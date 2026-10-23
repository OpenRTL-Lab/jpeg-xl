<!-- OpenRTL Lab | JPEG XL | version 1.0.0 | SPDX-License-Identifier: MIT -->
# RTL simulation cookbook

[简体中文](simulation.zh-CN.md)

Run from this repository root with the [build tools](build.md), Verilator 5.050, C++17 and Make. These runs use behavioral hard-block models without `JXL_FPGA_RAM`; they are not vendor-primitive or post-route simulations. Tests live in `src/tool/scala`, products in `src/main/scala` or generated SV. C++ drivers provide clocks/reset, not encoded answers.

## 1. Start with host contracts and the row miter

```sh
python3 -m unittest discover -s tests -v
python3 tools/check_host.py --out build/host
python3 tools/run_row_test.py --out build/row
```

The first two commands do not execute RTL. The row script emits positive/intentional-error `FastLengthRowMiter` harnesses and compiles with Verilator `--assert`. Require `FAST_ROW_MITER_PASS` for normal execution, and nonzero exit with `FAST_ROW_MITER_COST` for the negative. The wrapper exits zero only when both expectations hold. See `build/row/{normal,negative}-*.log` and `completion.json`. Expected negative assertions are not tool failures.

## 2. Prepare real pixels and official complete files

```sh
python3 tools/prepare_native_fixtures.py --out build/native-fixtures
```

This checks hashes/lengths of the 64 binaries indexed by `tests/fixtures/fixtures.json`, then mechanically repacks them into zero-padded little-endian 64-byte memory words, offsets, dimensions/channels/tags and the fault-test image. It does not predict pixels, construct codebooks or encode anything. The receipt supplies `raw_words`, `file_words` and the prefix. Test ROMs must stay outside product RTL.

Optionally execute the upstream oracle again rather than relying on captures:

```sh
git clone https://github.com/libjxl/libjxl.git build/libjxl
git -C build/libjxl checkout --detach 332feb17d17311c748445f7ee75c4fb55cc38530
c++ -O1 -std=c++17 -DFJXL_STANDALONE=1 \
  -DFJXL_ENABLE_AVX2=0 -DFJXL_ENABLE_AVX512=0 -Ibuild/libjxl \
  reference/fast_file_observer.cc -o build/official-observer
mkdir -p build/reference
build/official-observer build/reference/official
python3 tools/check_reference_capture.py --prefix build/reference/official
```

Require `OFFICIAL_FILE_OBSERVER_PASS images=32` and byte identity for all 64 files. This needs public upstream source/network access, not private data or a full libjxl shared-library build. Upstream BSD/patent notices still apply.

## 3. Execute the complete native encoder

Generate and body-check `build/native` using the [build guide](build.md), then:

```sh
python3 tools/run_native_test.py --rtl build/native \
  --suite native --mode both --out build/native-sim
```

The script prepares its own fixtures, emits `FastNativeTest`, compiles the selected product SV plus the harness, and runs `tools/fast_native_clock.cpp`. Two phases of 32 images exercise ordinary memory and request/result backpressure, reordered IDs, stable offers and draining all transactions. Every output word includes complete-file and tail-padding comparisons. Require two `FAST_NATIVE_PHASE_PASS`, 64 `FAST_NATIVE_COMPLETE` and `FAST_NATIVE_SUITE_PASS`. The negative flips one bit in the runtime-loaded golden ROM, executes the **same binary**, requires `FAST_NATIVE_OFFICIAL_BYTES`, then restores the reference file and verifies the executable hash. No DUT or product assertion is changed or disabled. With `--mode both`, native compilation happens once; unrelated failures/timeouts are never accepted.

Omit `--rtl` to test the shipped `rtl/native` instead. Never include both product sets. Whole-product C++ compilation is much slower than the row test; inspect `normal-compile.log` for progress. Execution has cycle/wall-clock limits. The receipt must contain `status=0`, `finished=true` and two successful expected results.

## 4. Faults, cancellation and recovery

```sh
python3 tools/run_native_test.py --rtl build/native \
  --suite fault --mode both --out build/native-fault
```

Sixteen scenarios produce twenty task results: invalid dimensions/channels/alignment/overflow, overlapping ownership and duplicate tags, output limits, memory faults, cancellation/not-found acknowledgments and subsequent successful work. Require sixteen `FAST_NATIVE_FAULT_CASE_PASS` and `FAST_NATIVE_FAULT_SUITE_PASS`. The negative must hit `FAST_NATIVE_FAULT_STATUS`. A cancellation acknowledgment does not release DMA ownership; the test checks response draining before results.

## 5. Debugging and extensions

Use `--mode normal` or `negative` to run one variant, always in a new output directory. Scripts retain emitted SV, Verilator objects, full emission/compile/run logs and exit codes. Missing PASS, timeout, duplicate modules, wrong ROM paths or missing primitives are failures. Preserve failed output and inspect the earliest failing log before a new run.

Additional entry points are in `src/tool/scala/openimage/jpegxl/fast/*Test.scala`, clock drivers in `tools/*_clock.cpp`, and observers in `reference/`. Each emitter's `require(args.length...)` defines its arguments; other modules do not share the native ROM format. The historical 136-test evidence includes additional directed tests, not an assertion that these scripts rerun all 136.

Run platform CSR/DMA tests separately using the [Chipyard guide](chipyard.md). Primitive-level testing requires licensed vendor models for `DSP48E2`, `RAMB36E2`, `URAM288_BASE` and dependencies, plus `JXL_FPGA_RAM`. Record library/tool versions and results separately; behavioral PASS is not primitive-level PASS.
