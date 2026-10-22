<!-- OpenRTL Lab | JPEG XL | version 1.0.0 | SPDX-License-Identifier: MIT -->
# 第三方说明

[English](THIRD_PARTY.md)

项目自研代码采用根目录 [MIT 许可证](LICENSE)，已有声明保留。以下依赖不因根许可证而改变许可。

| 组件 | 公共来源与版本 | 分发范围与许可 |
| --- | --- | --- |
| libjxl 参考 | [libjxl](https://github.com/libjxl/libjxl)，v0.11.2，提交 `332feb17d17311c748445f7ee75c4fb55cc38530` | 不内置编码器实现。参考数据保守附带上游 BSD-3-Clause [许可](licenses/libjxl-LICENSE)和[专利授权](licenses/libjxl-PATENTS)。 |
| Chisel | [Chisel](https://github.com/chipsalliance/chisel)，7.13.0；平台适配为 6.5.0 | 外部依赖，Apache-2.0，不内置。 |
| CIRCT | [CIRCT](https://github.com/llvm/circt)，firtool 1.149.0 | 外部生成器，适用上游 LLVM 许可条款，不内置；生成 SV 保留生成器标识。 |
| Chipyard / Rocket Chip | [Chipyard](https://github.com/ucb-bar/chipyard)，提交 `69eba860a352343e4ac6b6df0f3638a79a86ec78` | 外部平台 API，上游源码适用其自身许可；不内置整个平台或第三方子模块。 |
| Verilator | [Verilator](https://github.com/verilator/verilator) | 外部仿真器，适用上游许可，不内置。 |
| AMD/Xilinx 原语 | DSP48E2、RAMB36E2、URAM288_BASE | 提供自研包装，不分发专有原语实现、IP、工具或器件数据库，须从合法授权的工具安装获取。 |

`reference/` 是项目自研观察器，通过包含或调用上游编码器进行测试，编译需要上游原文件并遵守其许可。`software/jxl_sparse_loadmem.*` 连接外部 FESVR API，不包含外部实现。`tests/fixtures/` 包含合成像素输入和完整参考文件，不是通用图像数据集；数据声明适用于样例与生成 C 数组，不替代独立自研测试辅助程序的许可。

源码清单区分自研源码、生成 RTL 和参考数据。分发来源头仅标识本仓分发，不删除上游声明，也不授予未分发厂商软件的权利。
