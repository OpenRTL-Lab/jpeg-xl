// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl.fast

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline
import _root_.circt.stage.ChiselStage

class ExternalFastFileTransport extends BlackBox {
  override def desiredName = "FastFileTransport"
  val io = IO(new Bundle {
    val clock = Input(Clock()); val rst_n = Input(Bool())
    val io = new Bundle {
      val input = Flipped(Decoupled(new FastPoolWord))
      val command = Flipped(Decoupled(new FastFileCommand))
      val output = Decoupled(new FastFileWord)
      val sealedContexts = Output(Vec(2,Bool()))
    }
  })
}
class FastFileTest(packets: Int, words: Int, path: String, negative: Boolean) extends Module {
  val io = IO(new Bundle { val finished = Output(Bool()) })
  def table(kind: String, col: Int, bits: Int, count: Int): Mem[UInt] = {
    val m = Mem(count,UInt(bits.W))
    loadMemoryFromFileInline(m,path+s".$kind.column-$col.hex"); m
  }
  val pJob=table("packets",0,5,packets); val pTag=table("packets",1,32,packets)
  val pCh=table("packets",2,3,packets); val pFirst=table("packets",3,1,packets)
  val pLast=table("packets",4,1,packets); val pBits=table("packets",5,1024,packets)
  val pLengths=table("packets",6,36,packets); val pOffsets=table("packets",7,96,packets)
  val pTotals=table("packets",8,96,packets)
  val jWidth=table("jobs",2,9,32); val jHeight=table("jobs",3,9,32)
  val jTag=table("jobs",4,32,32); val jSize=table("jobs",5,24,32)
  val wJob=table("words",0,5,words); val wTag=table("words",1,32,words)
  val wOffset=table("words",2,24,words); val wTotal=table("words",3,24,words)
  val wBytes=table("words",4,7,words); val wFirst=table("words",5,1,words)
  val wLast=table("words",6,1,words); val wBits=table("words",7,512,words)
  val dut=Module(new ExternalFastFileTransport)
  val cycle=RegInit(0.U(32.W)); cycle := cycle+1.U
  val phase=RegInit(0.U(3.W)); val resetCount=RegInit(0.U(3.W))
  val warm=RegInit(0.U(4.W)); val loading=RegInit(true.B)
  val pi=RegInit(0.U(log2Ceil(packets+1).W)); val wi=RegInit(0.U(log2Ceil(words+1).W))
  val job=RegInit(0.U(5.W)); val submitted=RegInit(false.B)
  val running=phase===2.U || phase===3.U
  val at=Mux(pi<packets.U,pi,0.U)
  dut.io.clock := clock; dut.io.rst_n := !reset.asBool && phase=/=1.U
  val packet=dut.io.io.input.bits
  packet.context := pJob(at)(0); packet.tag := pTag(at); packet.channels := pCh(at)
  packet.first := pFirst(at).asBool; packet.last := pLast(at).asBool
  packet.bits := pBits(at).asTypeOf(packet.bits)
  packet.lengths := pLengths(at).asTypeOf(packet.lengths)
  packet.offsets := pOffsets(at).asTypeOf(packet.offsets)
  packet.totalBits := pTotals(at).asTypeOf(packet.totalBits)
  dut.io.io.input.valid := (phase===0.U || running) && loading && pi<packets.U && (pJob(at)>>1)===(job>>1)
  when(dut.io.io.input.fire) { pi := pi+1.U }
  when(loading && (pi===packets.U || (pJob(at)>>1)=/=(job>>1))) { loading := false.B }
  dut.io.io.command.bits.context := job(0)
  dut.io.io.command.bits.imageWidth := jWidth(job); dut.io.io.command.bits.imageHeight := jHeight(job)
  dut.io.io.command.bits.outputLimit := jSize(job)
  dut.io.io.command.valid := (phase===0.U || running) && !loading && !submitted
  when(dut.io.io.command.fire) { submitted := true.B }
  dut.io.io.output.ready := running && (phase===2.U || cycle%29.U>=20.U)
  when(phase===0.U && submitted) {
    warm := warm+1.U
    when(warm===12.U) { phase := 1.U }
  }
  when(phase===1.U) {
    assert(!dut.io.io.output.valid && !dut.io.io.input.ready && !dut.io.io.command.ready,
      "FAST_FILE_RESET_CANCEL")
    resetCount := resetCount+1.U
    when(resetCount===2.U) {
      phase := 2.U; pi := 0.U; wi := 0.U; job := 0.U; loading := true.B; submitted := false.B
    }
  }
  val stalled=dut.io.io.output.valid && !dut.io.io.output.ready
  val priorStall=RegNext(stalled,false.B); val held=RegEnable(dut.io.io.output.bits.asUInt,stalled)
  when(running && priorStall) {
    assert(dut.io.io.output.valid && held===dut.io.io.output.bits.asUInt,"FAST_FILE_OUTPUT_HOLD")
  }
  val lastAt=Reg(UInt(32.W)); val previousJob=Reg(UInt(5.W))
  when(running && dut.io.io.output.fire) {
    val out=dut.io.io.output.bits
    assert(wi<words.U && wJob(wi)===job && out.context===job(0) && out.tag===wTag(wi) &&
      out.tag===jTag(job),"FAST_FILE_IDENTITY")
    assert(out.byteOffset===wOffset(wi) && out.bytes===wBytes(wi) && out.totalBytes===wTotal(wi) &&
      out.first===wFirst(wi).asBool && out.last===wLast(wi).asBool,"FAST_FILE_METADATA")
    assert(out.bits===(if (negative) wBits(wi)^1.U else wBits(wi)),"FAST_FILE_OFFICIAL_BYTES")
    when(phase===2.U && !out.first) {
      assert(previousJob===job && cycle-lastAt===1.U,"FAST_FILE_INTERNAL_II_ONE")
    }
    lastAt := cycle; previousJob := job
    printf("FAST_FILE_WORD phase=%d index=%d job=%d offset=%d bytes=%d data=%x\n",
      phase,wi,job,out.byteOffset,out.bytes,out.bits)
    wi := wi+1.U
    when(out.last) {
      assert(submitted,"FAST_FILE_COMMAND_ACCEPTED")
      submitted := false.B; job := job+1.U
      when(job(0)) { loading := true.B }
      when(job===31.U) {
        assert(wi+1.U===words.U && pi===packets.U,"FAST_FILE_ALL_RECORDS")
        printf("FAST_FILE_PHASE_PASS phase=%d images=32 words=%d\n",phase,words.U)
        when(phase===3.U) { printf("FAST_FILE_SUITE_PASS\n") }
        phase := phase+1.U; pi := 0.U; wi := 0.U; job := 0.U; loading := true.B
      }
    }
  }
  assert(cycle<1000000.U,"FAST_FILE_WATCHDOG")
  io.finished := phase===4.U
}
object EmitFastFile {
  def main(args: Array[String]): Unit = {
    require(args.length==1)
    ChiselStage.emitSystemVerilogFile(new FastFileTransport,Array("--target-dir",args(0)),
      Array("--split-verilog","--disable-all-randomization","--strip-debug-info"))
  }
}
object EmitFastFileTest {
  def main(args: Array[String]): Unit = {
    require(args.length==5)
    ChiselStage.emitSystemVerilogFile(new FastFileTest(args(2).toInt,args(3).toInt,args(1),args(4)=="negative"),
      Array("--target-dir",args(0)),Array("--split-verilog","--disable-all-randomization","--strip-debug-info"))
  }
}
