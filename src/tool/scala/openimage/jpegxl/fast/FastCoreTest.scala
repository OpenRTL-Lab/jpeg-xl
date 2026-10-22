// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl.fast

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline
import _root_.circt.stage.ChiselStage

class ExternalFastEncoderCore extends BlackBox {
  override def desiredName = "FastEncoderCore"
  val io = IO(new Bundle {
    val clock = Input(Clock()); val rst_n = Input(Bool())
    val io = new Bundle {
      val write = Flipped(Decoupled(new FastPixelWrite))
      val command = Flipped(Decoupled(new FastEncoderCommand))
      val output = Decoupled(new FastFileWord); val busy = Output(Vec(2,Bool()))
    }
  })
}
class FastCoreTest(loads: Int, words: Int, path: String, negative: Boolean) extends Module {
  val io = IO(new Bundle { val finished = Output(Bool()) })
  def table(kind: String, col: Int, bits: Int, count: Int): Mem[UInt] = {
    val m=Mem(count,UInt(bits.W)); loadMemoryFromFileInline(m,path+s".$kind.column-$col.hex"); m
  }
  val rJob=table("raw",0,5,loads); val rWord=table("raw",1,12,loads)
  val rData=table("raw",2,512,loads)
  val jCh=table("jobs",1,3,32); val jWidth=table("jobs",2,9,32); val jHeight=table("jobs",3,9,32)
  val jTag=table("jobs",4,32,32); val jSize=table("jobs",5,24,32)
  val bases=table("bases",0,log2Ceil(words),32); val golden=table("words",7,512,words)
  val dut=Module(new ExternalFastEncoderCore)
  val cycle=RegInit(0.U(32.W)); cycle := cycle+1.U
  val phase=RegInit(0.U(3.W)); val resetCount=RegInit(0.U(3.W)); val warm=RegInit(0.U(4.W))
  val loading=RegInit(true.B); val pair=RegInit(0.U(4.W)); val ci=RegInit(0.U(2.W))
  val li=RegInit(0.U(log2Ceil(loads+1).W)); val wordsSeen=RegInit(0.U(log2Ceil(words+1).W))
  val counts=RegInit(VecInit(Seq.fill(2)(0.U(16.W))))
  val done=RegInit(VecInit(Seq.fill(2)(false.B))); val commandAt=Reg(Vec(2,UInt(32.W)))
  val running=phase===2.U || phase===3.U
  val at=Mux(li<loads.U,li,0.U); val job=Cat(pair,ci(0))
  dut.io.clock := clock; dut.io.rst_n := !reset.asBool && phase=/=1.U
  dut.io.io.write.valid := (phase===0.U || running) && loading && li<loads.U && (rJob(at)>>1)===pair
  dut.io.io.write.bits.context := rJob(at)(0); dut.io.io.write.bits.wordIndex := rWord(at)
  dut.io.io.write.bits.data := rData(at)
  when(dut.io.io.write.fire) { li := li+1.U }
  when(loading && (li===loads.U || (rJob(at)>>1)=/=pair)) { loading := false.B }
  dut.io.io.command.valid := !loading && ((phase===0.U && ci===0.U) || (running && ci<2.U))
  val cmd=dut.io.io.command.bits
  cmd.context := ci(0); cmd.tag := jTag(job); cmd.imageWidth := jWidth(job); cmd.imageHeight := jHeight(job)
  cmd.channels := jCh(job); cmd.baseByteOffset := 0.U; cmd.outputLimit := jSize(job)
  when(dut.io.io.command.fire) {
    ci := ci+1.U; commandAt(ci(0).asUInt) := cycle
    when(running) { printf("FAST_CORE_COMMAND phase=%d job=%d cycle=%d\n",phase,job,cycle) }
  }
  dut.io.io.output.ready := running && (phase===2.U || cycle%29.U>=20.U)
  when(phase===0.U && ci=/=0.U) { warm := warm+1.U; when(warm===12.U) { phase := 1.U } }
  when(phase===1.U) {
    assert(!dut.io.io.output.valid && !dut.io.io.command.ready && !dut.io.io.write.ready,
      "FAST_CORE_RESET_CANCEL")
    resetCount := resetCount+1.U
    when(resetCount===2.U) {
      phase := 2.U; loading := true.B; li := 0.U; ci := 0.U; pair := 0.U
    }
  }
  val stalled=dut.io.io.output.valid && !dut.io.io.output.ready
  val priorStall=RegNext(stalled,false.B); val held=RegEnable(dut.io.io.output.bits.asUInt,stalled)
  when(running && priorStall) {
    assert(dut.io.io.output.valid && held===dut.io.io.output.bits.asUInt,"FAST_CORE_OUTPUT_HOLD")
  }
  when(running && dut.io.io.output.fire) {
    val out=dut.io.io.output.bits; val ctx=out.context.asUInt; val outputJob=Cat(pair,out.context)
    val count=counts(ctx); val index=bases(outputJob)+&count
    val expectedOffset=count<<6; val remaining=jSize(outputJob)-expectedOffset
    val last=remaining<=64.U
    assert(!done(ctx) && ci===2.U && index<words.U && out.tag===jTag(outputJob),"FAST_CORE_IDENTITY")
    assert(out.byteOffset===expectedOffset && out.bytes===Mux(last,remaining,64.U) &&
      out.totalBytes===jSize(outputJob) && out.first===(count===0.U) && out.last===last,"FAST_CORE_METADATA")
    assert(out.bits===(if (negative) golden(index)^1.U else golden(index)),"FAST_CORE_OFFICIAL_BYTES")
    printf("FAST_CORE_WORD phase=%d index=%d job=%d offset=%d bytes=%d data=%x\n",
      phase,index,outputJob,out.byteOffset,out.bytes,out.bits)
    counts(ctx) := count+1.U; wordsSeen := wordsSeen+1.U
    when(last) {
      done(ctx) := true.B
      printf("FAST_CORE_COMPLETE phase=%d job=%d cycle=%d latency=%d bytes=%d\n",
        phase,outputJob,cycle,cycle-commandAt(ctx),out.totalBytes)
      when(done((!out.context).asUInt)) {
        pair := pair+1.U; ci := 0.U; loading := true.B
        counts := VecInit(Seq.fill(2)(0.U)); done := VecInit(Seq.fill(2)(false.B))
        when(pair===15.U) {
          assert(li===loads.U && wordsSeen+1.U===words.U,"FAST_CORE_ALL_RECORDS")
          printf("FAST_CORE_PHASE_PASS phase=%d images=32 words=%d\n",phase,words.U)
          when(phase===3.U) { printf("FAST_CORE_SUITE_PASS\n") }
          phase := phase+1.U; li := 0.U; pair := 0.U; wordsSeen := 0.U
        }
      }
    }
  }
  assert(cycle<1000000.U,"FAST_CORE_WATCHDOG")
  io.finished := phase===4.U
}
object EmitFastCore {
  def main(args: Array[String]): Unit = {
    require(args.length==1)
    ChiselStage.emitSystemVerilogFile(new FastEncoderCore,Array("--target-dir",args(0)),
      Array("--split-verilog","--disable-all-randomization","--strip-debug-info"))
  }
}
object EmitFastCoreTest {
  def main(args: Array[String]): Unit = {
    require(args.length==5)
    ChiselStage.emitSystemVerilogFile(new FastCoreTest(args(2).toInt,args(3).toInt,args(1),args(4)=="negative"),
      Array("--target-dir",args(0)),Array("--split-verilog","--disable-all-randomization","--strip-debug-info"))
  }
}
