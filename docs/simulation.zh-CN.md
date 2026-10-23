<!-- OpenRTL Lab | JPEG XL | version 1.0.0 | SPDX-License-Identifier: MIT -->
# RTL 仿真操作手册

[English](simulation.md)

从本仓根目录运行。需要[构建工具](build.zh-CN.md)及Verilator 5.050、C++17、Make。以下是行为级硬块模型，不定义`JXL_FPGA_RAM`；这不等于AMD原语或布局布线后仿真。测试代码在`src/tool/scala`，DUT在`src/main/scala`或生成SV，C++文件只驱动时钟/复位，不提供编码答案。

## 1. 先跑便宜的宿主与行对照

```sh
python3 -m unittest discover -s tests -v
python3 tools/check_host.py --out build/host
python3 tools/run_row_test.py --out build/row
```

前两项不执行RTL。最后一项从Chisel分别生成正常/故意错误的`FastLengthRowMiter`，用Verilator启用`--assert`编译运行。正常日志应包含`FAST_ROW_MITER_PASS`，负向日志应包含`FAST_ROW_MITER_COST`且非零退出；包装脚本在两者都满足时退出0。输出在`build/row/{normal,negative}-*.log`和`completion.json`。不同机器编译耗时不同，不能把负向断言的预期失败当工具故障。

## 2. 准备真实原始像素与官方完整文件

```sh
python3 tools/prepare_native_fixtures.py --out build/native-fixtures
```

该工具读取`tests/fixtures/fixtures.json`和64个公开二进制，逐项核SHA/长度，再按小端64字节拍补零生成测试ROM：原始像素、完整文件、每图起始偏移、尺寸/通道/tag及故障测试第0图。它不预测像素、不生成码表、不运行编码算法。`completion.json`给出`raw_words`、`file_words`和前缀；这些ROM只连测试环境，不能合入产品RTL。

可选地重新执行上游真值，而非信任已有捕获：

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

应出现`OFFICIAL_FILE_OBSERVER_PASS images=32`且64个文件逐字节核同。此步骤需要互联网和上游源码，但不需要私有数据或完整libjxl动态库构建。上游BSD许可/专利声明仍适用。

## 3. 完整原生编码器仿真

先按[构建指南](build.zh-CN.md)生成`build/native`并核正文，再运行：

```sh
python3 tools/run_native_test.py --rtl build/native \
  --suite native --mode both --out build/native-sim
```

脚本新建自己的夹具、发射`FastNativeTest`、编译给定产品SV及测试器，使用`tools/fast_native_clock.cpp`执行。两阶段各32图，覆盖常规内存与读写/结果反压、ID乱序响应、稳定offer和所有未结事务排空；比较每个输出拍的全部字节及末拍零填充。正常必须出现两个`FAST_NATIVE_PHASE_PASS`、64个`FAST_NATIVE_COMPLETE`及`FAST_NATIVE_SUITE_PASS`。负向翻转运行期加载的 golden ROM 一位，执行**同一个二进制**，必须命中`FAST_NATIVE_OFFICIAL_BYTES`，随后恢复参考文件并核二进制摘要；不修改DUT、不关闭任何产品断言。`--mode both`的原生编码器只编译一次，禁止把任意其他断言或超时算作负测通过。

若只核随仓分发SV，可省略`--rtl`，默认`rtl/native`；不要同时混入这两套产品。完整产品编译比行测试慢得多，日志会持续写入`normal-compile.log`等；仿真阶段有周期/墙钟上限。成功收据必须`status=0`、`finished=true`、两项`expected_result=true`。

## 4. 故障、取消及恢复

```sh
python3 tools/run_native_test.py --rtl build/native \
  --suite fault --mode both --out build/native-fault
```

16个场景产生20个任务结果，涵盖非法尺寸/通道/对齐/地址溢出、重叠与重复tag、输出上限、读写错误、取消与未找到取消、后续好任务。正常需16个`FAST_NATIVE_FAULT_CASE_PASS`及`FAST_NATIVE_FAULT_SUITE_PASS`；负向需`FAST_NATIVE_FAULT_STATUS`断言。取消ACK并不代替任务排空；测试明确检查返回结果前无未结响应。

## 5. 手工定位与进一步扩展

`--mode normal`或`negative`可单独执行一个变体，仍必须新输出目录。脚本保留生成SV、Verilator `obj`、完整emit/compile/run日志和原始退出码。没有PASS、超时、重复SV模块、ROM路径错误或缺少原语都不是成功；检查最早失败的日志，保留其目录，修复后换新目录。

更多模块测试入口在`src/tool/scala/openimage/jpegxl/fast/*Test.scala`，对应时钟驱动在`tools/*_clock.cpp`，参考观察器在`reference/`；入口的`require(args.length...)`定义参数，不能套用原生测试的ROM格式到其他模块。历史136项包含这些专项覆盖，不等于本页三套脚本已经自动重跑了全部136项。

平台CSR/DMA测试在[Chipyard教程](chipyard.zh-CN.md)单独运行。原语级验证需合法安装提供`DSP48E2`、`RAMB36E2`、`URAM288_BASE`及相关库，再定义`JXL_FPGA_RAM`；必须单独记录工具/库版本和测试结果，不能把本页行为级PASS提升为原语级PASS。
