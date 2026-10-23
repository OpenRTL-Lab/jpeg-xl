<!-- OpenRTL Lab | JPEG XL | version 1.0.0 | SPDX-License-Identifier: MIT -->
# 逐阶段复现路线

[English](reproduce.md)

这条路线从空的工作目录开始，把组件生成、RTL测试、数字SoC拼装和软件构建分开。命令失败时应查看对应日志并停止，不要复制旧产物覆盖失败目录。以下路径均相对工作目录；仓库仍私有时，克隆需要组织授予读取权限。

```sh
git clone https://github.com/OpenRTL-Lab/jpeg-xl.git
cd jpeg-xl
git rev-parse HEAD
python3 tools/check_public_tree.py
```

记录本次完整提交号，不把浮动的`main`当作版本锁。源码依赖版本在`build.mill`，公开文件摘要在`PUBLIC_FILES.json`；第三方许可见[声明](../THIRD_PARTY.zh-CN.md)。

| 阶段 | 输入与命令入口 | 输出与通过条件 |
| --- | --- | --- |
| 1. 工具与源码 | [构建](build.zh-CN.md)；Java、Mill、Python、C++、Verilator | 版本符合锁定要求，库存与隐私检查退出0 |
| 2. Chisel→SV | `mill -i jpegxl.tool.runMain openimage.jpegxl.fast.EmitFastNative build/native` | `FastNativeEncoder.sv`及全部依赖；`check_generated.py`逐正文核同 |
| 3. RTL仿真 | [仿真操作手册](simulation.zh-CN.md)；行对照、完整原生编码、故障/取消 | 正常PASS，故意错误命中指定断言，不能只检查进程退出 |
| 4. 平台适配 | [Chipyard教程](chipyard.zh-CN.md)；固定上游、平台测试 | CSR/DMA ScalaTest通过，不将合成的结果生产者当编码器 |
| 5. 数字SoC | 同一教程；单Rocket配置、FIRRTL、firtool | 配置检查、设备树、原生BlackBox实例及完整RTL输入清单 |
| 6. 驱动与ELF | [驱动教程](driver.zh-CN.md)；宿主协议测试、RV64交叉编译 | 宿主PASS、ELF头/符号正确；编译不等于执行 |
| 7. 系统/FPGA验证 | 自己的平台提供启动、内存、物理约束 | 程序实际报告完整文件/填充通过；不能沿用历史板测结论 |
| 8. 性能复算 | `python3 tools/check_measurements.py`及[性能说明](performance.zh-CN.md) | 重算3168任务、时间窗/请求计数及三轮统计一致 |

阶段2/3使用Chisel7；阶段4/5只编译Chisel6平台源，使用阶段2生成的SV作BlackBox实现。不要把`src/main/scala`整体塞进Chipyard的Chisel6源集。两条编译链由RTL端口合同连接，不靠混用Scala类。

## 最短路径与完整路径

只想检查环境：先执行库存、宿主检查和行测试，不要求RISC-V交叉工具链或Chipyard。要检查完整编码结果：运行完整原生RTL测试；它读真实像素、经DUT编码后逐512位拍比较完整官方文件及填充，不是仅比较文件头。要把IP加入自己的SoC：继续平台测试与数字SoC生成，然后移植驱动。

本仓提供的是数字组件和通用例子，不包含物理开发板、引脚、DDR厂商IP或下载脚本。第7阶段需要自己的平台工程；不应因为没有私有板文件而跳过第1～6阶段，也不能声称教程给出了可通用于任意板卡的比特流。

## 记录自己的结果

保留完整Git提交、工具版本、命令/退出码、`build/*/completion.json`、生成文件摘要及测试日志。为每次新执行选择新输出目录，避免混合正向、负向和旧版产物。主机测试、真实RTL、目标ELF构建、系统执行、板测和实现时序分别记录。历史136项RTL与三轮板测属于所附实现证据，读者本次只跑哪几项就只报告哪几项。
