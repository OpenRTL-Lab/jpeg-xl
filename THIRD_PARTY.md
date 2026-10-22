<!-- OpenRTL Lab | JPEG XL | version 1.0.0 | SPDX-License-Identifier: MIT -->
# Third-party notices

[简体中文](THIRD_PARTY.zh-CN.md)

Original project code is distributed under the root [MIT license](LICENSE). Existing notices are retained. The following dependencies are not relicensed by that root license.

| Component | Public source / version | Distribution and license |
| --- | --- | --- |
| libjxl reference | [libjxl](https://github.com/libjxl/libjxl), v0.11.2, commit `332feb17d17311c748445f7ee75c4fb55cc38530` | No codec implementation vendored. Captured test data is conservatively accompanied by the upstream BSD-3-Clause [license](licenses/libjxl-LICENSE) and [patent grant](licenses/libjxl-PATENTS). |
| Chisel | [Chisel](https://github.com/chipsalliance/chisel), 7.13.0; platform adapters 6.5.0 | External dependency, Apache-2.0; not vendored. |
| CIRCT | [CIRCT](https://github.com/llvm/circt), firtool 1.149.0 | External generator, its own upstream LLVM license terms; not vendored. Generated SV retains the generator identification. |
| Chipyard / Rocket Chip | [Chipyard](https://github.com/ucb-bar/chipyard), commit `69eba860a352343e4ac6b6df0f3638a79a86ec78` | External platform APIs; upstream licenses apply to their sources. No full platform or third-party submodules vendored. |
| Verilator | [Verilator](https://github.com/verilator/verilator) | External simulator under its upstream license; not vendored. |
| AMD/Xilinx primitives | DSP48E2, RAMB36E2, URAM288_BASE | Original wrappers supplied; proprietary primitive implementations, IP, tools and device databases are not redistributed. Obtain them through your licensed tool installation. |

`reference/` contains project-authored observers that include or call the upstream encoder for testing; building them requires the original upstream files and their licensing terms. `software/jxl_sparse_loadmem.*` connects to external FESVR APIs; those external implementations are not included. `tests/fixtures/` contains synthetic pixel inputs and complete reference captures, not a general image dataset. Its notice applies to the data and generated C arrays, not to the independent project test helpers.

The source inventory distinguishes original source, generated RTL and reference data. Publication headers identify this distribution and do not remove upstream notices or grant rights in undistributed vendor software.
