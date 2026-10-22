<!-- OpenRTL Lab | JPEG XL | version 1.0.0 | SPDX-License-Identifier: MIT -->
# 构建与集成

[English](build.md)

## 工具与组件生成

命令均从仓库根目录执行。组件生成器使用 Mill 1.1.7、Scala 2.13.18、Chisel 7.13.0、CIRCT firtool 1.149.0 和 Java 17。宿主检查需要 Python 3.11或更高版本及C/C++编译器，RTL仿真需要 Verilator。依赖来自公开Maven仓库，不需要私有构建产物。

```sh
python3 tools/check_public_tree.py
python3 tools/check_host.py --out build/host
python3 tools/check_measurements.py
mill -i jpegxl.tool.runMain openimage.jpegxl.fast.EmitFastNative build/native
python3 tools/check_generated.py build/native
```

`check_host.py` 编译并执行MMIO和缓冲辅助函数测试，失败返回非零，不运行RTL。生成器输出原生产品模块和验证层。`check_generated.py` 逐一比较生成SV的实现正文与发布清单，仅忽略准确新增的分发头。比较失败须定位原因，不能把已提供的RTL复制进输出目录以通过。使用全新输出目录。`rtl/native/rtl.f` 以相对 `rtl/native/` 的路径列出用于综合的分发模块；仅测试使用的验证层另行保留。

独立算术行测试及故意注错拒绝检查：

```sh
python3 tools/run_row_test.py --out build/row
```

该流程从提供的Chisel生成两个测试顶层，分别以Verilator编译，要求正常测试通过且负向测试出现预期失败，不是完整编码器仿真。其他测试生成器和时钟驱动也提供用于深入集成测试；覆盖范围见[验证](verification.zh-CN.md)。

## 原生 RTL 与硬块

生成的原生顶层端口见[接口](interfaces.zh-CN.md)。定义 `JXL_FPGA_RAM` 时，自研包装层显式实例化 `DSP48E2`、`RAMB36E2` 和 `URAM288_BASE`；原语实现和仿真库须由获许可的AMD/Xilinx安装提供。不定义该宏时，包装层提供行为级算术和同步内存模型。行为仿真不能当作原语级或布局布线后签核。原语包装源码开源，不重新分发厂商实现。

## 数字适配器，不是完整开发板系统

平台适配源使用Scala 2.13.12、Chisel 6.5.0及Rocket/Chipyard API，故意与Chisel 7组件生成器分开。单测使用chiseltest 6.0.0及ScalaTest 3.2.19。上游Chipyard参考为 `https://github.com/ucb-bar/chipyard.git` 的提交 `69eba860a352343e4ac6b6df0f3638a79a86ec78`。可复用系统trait接收子系统、连接CSR/DMA/中断，不选择物理开发板。

在兼容Chipyard工程加入平台源集，把 `WithJxl` 加入系统配置，将 `CanHaveJxl` 混入子系统，并为 `JxlNativeBlackBox` 连接生成的 `FastNativeEncoder` 实现。明确匹配128位系统总线/512位内存适配假设、DDR地址范围、复位转换及中断路由。提供平台源码和测试，但本次分发未对该通用混入方式重新完成整个数字SoC构建资格检查；不承诺一键系统生成，也不包含私有实测物理系统配置。

## 裸机软件

`software/jxl_benchmark.c` 检查完整官方文件及输出填充，要求RV64一致性内存平台、文档中CSR映射、位于 `0x10020000` 的SiFive兼容UART、50MHz周期计数器、合适DDR分配以及提供的启动/链接布局。使用前应适配这些数字平台合同。程序不配置FPGA，也不提供JTAG连接。

```sh
riscv64-unknown-elf-gcc -std=c11 -O2 -Wall -Wextra -Werror \
  -march=rv64imafdc -mabi=lp64d -mcmodel=medany \
  -ffreestanding -fno-builtin -nostdlib -nostartfiles \
  -Isoftware -Itests/fixtures -Wl,-T,software/link.ld \
  -Wl,--build-id=none software/start.S software/jxl_benchmark.c \
  -o build/jxl-benchmark.elf
```

编译ELF不等于执行。物理开发板下载、复位、启动固件、引脚约束、时钟、DDR控制器及专有IP配置不在本仓库范围。既有FPGA测量不能证明新适配的系统已经通过。
