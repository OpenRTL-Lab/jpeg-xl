<!-- OpenRTL Lab | JPEG XL | version 1.0.0 | SPDX-License-Identifier: MIT -->
# JPEG XL 硬件编码器

[English](README.md)

开放硬件，从算法到 RTL。

本仓库提供 MIT 许可的有界 JPEG XL 无损编码器，包括 Chisel 生成器、生成的 SystemVerilog、原生命令/DMA接口、CSR 与 TileLink 适配器、测试环境及裸机软件。支持的编码器对8位、1～4通道、宽高各1～256的图像生成完整裸码流，与固定版本 libjxl 快速无损参考的结果一致。

## 内容索引

- [从这里开始：逐阶段复现](docs/reproduce.zh-CN.md)：每阶段的环境、命令、产物与通过判据。
- [运行仿真](docs/simulation.zh-CN.md)：夹具准备、算术行、完整编码器和故障测试，含负向注错检查。
- [用 Chipyard 拼接 SoC](docs/chipyard.zh-CN.md)：固定上游、单 Rocket 示例、CSR/DMA 测试、FIRRTL/SV 与 BlackBox 输入。
- [编写与移植驱动](docs/driver.zh-CN.md)：寄存器 ABI、DMA 归属、轮询、中断、RV64 ELF 和 Linux 集成要求。
- [架构与接口](docs/interfaces.zh-CN.md)：系统边界、内存归属、命令/结果通道及软件 ABI。
- [构建与集成](docs/build.zh-CN.md)：固定工具版本、组件生成、原生集成和适配器要求。
- [验证](docs/verification.zh-CN.md)：宿主检查、RTL 测试、参考夹具及单独列出的 FPGA 证据。
- [性能](docs/performance.zh-CN.md)：三轮测量、计时窗口及限制。
- [第三方声明](THIRD_PARTY.zh-CN.md)：许可证、上游参考及专有工具边界。

可复用编码器位于 `src/main/scala/openimage/jpegxl/fast/` 与 `rtl/native/`。`integration/` 包含可复用数字适配器，不是开发板工程。其他自研有界编码/解码生成器也包含在 `src/main/scala/openimage/jpegxl/`；其接口不同，不属于原生编码器 FPGA 结果的覆盖范围。

## 适用范围

主要编码器实现固定的 `JxlFastLosslessEncode`、effort=1 子集，不是完整 JPEG XL 标准或 libjxl API。不解析 PNG/JPEG 文件，不解码原生编码器的输出，也不支持有损、HDR、动画、多组或超过256×256的图像。

三轮 FPGA 测试通过3168项完整文件及尾部填充检查。在50MHz下，256×256 RGB单任务硬件延迟为787.30μs，同图重复批处理输入吞吐为291.32MB/s。二者是不同口径，不是应用端到端速度或相对CPU加速倍数；详见[测量条件](docs/performance.zh-CN.md)。

不分发开发板特定工程、标识、原理图、引脚分配、厂商IP配置、比特流或启动镜像。源码用于组件复用，不是完整开发板启动包。

## 许可

原创内容使用 [MIT 许可证](LICENSE)，请保留[第三方声明](THIRD_PARTY.zh-CN.md)。[OpenRTL Lab](https://openrtl.top/)提供可选定制和集成服务，不改变开源许可。
