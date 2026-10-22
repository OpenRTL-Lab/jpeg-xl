// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl.fast

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline
import _root_.circt.stage.ChiselStage

/** One isolated job, quiescent barrier, then 32 identical jobs without reset.
  * Config is test-only ROM; C++ does not generate transactions or check pixels.
  */
class FastNativePerfTest(rawWords: Int, fileWords: Int, path: String, payloadPath: String) extends Module {
  val io=IO(new Bundle { val finished=Output(Bool()) })
  def table(file: String,bits: Int,count: Int): Mem[UInt] = {
    val m=Mem(count,UInt(bits.W)); loadMemoryFromFileInline(m,file); m
  }
  // Frozen firtool 1.149 crashes canonicalizing an initialized depth-one ROM.
  // A second unused word avoids that compiler bug; product RTL is unchanged.
  val configuration=table("perf-config.hex",16,2)(0.U)
  val image=configuration(4,0); val delayed=configuration(5); val negative=configuration(6)
  assert(configuration(15,7)===0.U,"FAST_PERF_CONFIG_RANGE")
  val raw=table(path+".raw.column-2.hex",512,rawWords)
  val rawBases=table(path+".raw-bases.column-0.hex",log2Ceil(rawWords+1),32)
  val golden=table(path+".words.column-7.hex",512,fileWords)
  val bases=table(path+".bases.column-0.hex",log2Ceil(fileWords+1),32)
  val widths=table(path+".jobs.column-2.hex",9,32); val heights=table(path+".jobs.column-3.hex",9,32)
  val channels=table(path+".jobs.column-1.hex",3,32); val sizes=table(path+".jobs.column-5.hex",24,32)
  // Zero means no entropy-coded image bits; otherwise first absolute bit + 1.
  val payload=table(payloadPath,24,32)(image)
  assert(payload<=sizes(image)*8.U,"FAST_PERF_OFFICIAL_BOUNDARY")
  val dut=Module(new ExternalFastNativeEncoder)
  dut.io.clock := clock; dut.io.rst_n := !reset.asBool
  val p=dut.io.io
  val cycle=RegInit(0.U(32.W)); cycle := cycle+1.U
  val sent=RegInit(0.U(6.W)); val completed=RegInit(0.U(6.W))
  val isolatedDone=RegInit(false.B); val batch=RegInit(false.B); val finished=RegInit(false.B)
  io.finished := finished
  val sourceBase=BigInt("100000000",16).U(33.W)
  val destinationBase=BigInt("180000000",16).U(33.W)
  val tagBase=BigInt("78100000",16).U(32.W)
  val rawSize=widths(image)*heights(image)*channels(image)
  p.command.valid := !finished && sent<33.U && (sent===0.U || batch)
  p.command.bits.tag := tagBase+sent
  p.command.bits.imageWidth := widths(image); p.command.bits.imageHeight := heights(image)
  p.command.bits.channels := channels(image)
  p.command.bits.source := sourceBase+(sent<<20)
  p.command.bits.destination := destinationBase+(sent<<20)
  p.command.bits.sourceSpan := ((rawSize+&63.U)>>6)<<6
  p.command.bits.destinationSpan := ((sizes(image)+&63.U)>>6)<<6
  p.command.bits.outputLimit := sizes(image)
  p.cancel.valid := false.B; p.cancel.bits := 0.U; p.cancelAck.ready := true.B
  val accepted=Reg(Vec(33,UInt(32.W)))
  when(p.command.fire) {
    accepted(sent) := cycle; sent := sent+1.U
    printf("FAST_PERF_COMMAND task=%d cycle=%d\n",sent,cycle)
  }
  val rp=RegInit(VecInit(Seq.fill(16)(false.B))); val rjob=Reg(Vec(16,UInt(6.W)))
  val rword=Reg(Vec(16,UInt(12.W))); val rdue=Reg(Vec(16,UInt(32.W)))
  val wp=RegInit(VecInit(Seq.fill(16)(false.B))); val wjob=Reg(Vec(16,UInt(6.W)))
  val wdue=Reg(Vec(16,UInt(32.W)))
  val writes=RegInit(VecInit(Seq.fill(33)(0.U(14.W))))
  val lastRead=RegInit(VecInit(Seq.fill(33)(0.U(32.W))))
  val lastWrite=RegInit(VecInit(Seq.fill(33)(0.U(32.W))))
  val lastResponse=RegInit(VecInit(Seq.fill(33)(0.U(32.W))))
  val firstPayload=RegInit(VecInit(Seq.fill(33)(0.U(32.W))))
  val readHeld=RegInit(false.B); val rb=Reg(new FastNativeReadResponse)
  val writeHeld=RegInit(false.B); val wb=Reg(new FastNativeWriteResponse)
  p.read.ready := !delayed || cycle%23.U>=8.U
  when(p.read.fire) {
    val id=p.read.bits.id; val j=p.read.bits.address(25,20); val word=p.read.bits.address(17,6)
    assert(j<33.U && j<sent && !rp(id),"FAST_PERF_READ_ID")
    assert(p.read.bits.address===sourceBase+(j<<20)+(word<<6) && (word<<6)<rawSize,
      "FAST_PERF_READ_SENTINEL")
    rp(id) := true.B; rjob(id) := j; rword(id) := word
    rdue(id) := cycle+Mux(delayed,19.U+(15.U-id),1.U)
  }
  p.readResponse.valid := readHeld; p.readResponse.bits := rb
  val re=VecInit((0 until 16).map(i => rp(i) && cycle>=rdue(i) &&
    !(p.readResponse.fire && rb.id===i.U))).asUInt
  val ri=15.U-PriorityEncoder(Reverse(re))
  when(!readHeld || p.readResponse.ready) {
    readHeld := re.orR
    when(re.orR) {
      rb.id := ri; rb.fault := false.B; rb.data := raw(rawBases(image)+rword(ri))
    }
  }
  when(p.readResponse.fire) { rp(rb.id) := false.B; lastRead(rjob(rb.id)) := cycle }
  p.write.ready := !delayed || cycle%31.U>=11.U
  when(p.write.fire) {
    val id=p.write.bits.id; val j=p.write.bits.address(25,20); val offset=p.write.bits.address(19,0)
    assert(j<33.U && j<sent && !wp(id),"FAST_PERF_WRITE_ID")
    assert(p.write.bits.address===destinationBase+(j<<20)+offset && offset(5,0)===0.U &&
      offset===(writes(j)<<6) && offset<sizes(image),"FAST_PERF_WRITE_SENTINEL")
    val expected=golden(bases(image)+writes(j))
    assert(p.write.bits.data===(expected^negative.asUInt),"FAST_PERF_OFFICIAL_BYTES")
    when(j===0.U || j===1.U || j===32.U) {
      val bytes=Mux(sizes(image)-offset>=64.U,64.U,sizes(image)-offset)
      printf("FAST_PERF_WORD task=%d offset=%d bytes=%d data=%x\n",j,offset,bytes,p.write.bits.data)
    }
    when(payload=/=0.U && offset*8.U<=payload-1.U && (offset+&64.U)*8.U>payload-1.U) {
      assert(firstPayload(j)===0.U,"FAST_PERF_PAYLOAD_ONCE"); firstPayload(j) := cycle
    }
    writes(j) := writes(j)+1.U; lastWrite(j) := cycle
    wp(id) := true.B; wjob(id) := j; wdue(id) := cycle+Mux(delayed,29.U+(15.U-id),1.U)
  }
  p.writeResponse.valid := writeHeld; p.writeResponse.bits := wb
  val we=VecInit((0 until 16).map(i => wp(i) && cycle>=wdue(i) &&
    !(p.writeResponse.fire && wb.id===i.U))).asUInt
  val wi=15.U-PriorityEncoder(Reverse(we))
  when(!writeHeld || p.writeResponse.ready) {
    writeHeld := we.orR
    when(we.orR) { wb.id := wi; wb.fault := false.B }
  }
  when(p.writeResponse.fire) { wp(wb.id) := false.B; lastResponse(wjob(wb.id)) := cycle }
  def hold[T <: Data](channel: DecoupledIO[T],label: String): Unit = {
    val stalled=channel.valid && !channel.ready
    val was=RegNext(stalled,false.B); val value=RegEnable(channel.bits.asUInt,stalled)
    when(was) { assert(channel.valid && channel.bits.asUInt===value,label) }
  }
  hold(p.read,"FAST_PERF_READ_HOLD"); hold(p.write,"FAST_PERF_WRITE_HOLD")
  hold(p.result,"FAST_PERF_RESULT_HOLD")
  p.result.ready := !delayed || cycle%37.U>=23.U
  val resultSeen=RegInit(0.U(33.W)); val validSeen=RegInit(VecInit(Seq.fill(33)(false.B)))
  val firstValid=Reg(Vec(33,UInt(32.W)))
  val j=p.result.bits.tag(5,0)
  when(p.result.valid && !validSeen(j)) { validSeen(j) := true.B; firstValid(j) := cycle }
  when(p.result.fire) {
    val r=p.result.bits
    assert(j<sent && j<33.U && r.tag===tagBase+j && !resultSeen(j) && r.status===0.U,
      "FAST_PERF_RESULT_IDENTITY")
    assert(r.outputBytes===sizes(image) && r.writeRequests===writes(j) &&
      writes(j)===(sizes(image)+&63.U)>>6 && r.readRequests===(rawSize+&63.U)>>6,
      "FAST_PERF_RESULT_COUNTS")
    assert(r.acceptedCycle===accepted(j) && r.retiredCycle>=lastResponse(j) &&
      lastResponse(j)>lastWrite(j) && lastRead(j)>accepted(j),"FAST_PERF_RESULT_TIMES")
    assert((payload===0.U && firstPayload(j)===0.U) ||
      (payload=/=0.U && firstPayload(j)>=r.firstFileWriteCycle && firstPayload(j)<=lastWrite(j)),
      "FAST_PERF_RESULT_PAYLOAD")
    for (id <- 0 until 16) {
      assert(!(rp(id) && rjob(id)===j) && !(wp(id) && wjob(id)===j),"FAST_PERF_DRAIN")
    }
    printf("FAST_PERF_RESULT task=%d accepted=%d retired=%d firstFile=%d firstPayload=%d lastRead=%d lastWrite=%d lastResponse=%d firstValid=%d handshake=%d reads=%d writes=%d bytes=%d\n",
      j,r.acceptedCycle,r.retiredCycle,r.firstFileWriteCycle,firstPayload(j),lastRead(j),lastWrite(j),
      lastResponse(j),Mux(validSeen(j),firstValid(j),cycle),cycle,r.readRequests,r.writeRequests,r.outputBytes)
    resultSeen := resultSeen | (1.U(33.W)<<j); completed := completed+1.U
    when(j===0.U) { isolatedDone := true.B }
  }
  when(!batch && isolatedDone && p.quiescent) {
    assert(sent===1.U && completed===1.U && !rp.asUInt.orR && !wp.asUInt.orR && !readHeld && !writeHeld,
      "FAST_PERF_ISOLATED_BARRIER")
    batch := true.B; printf("FAST_PERF_BATCH_BEGIN cycle=%d\n",cycle)
  }
  when(completed===33.U && p.quiescent && !finished) {
    assert(resultSeen.andR && !rp.asUInt.orR && !wp.asUInt.orR && !readHeld && !writeHeld,
      "FAST_PERF_FINAL_QUIESCENT")
    finished := true.B; printf("FAST_PERF_SUITE_PASS image=%d delayed=%d tasks=33\n",image,delayed)
  }
  when(!finished) { assert(cycle<1000000.U,"FAST_PERF_TIMEOUT") }
}
object EmitFastNativePerfTest {
  def main(args: Array[String]): Unit = {
    require(args.length==5 || (args.length==6 && args(5)=="diagnostic"))
    def design = new FastNativePerfTest(args(2).toInt,args(3).toInt,args(1),args(4))
    ChiselStage.emitCHIRRTLFile(design,Array("--target-dir",args(0)+"/chirrtl"))
    val diagnostics=if (args.length==6) Array("--mlir-print-ir-before-all",
      "--mlir-pass-pipeline-crash-reproducer="+args(0)+"/crash.mlir") else Array.empty[String]
    ChiselStage.emitSystemVerilogFile(design,Array("--target-dir",args(0)),
      Array("--split-verilog","--disable-all-randomization","--strip-debug-info")++diagnostics)
  }
}
