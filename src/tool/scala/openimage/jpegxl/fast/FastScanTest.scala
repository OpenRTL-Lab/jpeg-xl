// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl.fast

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline
import _root_.circt.stage.ChiselStage

class ExternalFastImageFront extends BlackBox {
  override def desiredName = "FastImageFront"
  val io = IO(new Bundle {
    val clock = Input(Clock()); val rst_n = Input(Bool())
    val io = new Bundle {
      val command = Flipped(Decoupled(new FastScanCommand))
      val write = Flipped(Decoupled(new FastPixelWrite))
      val output = Decoupled(new FastPixelFrontResult)
      val empty = Decoupled(new FastScanEmpty)
      val scanBusy = Output(Vec(2,Bool()))
    }
  })
}
class FastScanTest(rows: Int, loads: Int, path: String, negative: Boolean) extends Module {
  val io = IO(new Bundle { val finished = Output(Bool()) })
  def table(n: Int, bits: Int, suffix: String="", depth: Int=0): Mem[UInt] = {
    val m = Mem(if (depth==0) rows else depth,UInt(bits.W))
    loadMemoryFromFileInline(m,path+suffix+s".column-$n.hex"); m
  }
  val contexts=table(2,1); val tags=table(3,32); val layouts=table(4,3)
  // Only metadata bits are needed here. The ROM file is mechanically packed
  // to these three bits: firtool may otherwise remove unused low memory bits
  // without repacking an external $readmemh initialization file.
  val counts=table(5,4); val flags=table(6,3); val residual=table(9,512)
  val tokens=table(10,160); val bits=table(11,128); val extras=table(12,512); val prefixes=table(13,16)
  val loadBatch=table(0,6,".loads.tsv",loads); val loadContext=table(1,1,".loads.tsv",loads)
  val loadWord=table(2,12,".loads.tsv",loads); val loadData=table(3,512,".loads.tsv",loads)
  val cGroup=table(0,7,".commands.tsv",240); val cContext=table(1,1,".commands.tsv",240)
  val cTag=table(2,32,".commands.tsv",240); val cChannels=table(3,3,".commands.tsv",240)
  val cWidth=table(4,9,".commands.tsv",240); val cHeight=table(5,9,".commands.tsv",240)
  val cBase=table(6,18,".commands.tsv",240); val cSample=table(7,1,".commands.tsv",240)
  val cEmpty=table(8,1,".commands.tsv",240); val cEnd=table(9,log2Ceil(rows+1),".commands.tsv",240)
  val dut=Module(new ExternalFastImageFront)
  val phase=RegInit(0.U(3.W)); val cycle=RegInit(0.U(32.W)); cycle := cycle+1.U
  val warm=RegInit(0.U(4.W)); val resetCycles=RegInit(0.U(3.W))
  val loading=RegInit(true.B); val group=RegInit(0.U(7.W))
  val ci=RegInit(0.U(8.W)); val li=RegInit(0.U(log2Ceil(loads+1).W))
  val ri=RegInit(0.U(log2Ceil(rows+1).W)); val emptyCount=RegInit(0.U(2.W))
  val emptyTotal=RegInit(0.U(9.W)); val commandAt=Mem(240,UInt(32.W))
  val priorGroup=Reg(UInt(7.W)); val lastResult=Reg(UInt(32.W))
  val running=phase === 2.U || phase === 3.U
  val source=Mux(phase===0.U || ci>=240.U,0.U,ci)
  val loadIndex=Mux(li<loads.U,li,0.U); val groupHead=Cat(group,0.U(1.W))
  dut.io.clock := clock; dut.io.rst_n := !reset.asBool && phase =/= 1.U
  dut.io.io.write.valid := (phase===0.U || running) && loading && li<loads.U && loadBatch(loadIndex)===(group>>1)
  dut.io.io.write.bits.context := loadContext(loadIndex).asBool
  dut.io.io.write.bits.wordIndex := loadWord(loadIndex); dut.io.io.write.bits.data := loadData(loadIndex)
  when(dut.io.io.write.fire) { li := li+1.U }
  when(loading && (li===loads.U || loadBatch(loadIndex)=/=(group>>1))) { loading := false.B }
  val command=dut.io.io.command.bits
  command.context := cContext(source).asBool; command.tag := cTag(source)
  command.imageWidth := cWidth(source); command.imageHeight := cHeight(source)
  command.channels := cChannels(source); command.baseByteOffset := cBase(source)
  command.sampling := cSample(source).asBool
  dut.io.io.command.valid := !loading && (phase===0.U || (running && ci<240.U && cGroup(source)===group))
  dut.io.io.output.ready := running && (phase===2.U || cycle%23.U>=16.U)
  dut.io.io.empty.ready := running && (phase===2.U || cycle%11.U>=7.U)
  when(phase===0.U && !loading) {
    warm := warm+1.U
    when(warm===12.U) { phase := 1.U }
  }
  when(phase===1.U) {
    assert(!dut.io.io.output.valid && !dut.io.io.empty.valid && !dut.io.io.command.ready,"FAST_SCAN_RESET_CANCEL")
    resetCycles := resetCycles+1.U
    when(resetCycles===2.U) {
      phase := 2.U; loading := true.B; li := 0.U; ci := 0.U; ri := 0.U; group := 0.U
    }
  }
  when(running && dut.io.io.command.fire) {
    commandAt.write(ci,cycle); ci := ci+1.U
    printf("FAST_SCAN_COMMAND phase=%d index=%d cycle=%d\n",phase,ci,cycle)
  }
  val stall=dut.io.io.output.valid && !dut.io.io.output.ready
  val priorStall=RegNext(stall,false.B); val held=RegEnable(dut.io.io.output.bits.asUInt,stall)
  when(running && priorStall) {
    assert(dut.io.io.output.valid && held===dut.io.io.output.bits.asUInt,"FAST_SCAN_OUTPUT_HOLD")
  }
  val emptyStall=dut.io.io.empty.valid && !dut.io.io.empty.ready
  val priorEmptyStall=RegNext(emptyStall,false.B); val heldEmpty=RegEnable(dut.io.io.empty.bits.asUInt,emptyStall)
  when(running && priorEmptyStall) {
    assert(dut.io.io.empty.valid && heldEmpty===dut.io.io.empty.bits.asUInt,"FAST_SCAN_EMPTY_HOLD")
  }
  val finishGroup=WireDefault(false.B)
  when(running && dut.io.io.empty.fire) {
    val out=dut.io.io.empty.bits; val expected=groupHead+emptyCount
    assert(cEmpty(groupHead).asBool && emptyCount<2.U,"FAST_SCAN_EXPECTED_EMPTY")
    assert(out.tag===cTag(expected) && out.context===cContext(expected).asBool &&
      out.channels===cChannels(expected),"FAST_SCAN_EMPTY_IDENTITY")
    emptyCount := emptyCount+1.U; emptyTotal := emptyTotal+1.U
    printf("FAST_SCAN_EMPTY phase=%d index=%d tag=%x\n",phase,expected,out.tag)
    when(emptyCount===1.U) { finishGroup := true.B }
  }
  when(running && dut.io.io.output.fire) {
    val out=dut.io.io.output.bits; val block=out.block; val meta=out.metadata
    assert(ri<rows.U && !cEmpty(groupHead).asBool && block.tag===tags(ri),"FAST_SCAN_TAG")
    assert(block.channels===layouts(ri) && block.count===counts(ri),"FAST_SCAN_LAYOUT")
    assert(meta.context===contexts(ri).asBool && meta.first===flags(ri)(0) &&
      meta.last===flags(ri)(1) && meta.sampling===flags(ri)(2),"FAST_SCAN_METADATA")
    assert(block.residual.asUInt===(if (negative) residual(ri)^1.U else residual(ri)),"FAST_SCAN_OFFICIAL_RESIDUAL")
    assert(block.token.asUInt===tokens(ri) && block.extraBits.asUInt===bits(ri) &&
      block.extra.asUInt===extras(ri) && block.zeroPrefix.asUInt===prefixes(ri),"FAST_SCAN_OFFICIAL_SYMBOLS")
    when(phase===2.U) {
      when(meta.first) {
        assert(cycle-commandAt(block.tag(7,0))===7.U,"FAST_SCAN_FIRST_LATENCY_SEVEN")
      }
      when(ri=/=0.U && priorGroup===group) { assert(cycle-lastResult===1.U,"FAST_SCAN_IDEAL_II_ONE") }
    }
    priorGroup := group; lastResult := cycle
    printf("FAST_SCAN_RESULT phase=%d index=%d residual=%x extra=%x\n",phase,ri,block.residual.asUInt,block.extra.asUInt)
    ri := ri+1.U
    when(ri+1.U===cEnd(groupHead)) { finishGroup := true.B }
  }
  when(finishGroup) {
    assert(ci===groupHead+2.U,"FAST_SCAN_ALL_GROUP_COMMANDS")
    emptyCount := 0.U; group := group+1.U
    when(group(0)) { loading := true.B }
    when(group===119.U) {
      assert(ri===rows.U && li===loads.U,"FAST_SCAN_ALL_RECORDS")
      printf("FAST_SCAN_PHASE_PASS phase=%d commands=240 blocks=%d empty=%d\n",phase,rows.U,
        emptyTotal+Mux(dut.io.io.empty.fire,1.U,0.U))
      when(phase===3.U) { printf("FAST_SCAN_SUITE_PASS\n") }
      phase := phase+1.U; loading := true.B; li := 0.U; ci := 0.U; ri := 0.U; group := 0.U
      emptyTotal := 0.U
    }
  }
  assert(cycle<500000.U,"FAST_SCAN_WATCHDOG")
  io.finished := phase===4.U
}
object EmitFastScan {
  def main(args: Array[String]): Unit = {
    require(args.length==1)
    ChiselStage.emitSystemVerilogFile(new FastImageFront,Array("--target-dir",args(0)),
      Array("--split-verilog","--disable-all-randomization","--strip-debug-info"))
  }
}
object EmitFastScanTest {
  def main(args: Array[String]): Unit = {
    require(args.length==3)
    def count(p: String): Int = {
      val s=scala.io.Source.fromFile(p); try s.getLines().count(_.nonEmpty) finally s.close()
    }
    ChiselStage.emitSystemVerilogFile(new FastScanTest(count(args(1)),count(args(1)+".loads.tsv"),
      args(1),args(2)=="negative"),Array("--target-dir",args(0)),
      Array("--split-verilog","--disable-all-randomization","--strip-debug-info"))
  }
}
