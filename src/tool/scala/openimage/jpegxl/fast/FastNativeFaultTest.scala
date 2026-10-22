// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl.fast

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline
import _root_.circt.stage.ChiselStage

/** Directed native faults/cancels with the real whole encoder, not a fake core.
  * The one image is the first frozen official capture (1x1 Gray, zero pixel).
  */
class FastNativeFaultTest(path: String, negative: Boolean) extends Module {
  val io=IO(new Bundle { val finished=Output(Bool()) })
  val official=Mem(3,UInt(512.W))
  loadMemoryFromFileInline(official,path+".fault-file.hex")
  val dut=Module(new ExternalFastNativeEncoder)
  dut.io.clock := clock; dut.io.rst_n := !reset.asBool
  val p=dut.io.io
  val cycle=RegInit(0.U(32.W)); cycle := cycle+1.U
  val scenario=RegInit(0.U(4.W)); val sent=RegInit(0.U(3.W)); val results=RegInit(0.U(3.W))
  val seen=RegInit(0.U(4.W)); val cancelSent=RegInit(false.B); val cancelSeen=RegInit(false.B)
  val finished=RegInit(false.B); io.finished := finished
  val jobs=Mux(scenario===9.U,0.U,Mux(scenario===12.U,3.U,
    Mux(scenario===8.U || scenario===10.U || scenario===11.U,2.U,1.U)))
  val s=sent(1,0)
  val sourceBase=BigInt("100000000",16).U(33.W)
  val destinationBase=BigInt("180000000",16).U(33.W)
  val tagBase=BigInt("79000000",16).U(32.W)+(scenario<<4)
  p.command.valid := !finished && sent<jobs
  p.command.bits.tag := tagBase+Mux(scenario===11.U,0.U,s)
  p.command.bits.imageWidth := Mux(scenario===0.U,0.U,Mux(scenario===14.U,257.U,1.U))
  p.command.bits.imageHeight := 1.U; p.command.bits.channels := Mux(scenario===13.U,0.U,1.U)
  val overlapPeer=scenario===10.U && s===1.U
  val source=sourceBase+(Mux(overlapPeer,0.U,s)<<20)
  p.command.bits.source := Mux(scenario===1.U,source+1.U,
    Mux(scenario===2.U,((BigInt(1)<<33)-64).U,source))
  p.command.bits.destination := Mux(scenario===3.U,source,destinationBase+(s<<20))
  p.command.bits.sourceSpan := Mux(scenario===2.U,128.U,64.U)
  p.command.bits.destinationSpan := 192.U
  p.command.bits.outputLimit := Mux(scenario===4.U,1.U,174.U)
  when(p.command.fire) { sent := sent+1.U }

  val rp=RegInit(VecInit(Seq.fill(16)(false.B))); val rjob=Reg(Vec(16,UInt(2.W)))
  val rdue=Reg(Vec(16,UInt(32.W))); val wp=RegInit(VecInit(Seq.fill(16)(false.B)))
  val wjob=Reg(Vec(16,UInt(2.W))); val wdue=Reg(Vec(16,UInt(32.W)))
  val writes=RegInit(VecInit(Seq.fill(4)(0.U(3.W))))
  val reads=RegInit(VecInit(Seq.fill(4)(0.U(3.W))))
  val readReturned=RegInit(VecInit(Seq.fill(4)(false.B)))
  val returnedAt=Reg(Vec(4,UInt(32.W)))
  val readHeld=RegInit(false.B); val rb=Reg(new FastNativeReadResponse)
  val writeHeld=RegInit(false.B); val wb=Reg(new FastNativeWriteResponse)

  val blockedRead=(scenario===7.U && !cancelSent) || (scenario===12.U && !cancelSeen)
  p.read.ready := !blockedRead && cycle%7.U=/=0.U
  when(p.read.fire) {
    val id=p.read.bits.id; val j=p.read.bits.address(21,20)
    assert(!rp(id) && p.read.bits.address===sourceBase+(j<<20),"FAST_NATIVE_FAULT_READ_RANGE")
    assert(!(scenario===12.U && j===2.U),"FAST_NATIVE_QUEUED_CANCEL_ZERO_READ")
    assert(!(scenario===10.U && j===1.U),"FAST_NATIVE_OVERLAP_ZERO_READ")
    rp(id) := true.B; rjob(id) := j; rdue(id) := cycle+11.U+(15.U-id)
    reads(j) := reads(j)+1.U
  }
  p.readResponse.valid := readHeld; p.readResponse.bits := rb
  val re=VecInit((0 until 16).map(i => rp(i) && cycle>=rdue(i) &&
    !(p.readResponse.fire && rb.id===i.U))).asUInt
  val ri=15.U-PriorityEncoder(Reverse(re))
  when(!readHeld || p.readResponse.ready) {
    readHeld := re.orR
    when(re.orR) { rb.id := ri; rb.data := 0.U; rb.fault := scenario===5.U }
  }
  when(p.readResponse.fire) {
    rp(rb.id) := false.B; readReturned(rjob(rb.id)) := true.B; returnedAt(rjob(rb.id)) := cycle
  }
  p.write.ready := cycle%11.U>=3.U
  when(p.write.fire) {
    val id=p.write.bits.id; val j=p.write.bits.address(21,20)
    val offset=p.write.bits.address(19,0)
    assert(!wp(id) && p.write.bits.address===destinationBase+(j<<20)+offset &&
      offset===(writes(j)<<6) && offset<174.U,"FAST_NATIVE_FAULT_WRITE_RANGE")
    assert(p.write.bits.data===official(writes(j)),"FAST_NATIVE_FAULT_OFFICIAL_BYTES")
    assert(!(scenario===4.U || scenario===5.U || scenario===7.U ||
      (scenario===8.U && j===0.U) || (scenario===12.U && j===2.U)),"FAST_NATIVE_CANCEL_ERROR_ZERO_WRITE")
    writes(j) := writes(j)+1.U
    wp(id) := true.B; wjob(id) := j; wdue(id) := cycle+17.U+(15.U-id)
  }
  p.writeResponse.valid := writeHeld; p.writeResponse.bits := wb
  val we=VecInit((0 until 16).map(i => wp(i) && cycle>=wdue(i) &&
    !(p.writeResponse.fire && wb.id===i.U))).asUInt
  val wi=15.U-PriorityEncoder(Reverse(we))
  when(!writeHeld || p.writeResponse.ready) {
    writeHeld := we.orR
    when(we.orR) { wb.id := wi; wb.fault := scenario===6.U }
  }
  when(p.writeResponse.fire) { wp(wb.id) := false.B }

  val cancelTarget=Mux(scenario===12.U,2.U,0.U)
  val cancelEligible=(scenario===7.U && p.read.valid && !p.read.ready) ||
    (scenario===8.U && readReturned(0) && cycle-returnedAt(0)>=20.U) ||
    scenario===9.U || (scenario===12.U && sent===3.U)
  p.cancel.valid := !finished && !cancelSent && cancelEligible
  p.cancel.bits := Mux(scenario===9.U,"hffffffff".U,tagBase+cancelTarget)
  p.cancelAck.ready := cycle%5.U=/=0.U
  when(p.cancel.fire) { cancelSent := true.B }
  when(p.cancelAck.fire) {
    assert(cancelSent && !cancelSeen && p.cancelAck.bits.tag===p.cancel.bits &&
      p.cancelAck.bits.found===(scenario=/=9.U),"FAST_NATIVE_CANCEL_ACK")
    cancelSeen := true.B
    printf("FAST_NATIVE_CANCEL_ACK case=%d found=%d\n",scenario,p.cancelAck.bits.found)
  }

  def hold[T <: Data](channel: DecoupledIO[T], label: String): Unit = {
    val stalled=channel.valid && !channel.ready
    val previous=RegNext(stalled,false.B); val bits=RegEnable(channel.bits.asUInt,stalled)
    when(previous) { assert(channel.valid && channel.bits.asUInt===bits,label) }
  }
  hold(p.read,"FAST_NATIVE_CANCEL_READ_OFFER_HOLD")
  hold(p.write,"FAST_NATIVE_FAULT_WRITE_OFFER_HOLD")
  hold(p.result,"FAST_NATIVE_FAULT_RESULT_HOLD")
  hold(p.cancel,"FAST_NATIVE_CANCEL_OFFER_HOLD")
  p.result.ready := cycle%13.U>=5.U
  when(p.result.fire) {
    val out=p.result.bits
    val j=Mux(scenario===11.U && out.status===3.U,1.U,out.tag(1,0))
    val expected=WireDefault(0.U(3.W))
    when(scenario===0.U || scenario===1.U || scenario===2.U || scenario===13.U || scenario===14.U) { expected := 1.U }
    when(scenario===3.U || (scenario===10.U && j===1.U)) { expected := 2.U }
    when(scenario===11.U && j===1.U) { expected := 3.U }
    when(scenario===4.U) { expected := 6.U }
    when(scenario===5.U) { expected := 4.U }
    when(scenario===6.U) { expected := 5.U }
    when(scenario===7.U || (scenario===8.U && j===0.U) || (scenario===12.U && j===2.U)) { expected := 7.U }
    assert(j<jobs && !seen(j) && out.tag===tagBase+Mux(scenario===11.U,0.U,j),"FAST_NATIVE_FAULT_RESULT_TAG")
    assert(out.status===(if (negative) expected^1.U else expected),"FAST_NATIVE_FAULT_STATUS")
    assert(out.readRequests===reads(j) && out.writeRequests===writes(j),"FAST_NATIVE_FAULT_ACTUAL_COUNTS")
    for (id <- 0 until 16) {
      assert(!(rp(id) && rjob(id)===j) && !(wp(id) && wjob(id)===j),"FAST_NATIVE_FAULT_DRAIN_BEFORE_RESULT")
    }
    when(expected===0.U) { assert(out.outputBytes===174.U && reads(j)===1.U && writes(j)===3.U,"FAST_NATIVE_FAULT_RECOVERY_BYTES") }
    when(expected===1.U || expected===2.U || expected===3.U || (scenario===12.U && j===2.U)) {
      assert(reads(j)===0.U && writes(j)===0.U,"FAST_NATIVE_INVALID_ZERO_DMA")
    }
    when(scenario===5.U) { assert(out.faultAddress===sourceBase,"FAST_NATIVE_READ_FAULT_ADDRESS") }
    when(scenario===6.U) { assert(out.faultAddress>=destinationBase && out.faultAddress<destinationBase+192.U,"FAST_NATIVE_WRITE_FAULT_ADDRESS") }
    seen := seen | (1.U(4.W)<<j); results := results+1.U
    printf("FAST_NATIVE_FAULT_RESULT case=%d slot=%d status=%d reads=%d writes=%d bytes=%d\n",
      scenario,j,out.status,reads(j),writes(j),out.outputBytes)
  }
  val needsCancel=scenario===7.U || scenario===8.U || scenario===9.U || scenario===12.U
  when(!finished && sent===jobs && results===jobs && (!needsCancel || cancelSeen) && p.quiescent) {
    assert(!rp.asUInt.orR && !wp.asUInt.orR && !readHeld && !writeHeld,"FAST_NATIVE_FAULT_QUIESCENT")
    printf("FAST_NATIVE_FAULT_CASE_PASS case=%d results=%d\n",scenario,results)
    when(scenario===15.U) { finished := true.B; printf("FAST_NATIVE_FAULT_SUITE_PASS\n") }
      .otherwise {
        scenario := scenario+1.U; sent := 0.U; results := 0.U; seen := 0.U
        cancelSent := false.B; cancelSeen := false.B
        reads.foreach(_ := 0.U); writes.foreach(_ := 0.U); readReturned.foreach(_ := false.B)
      }
  }
  when(!finished) { assert(cycle<100000.U,"FAST_NATIVE_FAULT_TIMEOUT") }
}
object EmitFastNativeFaultTest {
  def main(args: Array[String]): Unit = {
    require(args.length==3)
    ChiselStage.emitSystemVerilogFile(new FastNativeFaultTest(args(1),args(2)=="negative"),
      Array("--target-dir",args(0)),Array("--split-verilog","--disable-all-randomization","--strip-debug-info"))
  }
}
