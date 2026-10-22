// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl.fast

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline
import _root_.circt.stage.ChiselStage

/** Actual raw-memory responses and whole-file writes. No codec computation in
  * the environment. The official ROMs belong here, outside the product SV.
  */
class FastNativeBoundaryTest(rawWords: Int, fileWords: Int, path: String) extends Module {
  val io=IO(new Bundle { val finished=Output(Bool()) })
  def table(suffix: String, bits: Int, count: Int): Mem[UInt] = {
    val m=Mem(count,UInt(bits.W)); loadMemoryFromFileInline(m,path+suffix); m
  }
  val boundaryConfig=Mem(2,UInt(16.W))
  loadMemoryFromFileInline(boundaryConfig,"boundary-config.hex")
  val config=boundaryConfig(0.U)
  val limit=config(4,0); val negative=config(5)
  val firstJob=(config(7,6)<<3).pad(6); val endJob=firstJob+8.U
  assert((limit===1.U || limit===4.U || limit===16.U) && config(15,8)===0.U,"FAST_BOUNDARY_CONFIG")
  val raw=table(".raw.column-2.hex",512,rawWords)
  val rawBases=table(".raw-bases.column-0.hex",log2Ceil(rawWords+1),32)
  val golden=table(".words.column-7.hex",512,fileWords)
  val bases=table(".bases.column-0.hex",log2Ceil(fileWords+1),32)
  val tags=table(".jobs.column-4.hex",32,32)
  val widths=table(".jobs.column-2.hex",9,32); val heights=table(".jobs.column-3.hex",9,32)
  val channels=table(".jobs.column-1.hex",3,32); val sizes=table(".jobs.column-5.hex",24,32)
  val dut=Module(new ExternalFastNativeEncoder)
  dut.io.clock := clock; dut.io.rst_n := !reset.asBool
  val ports=dut.io.io
  val cycle=RegInit(0.U(32.W)); cycle := cycle+1.U
  val phase=RegInit(2.U(3.W)); val sent=RegInit(0.U(6.W)); val completed=RegInit(0.U(6.W))
  val wordCount=RegInit(0.U(16.W)); val waiting=RegInit(false.B)
  val finished=RegInit(false.B); io.finished := finished
  val job=sent(4,0)
  val sourceBase=BigInt("100000000",16).U(33.W)
  val destinationBase=BigInt("180000000",16).U(33.W)
  val rawSize=widths(job)*heights(job)*channels(job)
  when(cycle===0.U) { sent := firstJob }
  ports.command.valid := cycle=/=0.U && !waiting && !finished && sent<endJob
  ports.command.bits.tag := tags(job)
  ports.command.bits.imageWidth := widths(job); ports.command.bits.imageHeight := heights(job)
  ports.command.bits.channels := channels(job)
  ports.command.bits.source := sourceBase+(job<<20)
  ports.command.bits.destination := destinationBase+(job<<20)
  ports.command.bits.sourceSpan := ((rawSize+&63.U)>>6)<<6
  ports.command.bits.destinationSpan := ((sizes(job)+&63.U)>>6)<<6
  ports.command.bits.outputLimit := sizes(job)
  ports.cancel.valid := false.B; ports.cancel.bits := 0.U; ports.cancelAck.ready := true.B
  when(ports.command.fire) {
    printf("FAST_NATIVE_COMMAND phase=%d job=%d cycle=%d\n",phase,job,cycle)
    sent := sent+1.U
  }

  // Memory response queues are scoreboards, not a software codec. They return
  // raw bytes by real source address and deliberately choose high ready IDs.
  val readPending=RegInit(VecInit(Seq.fill(16)(false.B)))
  val readJobs=Reg(Vec(16,UInt(5.W))); val readWords=Reg(Vec(16,UInt(12.W)))
  val readDue=Reg(Vec(16,UInt(32.W)))
  val writePending=RegInit(VecInit(Seq.fill(16)(false.B)))
  val writeJobs=Reg(Vec(16,UInt(5.W))); val writeDue=Reg(Vec(16,UInt(32.W)))
  val readPeak=RegInit(0.U(5.W)); val writePeak=RegInit(0.U(5.W))
  val readCount=PopCount(readPending); val writeCount=PopCount(writePending)
  assert(readCount<=limit && writeCount<=limit,"FAST_BOUNDARY_OUTSTANDING_LIMIT")
  when(readCount>readPeak) { readPeak := readCount }
  when(writeCount>writePeak) { writePeak := writeCount }
  ports.read.ready := (phase===2.U || cycle%23.U>=8.U) && readCount<limit
  val readAddress=ports.read.bits.address
  val readJob=readAddress(24,20); val readWord=readAddress(17,6)
  when(ports.read.fire) {
    val id=ports.read.bits.id
    assert(!readPending(id),"FAST_NATIVE_TEST_READ_ID_REUSE")
    assert(readAddress===sourceBase+(readJob<<20)+(readWord<<6) &&
      (readWord<<6)<widths(readJob)*heights(readJob)*channels(readJob),"FAST_NATIVE_TEST_READ_SENTINEL")
    readPending(id) := true.B; readJobs(id) := readJob; readWords(id) := readWord
    readDue(id) := cycle+Mux(phase===2.U,2.U,13.U)+(15.U-id)
  }
  val readResponseValid=RegInit(false.B); val readResponse=Reg(new FastNativeReadResponse)
  ports.readResponse.valid := readResponseValid; ports.readResponse.bits := readResponse
  val readEligible=VecInit((0 until 16).map(i => readPending(i) && cycle>=readDue(i) &&
    !(ports.readResponse.fire && readResponse.id===i.U))).asUInt
  val readPick=15.U-PriorityEncoder(Reverse(readEligible))
  when(!readResponseValid || ports.readResponse.ready) {
    readResponseValid := readEligible.orR
    when(readEligible.orR) {
      readResponse.id := readPick; readResponse.fault := false.B
      readResponse.data := raw(rawBases(readJobs(readPick))+readWords(readPick))
    }
  }
  when(ports.readResponse.fire) { readPending(readResponse.id) := false.B }

  ports.write.ready := (phase===2.U || cycle%31.U>=11.U) && writeCount<limit
  val writeAddress=ports.write.bits.address
  val writeJob=writeAddress(24,20); val offset=writeAddress(19,0)
  val writes=RegInit(VecInit(Seq.fill(32)(0.U(14.W))))
  when(ports.write.fire) {
    val id=ports.write.bits.id
    assert(!writePending(id),"FAST_NATIVE_TEST_WRITE_ID_REUSE")
    assert(writeAddress===destinationBase+(writeJob<<20)+offset && offset(5,0)===0.U &&
      offset===(writes(writeJob)<<6) && offset<sizes(writeJob),"FAST_NATIVE_TEST_WRITE_SENTINEL")
    val expected=golden(bases(writeJob)+writes(writeJob))
    assert(ports.write.bits.data===(expected^negative.asUInt),"FAST_NATIVE_OFFICIAL_BYTES")
    val validBytes=Mux(sizes(writeJob)-offset>=64.U,64.U,sizes(writeJob)-offset)
    printf("FAST_NATIVE_WORD phase=%d index=%d job=%d offset=%d bytes=%d data=%x\n",
      phase,wordCount,writeJob,offset,validBytes,ports.write.bits.data)
    wordCount := wordCount+1.U; writes(writeJob) := writes(writeJob)+1.U
    writePending(id) := true.B; writeJobs(id) := writeJob
    writeDue(id) := cycle+Mux(phase===2.U,3.U,19.U)+(15.U-id)
  }
  val writeResponseValid=RegInit(false.B); val writeResponse=Reg(new FastNativeWriteResponse)
  ports.writeResponse.valid := writeResponseValid; ports.writeResponse.bits := writeResponse
  val writeEligible=VecInit((0 until 16).map(i => writePending(i) && cycle>=writeDue(i) &&
    !(ports.writeResponse.fire && writeResponse.id===i.U))).asUInt
  val writePick=15.U-PriorityEncoder(Reverse(writeEligible))
  when(!writeResponseValid || ports.writeResponse.ready) {
    writeResponseValid := writeEligible.orR
    when(writeEligible.orR) { writeResponse.id := writePick; writeResponse.fault := false.B }
  }
  when(ports.writeResponse.fire) { writePending(writeResponse.id) := false.B }

  // Stable offers must survive request-side backpressure, including IDs/data.
  def checkHold[T <: Data](channel: DecoupledIO[T], label: String): Unit = {
    val stalled=channel.valid && !channel.ready
    val was=RegNext(stalled,false.B); val bits=RegEnable(channel.bits.asUInt,stalled)
    when(was) { assert(channel.valid && channel.bits.asUInt===bits,label) }
  }
  checkHold(ports.read,"FAST_NATIVE_READ_HOLD")
  checkHold(ports.write,"FAST_NATIVE_WRITE_HOLD")
  checkHold(ports.result,"FAST_NATIVE_RESULT_HOLD")
  ports.result.ready := phase===2.U || cycle%37.U>=23.U
  val resultSeen=RegInit(0.U(32.W))
  when(ports.result.fire) {
    val r=ports.result.bits; val j=r.tag(4,0)
    assert(j>=firstJob && j<sent && r.tag===tags(j) && !resultSeen(j) && r.status===0.U,"FAST_NATIVE_RESULT_IDENTITY")
    assert(r.outputBytes===sizes(j) && writes(j)===(sizes(j)+&63.U)>>6,"FAST_NATIVE_RESULT_SIZE")
    assert(r.readRequests===(widths(j)*heights(j)*channels(j)+&63.U)>>6 &&
      r.writeRequests===writes(j) && r.retiredCycle>r.acceptedCycle &&
      r.firstFileWriteCycle>r.acceptedCycle && r.retiredCycle>r.firstFileWriteCycle,"FAST_NATIVE_RESULT_METRICS")
    for (id <- 0 until 16) {
      assert(!(readPending(id) && readJobs(id)===j) && !(writePending(id) && writeJobs(id)===j),
        "FAST_NATIVE_RESULT_AFTER_REAL_RESPONSES")
    }
    resultSeen := resultSeen | (1.U(32.W)<<j)
    printf("FAST_NATIVE_COMPLETE phase=%d job=%d cycle=%d accepted=%d retired=%d firstWrite=%d reads=%d writes=%d bytes=%d\n",
      phase,j,cycle,r.acceptedCycle,r.retiredCycle,r.firstFileWriteCycle,r.readRequests,r.writeRequests,r.outputBytes)
    completed := completed+1.U
    when(completed===7.U) {
      val expectedWords=(0 until 8).map(i => (sizes((firstJob+i.U)(4,0))+&63.U)>>6).reduce(_+&_)
      assert(sent===endJob && wordCount===expectedWords,"FAST_NATIVE_ALL_RECORDS")
      waiting := true.B
      printf("FAST_NATIVE_PHASE_PASS phase=%d images=8 words=%d\n",phase,wordCount)
    }
  }
  when(waiting && ports.quiescent) {
    assert(!readPending.asUInt.orR && !writePending.asUInt.orR && !readResponseValid && !writeResponseValid,
      "FAST_NATIVE_QUIESCENT_REAL_MEMORY")
    when(phase===2.U) {
      phase := 3.U; sent := firstJob; completed := 0.U; resultSeen := 0.U; wordCount := 0.U
      writes.foreach(_ := 0.U); waiting := false.B
    }.otherwise {
      finished := true.B
      printf("FAST_BOUNDARY_LIMIT_PASS first=%d limit=%d readPeak=%d writePeak=%d\n",firstJob,limit,readPeak,writePeak)
      printf("FAST_NATIVE_SUITE_PASS\n")
    }
  }
  when(!finished) { assert(cycle<1000000.U,"FAST_NATIVE_TIMEOUT") }
}
object EmitFastNativeBoundaryTest {
  def main(args: Array[String]): Unit = {
    require(args.length==4)
    ChiselStage.emitSystemVerilogFile(new FastNativeBoundaryTest(args(2).toInt,args(3).toInt,args(1)),
      Array("--target-dir",args(0)),Array("--split-verilog","--disable-all-randomization","--strip-debug-info"))
  }
}
