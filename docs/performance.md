<!-- OpenRTL Lab | JPEG XL | version 1.0.0 | SPDX-License-Identifier: MIT -->
# FPGA measurements

[简体中文](performance.zh-CN.md)

The native encoder was measured on a VU13P-class FPGA with one Rocket RV64 CPU, coherent DDR DMA and both CPU and accelerator clocks at 50 MHz. Physical-board configuration and programming files are not distributed. The source-level digital adapters are provided separately; these measurements do not qualify a newly adapted system.

## Method

Three predetermined rounds each processed 32 fixtures, once individually and once as a batch of 32 identical inputs: 3,168 completed tasks in total. Every complete output file and its DMA padding were checked against fixed libjxl 0.11.2 fast-lossless captures. Inputs cover 1–4 channels and eight sizes through 256×256, at 8 bits per channel. The complete per-fixture samples are in [measurements.json](../evidence/measurements.json), with all numeric task timestamps in [task-cycles.json](../evidence/task-cycles.json); inputs and reference files are in [fixtures.json](../tests/fixtures/fixtures.json).

The CPU cycle interval starts after buffer preparation and covers command submission through draining all results. It excludes input preparation, output comparison, UART output and executable loading. Hardware latency is `(retired − accepted) / 50 MHz`, including DMA and queue waiting. First output uses `(first_write − accepted) / 50 MHz`. Batch throughput is raw input bytes divided by that CPU interval; MB means 1,000,000 bytes. Hardware timestamps measure a different interval from CPU submission/draining.

The prefix memo was not explicitly cleared. A batch repeats the same image, so these are not cold-cache or mixed-content batch results. There is no CPU software-codec baseline or measured speedup.

## 256×256 results

Values are medians of three rounds. Batch throughput includes all 32 tasks, not just the fastest task.

| Channels | Input bytes | File bytes | Hardware single (µs) | CPU single (µs) | Batch input (MB/s) | Batch pixels (MPixel/s) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 65,536 | 72,968 | 403.32 | 420.22 | 249.25 | 249.25 |
| 2 | 131,072 | 146,129 | 584.58 | 602.98 | 291.06 | 145.53 |
| 3 | 196,608 | 221,385 | 787.30 | 806.06 | 291.32 | 97.11 |
| 4 | 262,144 | 294,079 | 982.76 | 1,002.10 | 293.15 | 73.29 |

RGB batch throughput ranged from 291.27 to 291.54 MB/s; four-channel throughput ranged from 292.97 to 293.21 MB/s. The weighted mixed-fixture median was 259.63 MB/s, calculated from total bytes / total batch time, not an arithmetic mean of rates. Small fixtures are overhead-sensitive; all three samples, including fixture 3's variation, are retained in the data.

These synthetic fixtures can produce files larger than their inputs. The table is not a compression-ratio claim for natural images, an end-to-end application benchmark or a universal JPEG XL performance claim. See [verification](verification.md) for limits.
