// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl.fast

import chisel3._
import chisel3.util._

class FastEncoderCommand extends Bundle {
  val context = Bool(); val tag = UInt(32.W)
  val imageWidth = UInt(9.W); val imageHeight = UInt(9.W); val channels = UInt(3.W)
  val baseByteOffset = UInt(18.W); val outputLimit = UInt(24.W)
}
/** Internal native-local encoding core. Raw bytes/parameters only.
  * External DMA responses, cancellation and completion queues belong above.
  */
class FastEncoderCore extends RawModule {
  val clock = IO(Input(Clock())); val rst_n = IO(Input(Bool()))
  val io = IO(new Bundle {
    val write = Flipped(Decoupled(new FastPixelWrite))
    val command = Flipped(Decoupled(new FastEncoderCommand))
    val output = Decoupled(new FastFileWord)
    val busy = Output(Vec(2,Bool()))
  })
  val front = Module(new FastImageFront); val run = Module(new FastRunPlanner)
  val statistics = Module(new FastStatistics); val prefix = Module(new FastPrefixMemoGroup)
  val descriptors = Module(new FastHeaderGroup); val globalHeader = Module(new FastGlobalHeader)
  val pack = Module(new FastSymbolPack8); val pool = Module(new FastBitPool)
  val files = Module(new FastFileTransport)
  front.clock := clock; front.rst_n := rst_n; run.clock := clock; run.rst_n := rst_n
  statistics.clock := clock; statistics.rst_n := rst_n; prefix.clock := clock; prefix.rst_n := rst_n
  descriptors.clock := clock; descriptors.rst_n := rst_n
  globalHeader.clock := clock; globalHeader.rst_n := rst_n
  pack.clock := clock; pack.rst_n := rst_n; pool.clock := clock; pool.rst_n := rst_n
  files.clock := clock; files.rst_n := rst_n
  withClockAndReset(clock,(!rst_n).asAsyncReset) {
    val idle :: statsCommand :: sampleCommand :: waitCode :: inject :: encodeCommand :: waitEncode :: sealedFile :: streaming :: Nil = Enum(9)
    val state = RegInit(VecInit(Seq.fill(2)(idle)))
    val commands = Reg(Vec(2,new FastEncoderCommand))
    val tables = Reg(Vec(2,Vec(4,new FastPrefixResult)))
    val globalBits = Reg(Vec(2,UInt(2048.W))); val globalLength = Reg(Vec(2,UInt(12.W)))
    val globalPosition = Reg(Vec(2,UInt(4.W)))
    val commandContext = io.command.bits.context.asUInt
    io.command.ready := rst_n && state(commandContext)===idle
    for (i <- 0 until 2) io.busy(i) := state(i)=/=idle
    when(io.command.fire) {
      val in=io.command.bits
      assert(in.imageWidth>=1.U && in.imageWidth<=256.U && in.imageHeight>=1.U && in.imageHeight<=256.U &&
        in.channels>=1.U && in.channels<=4.U,"FAST_CORE_COMMAND_FIELDS")
      assert(in.baseByteOffset+&(in.imageWidth*in.imageHeight*in.channels)<=262144.U,
        "FAST_CORE_RAW_CAPACITY")
      commands(commandContext) := in; state(commandContext) := statsCommand
    }
    front.io.write.bits := io.write.bits
    front.io.write.valid := io.write.valid && state(io.write.bits.context.asUInt)===idle
    io.write.ready := front.io.write.ready && state(io.write.bits.context.asUInt)===idle

    val statsCommands = Module(new RRArbiter(new FastStatisticsCommand,2))
    for (i <- 0 until 2) {
      val saved=commands(i)
      statsCommands.io.in(i).valid := state(i)===statsCommand
      statsCommands.io.in(i).bits.context := i.U.asBool
      statsCommands.io.in(i).bits.tag := saved.tag
      statsCommands.io.in(i).bits.channels := saved.channels
      val empty=saved.imageHeight<128.U || saved.imageWidth<8.U
      statsCommands.io.in(i).bits.empty := empty
      when(statsCommands.io.in(i).fire) { state(i) := Mux(empty,waitCode,sampleCommand) }
    }
    statistics.io.command <> statsCommands.io.out
    val scans = Module(new RRArbiter(new FastScanCommand,2))
    for (i <- 0 until 2) {
      val saved=commands(i); val sampling=state(i)===sampleCommand
      scans.io.in(i).valid := sampling || state(i)===encodeCommand
      scans.io.in(i).bits.context := i.U.asBool; scans.io.in(i).bits.tag := saved.tag
      scans.io.in(i).bits.imageWidth := saved.imageWidth; scans.io.in(i).bits.imageHeight := saved.imageHeight
      scans.io.in(i).bits.channels := saved.channels; scans.io.in(i).bits.baseByteOffset := saved.baseByteOffset
      scans.io.in(i).bits.sampling := sampling
      when(scans.io.in(i).fire) { state(i) := Mux(sampling,waitCode,waitEncode) }
    }
    front.io.command <> scans.io.out
    front.io.empty.ready := true.B
    assert(!front.io.empty.valid,"FAST_CORE_EMPTY_SAMPLING_NOT_ISSUED")
    run.io.input.valid := front.io.output.valid
    front.io.output.ready := run.io.input.ready
    run.io.input.bits.block := front.io.output.bits.block
    run.io.input.bits.context := front.io.output.bits.metadata.context
    run.io.input.bits.first := front.io.output.bits.metadata.first
    run.io.input.bits.last := front.io.output.bits.metadata.last
    run.io.input.bits.sampling := front.io.output.bits.metadata.sampling
    val sample=run.io.output.bits.input.sampling
    statistics.io.input.valid := run.io.output.valid && sample
    statistics.io.input.bits := run.io.output.bits
    pack.io.input.valid := run.io.output.valid && !sample
    pack.io.input.bits.planned := run.io.output.bits
    pack.io.input.bits.tables := tables(run.io.output.bits.input.context.asUInt)
    run.io.output.ready := Mux(sample,statistics.io.input.ready,pack.io.input.ready)
    val samples = Module(new Queue(new FastStatisticsResult,2))
    samples.io.enq <> statistics.io.output; prefix.io.input <> samples.io.deq
    val prefixContext=Reg(Bool()); val headerContext=Reg(Bool())
    when(prefix.io.input.fire) { prefixContext := prefix.io.input.bits.context }
    descriptors.io.input <> prefix.io.output
    when(prefix.io.output.fire) {
      val ctx=prefixContext.asUInt
      assert(state(ctx)===waitCode && prefix.io.output.bits.tag===commands(ctx).tag,
        "FAST_CORE_PREFIX_CONTEXT")
      tables(ctx) := prefix.io.output.bits.tables; headerContext := prefixContext
    }
    val globalContexts = Module(new Queue(Bool(),2))
    globalContexts.io.enq.bits := headerContext
    globalContexts.io.enq.valid := descriptors.io.output.valid && globalHeader.io.input.ready
    globalHeader.io.input.valid := descriptors.io.output.valid && globalContexts.io.enq.ready
    globalHeader.io.input.bits := descriptors.io.output.bits
    descriptors.io.output.ready := globalHeader.io.input.ready && globalContexts.io.enq.ready
    globalHeader.io.output.ready := globalContexts.io.deq.valid
    globalContexts.io.deq.ready := globalHeader.io.output.valid
    when(globalHeader.io.output.fire) {
      val ctx=globalContexts.io.deq.bits.asUInt; val out=globalHeader.io.output.bits
      assert(state(ctx)===waitCode && out.tag===commands(ctx).tag && out.channels===commands(ctx).channels,
        "FAST_CORE_GLOBAL_CONTEXT")
      globalBits(ctx) := out.bits; globalLength(ctx) := out.length
      globalPosition(ctx) := 0.U; state(ctx) := inject
    }
    val injection = Module(new RRArbiter(new FastPackedBlock,2))
    for (i <- 0 until 2) {
      val in=injection.io.in(i); val offset=Cat(globalPosition(i),0.U(8.W))
      val remaining=globalLength(i)-offset
      in.valid := state(i)===inject
      in.bits := 0.U.asTypeOf(in.bits)
      in.bits.tag := commands(i).tag; in.bits.context := i.U.asBool
      in.bits.channels := commands(i).channels; in.bits.first := globalPosition(i)===0.U
      in.bits.bits(0) := (globalBits(i)>>offset)(255,0)
      in.bits.lengths(0) := Mux(remaining>=256.U,256.U,remaining)
      when(in.fire) {
        assert(remaining>0.U && remaining<=2048.U,"FAST_CORE_GLOBAL_SLICE")
        globalPosition(i) := globalPosition(i)+1.U
        when(remaining<=256.U) { state(i) := encodeCommand }
      }
    }
    val segments = Module(new RRArbiter(new FastPackedBlock,2))
    segments.io.in(0).valid := pack.io.output.valid
    segments.io.in(0).bits := pack.io.output.bits
    segments.io.in(0).bits.first := false.B
    pack.io.output.ready := segments.io.in(0).ready
    segments.io.in(1) <> injection.io.out
    pool.io.input <> segments.io.out
    files.io.input <> pool.io.output
    when(files.io.input.fire && files.io.input.bits.last) {
      val ctx=files.io.input.bits.context.asUInt
      assert(state(ctx)===waitEncode,"FAST_CORE_FINAL_STREAM")
      state(ctx) := sealedFile
    }
    val fileCommands = Module(new RRArbiter(new FastFileCommand,2))
    for (i <- 0 until 2) {
      val in=fileCommands.io.in(i)
      in.valid := state(i)===sealedFile
      in.bits.context := i.U.asBool; in.bits.imageWidth := commands(i).imageWidth
      in.bits.imageHeight := commands(i).imageHeight; in.bits.outputLimit := commands(i).outputLimit
      when(in.fire) { state(i) := streaming }
    }
    files.io.command <> fileCommands.io.out
    io.output <> files.io.output
    when(io.output.fire && io.output.bits.last) {
      val ctx=io.output.bits.context.asUInt
      assert(state(ctx)===streaming && io.output.bits.tag===commands(ctx).tag,"FAST_CORE_RETIRE")
      state(ctx) := idle
    }
  }
}
