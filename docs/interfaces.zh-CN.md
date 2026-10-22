<!-- OpenRTL Lab | JPEG XL | version 1.0.0 | SPDX-License-Identifier: MIT -->
# 架构与接口

[English](interfaces.md)

## 系统边界

`FastNativeEncoder` 读取原始像素并写出完整 JPEG XL 裸码流。CPU提交描述符，编码器完成预测、统计、码表构造和比特打包；CPU不提供编码答案或中间码表。内部两个计算上下文与四个保留任务/结果槽用于交叠工作，不是多个独立满速编码器。

输入为紧密交错的 Gray、GrayA、RGB 或 RGBA，每通道8位，无行跨度。宽、高各1～256。输出以裸码流签名 `FF 0A` 开始，不增加外层文件容器。保留透明像素的颜色样本。

## 原生端口

方向均相对编码器。顶层 `clock` 配合低有效异步复位 `rst_n`。各通道使用 valid/ready，仅在上升沿二者同为高时传输；生产者被阻塞时须保持 valid 和载荷稳定。

| 通道 | 方向 | 载荷 |
|---|---|---|
| command | 输入 | tag32、width9、height9、channels3、source33、destination33、sourceSpan24、destinationSpan24、outputLimit24 |
| result | 输出 | tag32、status3、outputBytes24、faultAddress33、acceptedCycle64、firstFileWriteCycle64、retiredCycle64、readRequests13、writeRequests15 |
| cancel | 输入 | tag32 |
| cancelAck | 输出 | tag32、found1 |
| read | 输出 | id4、address33 |
| readResponse | 输入 | id4、data512、fault1 |
| write | 输出 | id4、address33、data512 |
| writeResponse | 输入 | id4、fault1 |
| quiescent | 输出 | 无保留任务、待取结果或未结事务 |

实际展开的 SV 端口采用 `io_command_bits_tag` 等名称。原生接口不是 AXI 或 TileLink，需要适配器转换。ID可以乱序完成，但响应返回前不能复用活动ID。每次访存是一个对齐64字节块，低字节通道对应低地址。

## 内存归属与完成

地址、范围和上限均以字节计。输入/输出起始地址和范围须64字节对齐。输入范围至少覆盖 `roundUp(width*height*channels,64)`；输出范围和输出上限须非零，上限不超过输出范围。地址加范围不能超过33位。输入和输出范围不能相交，也不能与其他保留任务所拥有范围相交；两个活动任务不能共享同一输入分配。结果被消费前，缓冲仍属于任务；取消不等于可以提前回收未结DMA缓冲。

结果可以与提交乱序，必须按tag识别。非法命令也可能进入结果槽，然后返回错误而不进行DMA。使用长度前先检查状态，完整状态枚举和软件检查以 `software/jxl_mmio.h` 为准。有效输出校验至 `outputBytes`，末拍64字节边界以内其余字节为零填充。

## 数字 SoC 适配器

`integration/src/main/scala/jpegxl/platform/` 提供CSR控制器和512位 TileLink DMA适配器。CSR使用4KiB区间中的32位访问，默认基址 `0x10040000`，ID为 `0x4a584c31`，ABI为 `0x00010000`。寄存器表和辅助函数在 `software/jxl_mmio.h`。DMA桥将访问限制在声明的DDR窗口，并传播拒绝/损坏响应。提供中断输出；FPGA实测软件采用轮询，不代表中断已验证。

集成trait通过分片/宽度适配器将控制接入系统总线，将DMA接入一致性系统总线，将完成中断接入子系统中断总线。外部系统提供时钟、复位同步、地址映射/一致性、中断分配、CPU启动和物理内存，详见[构建与集成](build.zh-CN.md)。其他有界编码/解码源码使用独立的字节流合同 `NativeIO`，不能作为 `FastNativeEncoder` 的直接替代。
