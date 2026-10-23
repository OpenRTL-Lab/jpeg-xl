<!-- OpenRTL Lab | JPEG XL | version 1.0.0 | SPDX-License-Identifier: MIT -->
# 在 Chipyard 中拼装数字 SoC

[English](chipyard.md)

本页组装一个公开、通用的单Rocket系统，不选物理开发板。先完成[组件生成](build.zh-CN.md)和[原生RTL测试](simulation.zh-CN.md)。平台使用Scala 2.13.12/Chisel 6.5.0、chiseltest 6.0.0、ScalaTest 3.2.19；原生产品仍由Chisel7生成，两者不混编。系统下层生成采用firtool 1.75.0；不要将组件的1.149.0依赖自动替换成系统工具。

## 1. 固定上游并初始化环境

从包含`jpeg-xl/`的父目录开始，选择新的`chipyard/`：

```sh
git clone https://github.com/ucb-bar/chipyard.git
git -C chipyard checkout --detach 69eba860a352343e4ac6b6df0f3638a79a86ec78
cd chipyard
./build-setup.sh riscv-tools
source env.sh
git rev-parse HEAD
git submodule status --recursive
sbt 'show sbtVersion'
firtool --version
```

按该固定上游的[环境说明](https://github.com/ucb-bar/chipyard/tree/69eba860a352343e4ac6b6df0f3638a79a86ec78)安装宿主包/Conda前提。上游脚本会下载大量公开依赖并初始化子模块；记录输出，不用自己的浮动Rocket版本代替gitlink锁。正常已初始化的子模块行不应以`-`或`+`开头。Java和RISC-V工具链必须在当前shell可用。

## 2. 安装源集，而不是把整个生成器并入SoC

回到`jpeg-xl/`根目录：

```sh
cd ../jpeg-xl
python3 tools/install_chipyard.py --chipyard ../chipyard
```

安装器核上游HEAD，复制`integration/`、`examples/`和`rtl/`到`chipyard/generators/jpegxl/`，并将`integration/chipyard/jpegxl.sbt`安装为上游根的`jpegxl.sbt`。目标已存在时拒绝覆盖。**不复制本仓根`build.mill`和Chisel7组件源集。** SBT新增：

- `jpegxlPlatform`依赖上游`rocketchip`，编译平台CSR/DMA、`JxlKey`及`CanHaveJxl`。
- `jpegxlSoc`依赖`chipyard`和`jpegxlPlatform`，编译`examples/chipyard/.../JxlRocketConfig.scala`。

该示例的`JxlExampleSystem extends chipyard.DigitalTop with CanHaveJxl`是实际系统挂接点；`WithJxlExampleSystem`替换`chipyard.BuildSystem`，`WithJxl`设置`JxlKey`，`JxlRocketConfig`选择单核Rocket。只加`WithJxl`却不把trait混入系统，不会实例化编码器。

## 3. 平台单测与配置检查

这些chiseltest 6测试使用固定上游环境中的 **Verilator 5.022**，独立Chisel7组件脚本使用5.050。source Chipyard环境后检查`which verilator`与`verilator --version`。不要让搬迁的可执行文件使用另一套安装的`VERILATOR_ROOT`。特别是5.050移除的全局`WData`类型使旧JNI测试器无法编译；这是测试工具不匹配，不是RTL错误。两套环境请分别使用。

以下工作目录为`chipyard/`：

```sh
cd ../chipyard
sbt 'jpegxlPlatform/testOnly jpegxl.platform.JxlCsrSpec jpegxl.platform.JxlDmaSpec'
sbt 'jpegxlSoc/compile' 'jpegxlSoc/runMain jpegxl.example.CheckJxlRocketConfig'
```

测试使用Verilator真实执行CSR/DMA RTL，但CSR测试的结果生产者是接口激励，不是JPEG XL编码器。需要ScalaTest的全部测试通过及`JXL_EXAMPLE_CONFIG_PASS cores=1 sbus_bits=128 mbus_bits=512`；这仍不是整个SoC的软件执行。

## 4. 总线、时钟、地址及中断接线

`CanHaveJxl`实现这些实际连接：

| 接口 | 连接与要求 |
| --- | --- |
| CSR | 128位SBUS→宽度/分片/缓冲→32位CSR管理端，4KiB窗口`0x10040000` |
| DMA | 原生512位访存→`JxlDmaBridge`→宽度/缓冲→一致性SBUS；读source 0～15，写16～31 |
| 时钟 | `encoder.clockNode := sbus.fixedClockNode`，本例不跨异步时钟域 |
| 复位 | 域复位转换为原生低有效`rst_n`；带任务时复位须按系统规则处理未结DMA |
| 中断 | `ibus.fromSync := encoder.intNode`，进入PLIC；编号以本次生成DTS为准 |
| 内存 | `[0x80000000,0x180000000)`、4GiB；内部MBUS为512位，转换为64位外部AXI以满足上游SimDRAM限制 |

桥中DDR窗口是硬编码的上述范围，不是`JxlParams`可配置项。移植到另一窗口时，要同步更新DMA过滤、软件`jxl_region`、链接/分配和相应测试。不能仅改设备树。原生协议本身支持33位地址，但平台允许范围更小；示例必须完整保证64字节Get/Put和缓存一致性。

## 5. 生成系统 FIRRTL、设备树及 SV

先构建上游BootROM，确保交叉GCC/objcopy在PATH。输出目录必须新建；ROM要放在生成器相对解析的目标目录中：

```sh
make -C generators/testchipip/bootrom bootrom.rv64.img \
  GCC=riscv64-unknown-elf-gcc OBJCOPY=riscv64-unknown-elf-objcopy
mkdir -p build/jxl-example/generated
cp generators/testchipip/bootrom/bootrom.rv64.img build/jxl-example/generated/
sbt 'jpegxlSoc/runMain chipyard.Generator --target-dir build/jxl-example/generated --top-module chipyard.harness.TestHarness --configs jpegxl.example.JxlRocketConfig --name JxlExample'
firtool --format=fir --verify-each=true --warn-on-unprocessed-annotations \
  --disable-annotation-classless --disable-annotation-unknown \
  --lowering-options=noAlwaysComb,disallowLocalVariables \
  --annotation-file=build/jxl-example/generated/JxlExample.anno.json \
  --split-verilog -o build/jxl-example/rtl \
  build/jxl-example/generated/JxlExample.fir
```

应产生`JxlExample.fir`、`.anno.json`、`.dts`及系统SV。检查DTS内`jpegxl@10040000`的`openrtl,jpegxl-fast-v1`、`reg`、`interrupts`、4GiB memory范围和一个CPU；不要把其他系统的PLIC source编号写死到驱动。检查`JxlTileLink`内真实`FastNativeEncoder`实例，并确认没有第二份同名产品模块。

本示例实际生成的 DTS 中编码器中断号为 2，而非 benchmark 仿真分支写死的 5；请检查自己生成的 DTS。搜索生成的 SV 中编码器时钟域内的 `FastNativeEncoder core` 实例；LazyModule 的 Scala 类名不一定保留为模块文件名。

## 6. BlackBox必须有实现：组合两套RTL输入

`JxlNativeBlackBox`只声明端口，**不会自动把产品SV加入SBT/firtool输出**。安装器只复制数字源集；在Chipyard根目录从原仓运行清单脚本：

```sh
python3 ../jpeg-xl/tools/make_soc_filelist.py \
  --soc-rtl build/jxl-example/rtl \
  --native-rtl generators/jpegxl/rtl/native \
  --out build/jxl-example/combined.f
```

清单显式纳入原生产品、断言层与包含路径，以及系统发射/资源SV。脚本拒绝重复模块文件并检查关键模块，不能用空壳或stub消除未解析BlackBox。若要用新发射产品，将`--native-rtl`换成阶段2的输出目录。不要将验证测试顶层误当综合顶层。

继续整个SoC仿真时还需上游TestHarness的DPI/内存/时钟模型和FESVR等链接库。该清单是RTL输入，不是完整C++链接命令；普通`verilator -f combined.f`不等于可执行SoC。遵循上游`sims/verilator` Makefile，注册`jpegxlSoc`为所选SBT项目、`jpegxl.example.JxlRocketConfig`为配置，并把`combined.f`中的产品输入附加到编译中。`software/jxl_benchmark.c`的`JXL_SOC_SIM`分支支持HTIF签名传输，但不能省去仿真启动/内存加载适配。

使用上游Make流程时，生成**仅含原生产品**的外部列表；Make自己提供系统文件，不要再把`combined.f`重复传入。先在`jpeg-xl/`，再到`chipyard/`：

```sh
python3 tools/make_soc_filelist.py --native-rtl rtl/native --out build/jxl-native.f
cd ../chipyard
JXL_NATIVE_FILELIST=$(realpath ../jpeg-xl/build/jxl-native.f)
make -C sims/verilator -j2 SBT_PROJECT=jpegxlSoc GENERATOR_PACKAGE=chipyard \
  CONFIG_PACKAGE=jpegxl.example CONFIG=JxlRocketConfig \
  EXT_FILELISTS="$JXL_NATIVE_FILELIST"
```

这个可选命令构建上游仿真器，不生成板镜像。运行软件前，先按本次DTS/PLIC及启动环境移植[ELF](driver.zh-CN.md)，定义`JXL_SOC_SIM`编译，再向同一Make命令追加`run-binary-fast BINARY=<your-ELF> LOADMEM=1`。`<your-ELF>`替换为真实ELF绝对路径，不要照抄占位符。检查HTIF退出/签名及完整文件结果，不能只看仿真器编译成功。整SoC的软件执行是另一个资格阶段，原生与适配器测试不能替代。

## 7. 接入自己的平台

保留`CanHaveJxl`和两套项目边界，可以将系统trait混入你已有的`BaseSubsystem`子类，或继承此通用例子。重新核CSR地址冲突、缓存一致性、DDR窗口、复位、PLIC分配及启动。先[编写轮询驱动](driver.zh-CN.md)，再测中断。厂商DDR控制器、IO/时钟约束、综合/布局布线及下载需要自己的平台工程；本页不包含任何具体板卡信息或整板产物。
