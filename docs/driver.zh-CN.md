<!-- OpenRTL Lab | JPEG XL | version 1.0.0 | SPDX-License-Identifier: MIT -->
# 驱动编写、构建与调试

[English](driver.md)

先完成[数字SoC接线](chipyard.zh-CN.md)，再写驱动。当前可执行参考为`software/jxl_mmio.h`和裸机`software/jxl_benchmark.c`；不是Linux内核驱动。本页先给单生产者轮询流程，再说明多任务、中断和Linux移植，不能把宿主假寄存器测试当作设备执行。

## 1. 寄存器及最小状态机

CSR默认`0x10040000`，窗口4KiB，只使用对齐32位读写。不要用字节写、64位MMIO或普通指针优化掉访问。先读ID=`0x4a584c31`和ABI=`0x00010000`，不匹配则停止，不提交DMA。

| 偏移 | 作用 |
| --- | --- |
| `0x00/0x04` | ID/ABI，只读 |
| `0x08` | FLAGS：bit0可提交，bit1有结果，bit2有取消ACK，bit3全静止 |
| `0x0c` | outstanding，不能只靠软件提交数猜测硬件状态 |
| `0x10/0x14` | IRQ使能/只读pending；bit0结果、bit1取消ACK |
| `0x20..0x48` | tag、宽高通道、源/目的地址低高位、两段范围、输出上限 |
| `0x4c` | SUBMIT写1，描述符影子值在握手时提交 |
| `0x50/0x54` | cancel tag/写1取消请求 |
| `0x58/0x5c` | 结果/取消ACK POP，各写1 |
| `0x60..0x90` | 稳定结果快照：tag、状态、长度、故障地址、三个64位周期及访存次数 |
| `0x98/0x9c` | 取消ACK tag/found |

完整字段枚举/位宽见[头文件](../software/jxl_mmio.h)。64位快照按低32/高32读取；因为POP前快照稳定，不是对自由运行计数器的撕裂读。先检查FLAGS的valid再读结果区，否则总线可能返回denied。先拷贝全部结果，再POP。

原生状态码：0成功、1非法命令、2范围重叠、3重复tag、4读错误、5写错误、6输出上限不足、7取消。`jxl_try_complete`返回`JXL_OK`只表示取到了结果，**不表示`completion.status==0`**。

## 2. DMA缓冲与所有权

每图按交错8位Gray/GrayA/RGB/RGBA排列，无行跨度。源/目的总线地址和分配范围都64字节对齐；源范围覆盖`roundUp(width*height*channels,64)`。输出分配向上取整并给足容量；不能将压缩一定变小当上界。平台允许范围为`[0x80000000,0x180000000)`，地址是DMA可见总线地址，不是用户/内核虚拟指针。

CPU填写像素→完成平台DMA同步→写描述符→fence→SUBMIT→轮询/中断→读取稳定快照并POP→DMA同步回CPU→检查状态与长度→使用输出。输入/输出不能相交，所有保留任务之间也不能复用源或目的分配。输入末拍需要足够已分配padding；成功输出仅到`output_bytes`是文件，其后至64字节边界为零。

RISC-V `fence iorw,iorw`负责顺序，**不能代替非一致性缓存的clean/invalidate**。一致性SBUS才可像示例那样用普通共享内存配合fence。非一致性平台必须增加缓存维护或使用合适DMA分配；禁止只加`volatile`冒充一致性。

## 3. 编译单任务轮询示例

`software/examples/poll_once.c`提供可复用`jxl_example_poll_once`，调用者注入`jxl_io`三种回调和自己的已分配DMA缓冲。先独占设备且检查静止，关闭IRQ，再非阻塞提交/轮询；超时返回`JXL_EXAMPLE_STILL_OWNED`，明确保留缓冲，不自动复位或释放。

```sh
mkdir -p build/driver
cc -std=c11 -Wall -Wextra -Werror -Isoftware \
  -c software/examples/poll_once.c -o build/driver/poll-host.o
python3 tools/check_host.py --out build/driver/host
python3 tools/check_driver_example.py --out build/driver/example
riscv64-unknown-elf-gcc -std=c11 -O2 -Wall -Wextra -Werror \
  -march=rv64imafdc -mabi=lp64d -mcmodel=medany -ffreestanding \
  -Isoftware -c software/examples/poll_once.c -o build/driver/poll-rv64.o
```

裸机回调与任务示例（调用前须实际保留相应内存，不能照抄到未知平台）：

```c
struct jxl_io io = { (void *)(uintptr_t)JXL_MMIO_BASE,
                    jxl_phys_read, jxl_phys_write, jxl_phys_fence };
struct jxl_job job = { .tag = 1, .width = 256, .height = 256, .channels = 3,
  .source = UINT64_C(0x90000000), .destination = UINT64_C(0x90080000),
  .source_span = 196608, .destination_span = 524288, .output_limit = 524288 };
/* Fill source pixels and synchronize DMA before jxl_try_submit(&io, &job).
 * Reap by tag with jxl_try_complete; check completion.status before length.
 * Do not release either buffer on timeout or a cancel acknowledgment. */
```

需用本地C编译器运行协议测试观察`JXL_MMIO_HOST_CONTRACT_PASS`和`JXL_CPU_BUFFERS_PASS`。它们不访问MMIO地址。RV64对象必须由交叉编译器生成，宿主对象不可当ELF加载。

## 4. 构建完整测试程序 ELF

```sh
riscv64-unknown-elf-gcc -std=c11 -O2 -Wall -Wextra -Werror \
  -march=rv64imafdc -mabi=lp64d -mcmodel=medany \
  -ffreestanding -fno-builtin -nostdlib -nostartfiles \
  -Isoftware -Itests/fixtures -Wl,-T,software/link.ld \
  -Wl,--build-id=none software/start.S software/jxl_benchmark.c \
  -o build/driver/jxl-benchmark.elf
riscv64-unknown-elf-readelf -h -l build/driver/jxl-benchmark.elf
riscv64-unknown-elf-nm build/driver/jxl-benchmark.elf
```

要求ELF64/RISC-V、入口`_start`、`main`及`jxl_trap`符号存在。链接脚本将代码/数据放在`0x80000000`起8MiB，保留64KiB栈；程序额外使用`0x90000000`起每slot 2MiB的32组测试缓冲，目的相对源`+0x80000`。启动器必须为这些地址保留真实内存。单hart M-mode、`satp=0`、FPU/D扩展和trap设置均要匹配；其他模式请移植启动文件。

默认SiFive兼容UART地址`0x10020000`及50MHz周期参数是数字平台合同，不自动适配任意Chipyard UART。查DTS并修改UART地址/频率/内存布局后再运行。仿真可加`-DJXL_SOC_SIM -DJXL_FIXTURE=0`使用HTIF签名而非UART，随后需要自己的FESVR/启动环境；编译这两个宏不等于已运行。

先实际执行fixture0的1+32任务，完整官方文件和末拍padding都通过后，再执行32图1056任务。检查`JXL_BOARD_PASS`（或仿真的`JXL_SOC_PASS`）以及全部任务记录；trap、FAIL或只有UART启动信息都不是通过。加载器、JTAG及物理下载操作由自己的平台提供，本仓不提供或泄露具体板卡配置。

`JXL_SOC_SIM` 分支还在固定 PLIC 地址检查中断号 5。启用前必须依据**自己生成的 DTS**修改这些常量；通用单 Rocket 示例不保证分配 IRQ 5。这与禁用 IRQ 的轮询示例是两个不同流程。

## 5. 多任务、取消、中断及超时

硬件最多保留4个任务/结果槽。`jxl_try_submit`在FLAGS bit0为0时返回`JXL_AGAIN`；不要在CPU上阻塞写第5个SUBMIT而又不POP结果，否则可能死锁。一个锁保护影子CSR序列和结果快照读取；按tag匹配完成，不能按提交顺序回收。相同tag未完成前不得复用。

取消流程：发送cancel tag/doorbell，读FLAGS bit2、ACK tag/found并POP；found表示找到了任务，不表示DMA已经停止。仍等待相同tag的终态结果并排空。超时是软件观测，不能擅自释放/重用DMA缓冲；恢复流程要完成取消/终态或在系统级证实master已隔离且全部事务结束。

IRQ为电平信号，pending来自结果/取消ACK的valid，不是W1C寄存器。先接PLIC并注册ISR，再启用`IRQ_ENABLE`相应位；ISR读取/保存快照、POP并唤醒等待者，最后完成PLIC协议。IRQ编号从DTS读取，不写死其他板的编号。板上历史测试用轮询，新增ISR必须另测中断到达、屏蔽、重复触发、队列满和拆卸。

## 6. 写 Linux platform 驱动时

这是一条移植步骤，不是仓库里已有受测`.ko`。不要直接将含`stdint.h`的裸机头纳入内核；按同一ABI用`u32/u64`、`readl/writel`实现内核版本。固定你的内核提交、配置、RISC-V交叉工具链和设备树，按[Linux DMA官方指南](https://docs.kernel.org/core-api/dma-api-howto.html)区分CPU虚拟地址与DMA地址。

1. 用`of_match_table`匹配`openrtl,jpegxl-fast-v1`，`devm_platform_ioremap_resource`映射4KiB，`readl`探测ID/ABI；用`dma_set_mask_and_coherent(dev,DMA_BIT_MASK(33))`设置上限，额外检查设备实际DDR窗口。
2. 第一版用内核拥有的`dma_alloc_coherent`源/目的缓冲及bounce copy，或正确`dma_map_single`/sync；检查映射失败、64字节对齐、范围溢出、长度、权限及不相交。不能把用户指针/`virt_to_phys`写入设备，也不接受任意用户指定物理地址。IOMMU映射也必须落在桥接受的窗口。
3. 对`SUBMIT`设计固定宽度ioctl结构：tag/宽高通道、用户数据指针/长度、输出容量；使用`copy_from_user`，验证乘法溢出后分配/填数据，由内核生成DMA地址。用mutex保护CSR，先`dma_wmb()`再门铃。通过`POLL/WAIT/READ_RESULT`按tag交付结果，检查硬件状态、长度不超容量，再`copy_to_user`。
4. ISR用`platform_get_irq`取得真实中断，`devm_request_irq`注册；只在完成快照已保存并POP后归还tag/缓冲。流式映射用相应`dma_sync_*`，即使一致性内存也不能省去必要顺序屏障。不要把裸机`volatile`回调原样代替内核MMIO API。
5. close、超时、取消、进程退出和remove期间停止新提交、关闭/同步IRQ、排空已提交DMA；没有安全排空证据不得`dma_free_coherent`或解除映射。先实现单打开/独占设备，再扩展并发和用户缓冲锁页。
6. 分别执行Kbuild交叉编译、宿主/内核负测和真实设备测试；至少测坏用户指针、非法长度/tag、四槽满、乱序结果、取消、非一致性缓存、32位兼容ioctl及remove竞态。不要把本仓裸机结果作为Linux驱动资格。
