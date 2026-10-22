// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl.fast

import chisel3._
import chisel3.util._

class FastNativeCommand extends Bundle {
  val tag = UInt(32.W)
  val imageWidth = UInt(9.W); val imageHeight = UInt(9.W); val channels = UInt(3.W)
  val source = UInt(33.W); val destination = UInt(33.W)
  val sourceSpan = UInt(24.W); val destinationSpan = UInt(24.W); val outputLimit = UInt(24.W)
}
class FastNativeRead extends Bundle { val id = UInt(4.W); val address = UInt(33.W) }
class FastNativeReadResponse extends Bundle {
  val id = UInt(4.W); val data = UInt(512.W); val fault = Bool()
}
class FastNativeWrite extends Bundle {
  val id = UInt(4.W); val address = UInt(33.W); val data = UInt(512.W)
}
class FastNativeWriteResponse extends Bundle { val id = UInt(4.W); val fault = Bool() }
class FastNativeCancelAck extends Bundle { val tag = UInt(32.W); val found = Bool() }
class FastNativeResult extends Bundle {
  val tag = UInt(32.W); val status = UInt(3.W); val outputBytes = UInt(24.W)
  val faultAddress = UInt(33.W)
  val acceptedCycle = UInt(64.W); val firstFileWriteCycle = UInt(64.W); val retiredCycle = UInt(64.W)
  val readRequests = UInt(13.W); val writeRequests = UInt(15.W)
}
class FastNativePorts extends Bundle {
  val command = Flipped(Decoupled(new FastNativeCommand))
  val result = Decoupled(new FastNativeResult)
  val cancel = Flipped(Decoupled(UInt(32.W)))
  val cancelAck = Decoupled(new FastNativeCancelAck)
  val read = Decoupled(new FastNativeRead)
  val readResponse = Flipped(Decoupled(new FastNativeReadResponse))
  val write = Decoupled(new FastNativeWrite)
  val writeResponse = Flipped(Decoupled(new FastNativeWriteResponse))
  val quiescent = Output(Bool())
}

/** Protocol controller only: isolated internal ports are not a substitute encoder.
  * Four reserved descriptor/result slots; two dynamically bound compute contexts.
  * Every offered request reserves its ID until its one real response arrives.
  */
class FastNativeController(readCredits: Int = 16, writeCredits: Int = 16) extends RawModule {
  require(readCredits >= 1 && readCredits <= 16 && writeCredits >= 1 && writeCredits <= 16)
  val clock = IO(Input(Clock())); val rst_n = IO(Input(Bool()))
  val io = IO(new FastNativePorts)
  val rawWrite = IO(Decoupled(new FastPixelWrite))
  val coreCommand = IO(Decoupled(new FastEncoderCommand))
  val file = IO(Flipped(Decoupled(new FastFileWord)))
  withClockAndReset(clock,(!rst_n).asAsyncReset) {
    val free :: queued :: loading :: launching :: running :: draining :: done :: Nil = Enum(7)
    val state = RegInit(VecInit(Seq.fill(4)(free)))
    val descriptions = Reg(Vec(4,new FastNativeCommand))
    val reserved = RegInit(VecInit(Seq.fill(4)(false.B)))
    val status = RegInit(VecInit(Seq.fill(4)(0.U(3.W))))
    val faultAddress = Reg(Vec(4,UInt(33.W)))
    val accepted = Reg(Vec(4,UInt(64.W))); val retired = Reg(Vec(4,UInt(64.W)))
    val firstWrite = Reg(Vec(4,UInt(64.W)))
    val readCount = Reg(Vec(4,UInt(13.W))); val writeCount = Reg(Vec(4,UInt(15.W)))
    val fileBytes = Reg(Vec(4,UInt(24.W)))
    val nextWord = Reg(Vec(4,UInt(13.W))); val words = Reg(Vec(4,UInt(13.W)))
    val context = Reg(Vec(4,Bool()))
    val bound = RegInit(VecInit(Seq.fill(2)(false.B))); val owner = Reg(Vec(2,UInt(2.W)))
    val cycle = RegInit(0.U(64.W)); cycle := cycle+1.U

    val cancelReplies = Module(new Queue(new FastNativeCancelAck,2))
    io.cancelAck <> cancelReplies.io.deq
    io.cancel.ready := rst_n && cancelReplies.io.enq.ready
    val cancelHit = Wire(Vec(4,Bool()))
    for (j <- 0 until 4) cancelHit(j) := state(j)=/=free && state(j)=/=done &&
      reserved(j) && descriptions(j).tag===io.cancel.bits
    cancelReplies.io.enq.valid := io.cancel.valid && rst_n
    cancelReplies.io.enq.bits.tag := io.cancel.bits
    cancelReplies.io.enq.bits.found := cancelHit.asUInt.orR

    val slotAvailable = VecInit(state.map(_ === free)).asUInt
    val slot = PriorityEncoder(slotAvailable)
    io.command.ready := rst_n && slotAvailable.orR
    val in = io.command.bits
    val rawBytes = in.imageWidth*in.imageHeight*in.channels
    val rawPhysical = (rawBytes+&63.U) & ~63.U(22.W)
    val sourceEnd = in.source+&in.sourceSpan
    val destinationEnd = in.destination+&in.destinationSpan
    def overlaps(a: UInt, ae: UInt, b: UInt, be: UInt): Bool = a < be && b < ae
    val fieldsBad = in.imageWidth<1.U || in.imageWidth>256.U || in.imageHeight<1.U ||
      in.imageHeight>256.U || in.channels<1.U || in.channels>4.U ||
      in.source(5,0).orR || in.destination(5,0).orR || in.sourceSpan(5,0).orR ||
      in.destinationSpan(5,0).orR || in.sourceSpan<rawPhysical || in.destinationSpan===0.U ||
      in.outputLimit===0.U || in.outputLimit>in.destinationSpan ||
      sourceEnd>(BigInt(1)<<33).U || destinationEnd>(BigInt(1)<<33).U
    val conflict = Wire(Vec(4,Bool())); val duplicate = Wire(Vec(4,Bool()))
    for (j <- 0 until 4) {
      val d = descriptions(j)
      val se=d.source+&d.sourceSpan; val de=d.destination+&d.destinationSpan
      conflict(j) := reserved(j) && state(j)=/=free && (
        overlaps(in.source,sourceEnd,d.source,se) || overlaps(in.source,sourceEnd,d.destination,de) ||
        overlaps(in.destination,destinationEnd,d.source,se) || overlaps(in.destination,destinationEnd,d.destination,de))
      duplicate(j) := reserved(j) && state(j)=/=free && d.tag===in.tag
    }
    val initialStatus = Mux(fieldsBad,1.U,Mux(duplicate.asUInt.orR,3.U,
      Mux(conflict.asUInt.orR || overlaps(in.source,sourceEnd,in.destination,destinationEnd),2.U,0.U)))
    when(io.command.fire) {
      descriptions(slot) := in; reserved(slot) := initialStatus===0.U
      status(slot) := initialStatus; state(slot) := Mux(initialStatus===0.U,queued,done)
      faultAddress(slot) := 0.U; accepted(slot) := cycle; retired(slot) := cycle
      firstWrite(slot) := 0.U; readCount(slot) := 0.U; writeCount(slot) := 0.U
      fileBytes(slot) := 0.U; nextWord(slot) := 0.U; words(slot) := (rawBytes+&63.U)>>6
    }

    // Binding is round-robin among queued descriptors. Result slots can remain
    // occupied while a released compute context starts the next descriptor.
    val bindRequests = Module(new RRArbiter(UInt(2.W),4))
    for (j <- 0 until 4) {
      bindRequests.io.in(j).valid := state(j)===queued && status(j)===0.U &&
        !(io.cancel.fire && cancelHit(j))
      bindRequests.io.in(j).bits := j.U
    }
    val freeContext = !bound(0) || !bound(1)
    val chosenContext = bound(0)
    bindRequests.io.out.ready := rst_n && freeContext
    when(bindRequests.io.out.fire) {
      val j=bindRequests.io.out.bits
      context(j) := chosenContext; owner(chosenContext.asUInt) := j
      bound(chosenContext.asUInt) := true.B; state(j) := loading
    }

    // 0 free, 1 offered (stable request not yet accepted), 2 issued.
    val readState = RegInit(VecInit(Seq.fill(16)(0.U(2.W))))
    val readOwner = Reg(Vec(16,UInt(2.W))); val readWord = Reg(Vec(16,UInt(12.W)))
    val readAddress = Reg(Vec(16,UInt(33.W)))
    val writeState = RegInit(VecInit(Seq.fill(16)(0.U(2.W))))
    val writeOwner = Reg(Vec(16,UInt(2.W))); val writeAddress = Reg(Vec(16,UInt(33.W)))
    val readPending = Wire(Vec(4,Bool())); val writePending = Wire(Vec(4,Bool()))
    for (j <- 0 until 4) {
      readPending(j) := VecInit((0 until 16).map(i => readState(i)=/=0.U && readOwner(i)===j.U)).asUInt.orR
      writePending(j) := VecInit((0 until 16).map(i => writeState(i)=/=0.U && writeOwner(i)===j.U)).asUInt.orR
    }
    val rid=io.readResponse.bits.id; val wid=io.writeResponse.bits.id
    val rj=readOwner(rid); val wj=writeOwner(wid)
    val abort = Wire(Vec(4,Bool()))
    for (j <- 0 until 4) {
      abort(j) := status(j)=/=0.U || (io.cancel.fire && cancelHit(j)) ||
        (io.readResponse.valid && readState(rid)===2.U && rj===j.U && io.readResponse.bits.fault) ||
        (io.writeResponse.fire && wj===j.U && io.writeResponse.bits.fault)
    }
    rawWrite.valid := rst_n && io.readResponse.valid && readState(rid)===2.U &&
      !io.readResponse.bits.fault && !abort(rj)
    rawWrite.bits.context := context(rj); rawWrite.bits.wordIndex := readWord(rid)
    rawWrite.bits.data := io.readResponse.bits.data
    // Unknown/duplicate IDs are adapter protocol errors, not codec failures.
    io.readResponse.ready := rst_n && readState(rid)===2.U &&
      (io.readResponse.bits.fault || abort(rj) || rawWrite.ready)
    io.writeResponse.ready := rst_n && writeState(wid)===2.U
    // A zero-latency adapter may present valid in the request-handshake
    // cycle. Backpressure it until the scoreboard has become issued.
    when(rst_n && io.readResponse.valid) { assert(readState(rid)=/=0.U,"FAST_NATIVE_READ_ID") }
    when(rst_n && io.writeResponse.valid) { assert(writeState(wid)=/=0.U,"FAST_NATIVE_WRITE_ID") }
    when(io.readResponse.fire) { readState(rid) := 0.U }
    when(io.writeResponse.fire) { writeState(wid) := 0.U }

    val readHeld = RegInit(false.B); val readRequest = Reg(new FastNativeRead)
    io.read.valid := rst_n && readHeld; io.read.bits := readRequest
    when(io.read.fire) {
      readState(readRequest.id) := 2.U; readHeld := false.B
      val j=readOwner(readRequest.id); readCount(j) := readCount(j)+1.U
    }
    val freeReadIds=VecInit((0 until 16).map(i => (i<readCredits).B && readState(i)===0.U)).asUInt
    val readId=PriorityEncoder(freeReadIds)
    val readJobs = Module(new RRArbiter(UInt(2.W),4))
    for (j <- 0 until 4) {
      readJobs.io.in(j).valid := state(j)===loading && nextWord(j)<words(j) && !abort(j)
      readJobs.io.in(j).bits := j.U
    }
    readJobs.io.out.ready := rst_n && (!readHeld || io.read.ready) && freeReadIds.orR
    when(readJobs.io.out.fire) {
      val j=readJobs.io.out.bits
      val address=descriptions(j).source+(nextWord(j)<<6)
      readHeld := true.B; readRequest.id := readId; readRequest.address := address
      readState(readId) := 1.U; readOwner(readId) := j; readWord(readId) := nextWord(j)
      readAddress(readId) := address; nextWord(j) := nextWord(j)+1.U
    }

    val launch = Module(new RRArbiter(new FastEncoderCommand,4))
    for (j <- 0 until 4) {
      val d=descriptions(j)
      launch.io.in(j).valid := state(j)===launching && !abort(j)
      launch.io.in(j).bits.context := context(j); launch.io.in(j).bits.tag := d.tag
      launch.io.in(j).bits.imageWidth := d.imageWidth; launch.io.in(j).bits.imageHeight := d.imageHeight
      launch.io.in(j).bits.channels := d.channels; launch.io.in(j).bits.baseByteOffset := 0.U
      launch.io.in(j).bits.outputLimit := 2097151.U
      when(launch.io.in(j).fire) { state(j) := running }
    }
    // Stable command register: cancellation cannot withdraw an offered command.
    val launchHeld = RegInit(false.B); val launchBits = Reg(new FastEncoderCommand)
    coreCommand.valid := rst_n && launchHeld; coreCommand.bits := launchBits
    launch.io.out.ready := rst_n && !launchHeld
    when(launch.io.out.fire) {
      launchHeld := true.B; launchBits := launch.io.out.bits
    }
    when(coreCommand.fire) { launchHeld := false.B }

    val writeHeld = RegInit(false.B); val writeRequest = Reg(new FastNativeWrite)
    io.write.valid := rst_n && writeHeld; io.write.bits := writeRequest
    when(io.write.fire) {
      writeState(writeRequest.id) := 2.U; writeHeld := false.B
      val j=writeOwner(writeRequest.id)
      when(writeCount(j)===0.U) { firstWrite(j) := cycle }
      writeCount(j) := writeCount(j)+1.U
    }
    val fj=owner(file.bits.context.asUInt)
    val filePhysical=(file.bits.totalBytes+&63.U) & ~63.U(25.W)
    val capacityBad=file.bits.totalBytes>descriptions(fj).outputLimit || filePhysical>descriptions(fj).destinationSpan
    val discard=abort(fj) || capacityBad
    val freeWriteIds=VecInit((0 until 16).map(i => (i<writeCredits).B && writeState(i)===0.U)).asUInt
    val writeId=PriorityEncoder(freeWriteIds)
    file.ready := rst_n && (discard || ((!writeHeld || io.write.ready) && freeWriteIds.orR))
    when(file.fire) {
      assert(bound(file.bits.context.asUInt) && state(fj)===running,"FAST_NATIVE_FILE_OWNER")
      assert(file.bits.tag===descriptions(fj).tag,"FAST_NATIVE_FILE_TAG")
      when(file.bits.first) { fileBytes(fj) := file.bits.totalBytes }
      when(!discard) {
        writeHeld := true.B; writeRequest.id := writeId
        writeRequest.address := descriptions(fj).destination+file.bits.byteOffset
        writeRequest.data := file.bits.bits
        writeState(writeId) := 1.U; writeOwner(writeId) := fj
        writeAddress(writeId) := descriptions(fj).destination+file.bits.byteOffset
        assert(file.bits.byteOffset(5,0)===0.U && file.bits.byteOffset+&64.U<=descriptions(fj).destinationSpan,
          "FAST_NATIVE_WRITE_RANGE")
      }
      when(file.bits.last) { state(fj) := draining }
    }

    // First error wins. Same-cycle cancellation has precedence over a newly
    // reported memory fault, then read, write, capacity in fixed order.
    for (j <- 0 until 4) {
      when(status(j)===0.U && state(j)=/=free && state(j)=/=done) {
        when(io.cancel.fire && cancelHit(j)) { status(j) := 7.U }
          .elsewhen(io.readResponse.fire && rj===j.U && io.readResponse.bits.fault) {
            status(j) := 4.U; faultAddress(j) := readAddress(rid)
          }.elsewhen(io.writeResponse.fire && wj===j.U && io.writeResponse.bits.fault) {
            status(j) := 5.U; faultAddress(j) := writeAddress(wid)
          }.elsewhen(file.fire && fj===j.U && capacityBad) { status(j) := 6.U }
      }
      when(state(j)===queued && abort(j)) { state(j) := done; retired(j) := cycle }
      when(state(j)===loading && !readPending(j)) {
        when(abort(j)) {
          state(j) := done; retired(j) := cycle; bound(context(j).asUInt) := false.B
        }.elsewhen(nextWord(j)===words(j)) { state(j) := launching }
      }
      when(state(j)===launching && abort(j)) {
        state(j) := done; retired(j) := cycle; bound(context(j).asUInt) := false.B
      }
      when(state(j)===draining && !writePending(j) && !readPending(j)) {
        state(j) := done; retired(j) := cycle; bound(context(j).asUInt) := false.B
      }
    }

    val results = Module(new RRArbiter(new FastNativeResult,4))
    val resultHeld = RegInit(false.B); val resultBits = Reg(new FastNativeResult); val resultOwner = Reg(UInt(2.W))
    for (j <- 0 until 4) {
      results.io.in(j).valid := state(j)===done
      results.io.in(j).bits.tag := descriptions(j).tag; results.io.in(j).bits.status := status(j)
      results.io.in(j).bits.outputBytes := fileBytes(j); results.io.in(j).bits.faultAddress := faultAddress(j)
      results.io.in(j).bits.acceptedCycle := accepted(j); results.io.in(j).bits.firstFileWriteCycle := firstWrite(j)
      results.io.in(j).bits.retiredCycle := retired(j); results.io.in(j).bits.readRequests := readCount(j)
      results.io.in(j).bits.writeRequests := writeCount(j)
    }
    results.io.out.ready := rst_n && !resultHeld
    when(results.io.out.fire) {
      resultHeld := true.B; resultBits := results.io.out.bits; resultOwner := results.io.chosen
    }
    io.result.valid := rst_n && resultHeld; io.result.bits := resultBits
    when(io.result.fire) { resultHeld := false.B; state(resultOwner) := free; reserved(resultOwner) := false.B }
    io.quiescent := !VecInit(state.map(_ =/= free)).asUInt.orR && !bound.asUInt.orR &&
      !readHeld && !writeHeld && !launchHeld && !resultHeld && !cancelReplies.io.deq.valid
  }
}

/** Native-only subsystem. No AXI/MMIO/SoC or software codec datapath. */
class FastNativeEncoder(readCredits: Int = 16, writeCredits: Int = 16) extends RawModule {
  val clock = IO(Input(Clock())); val rst_n = IO(Input(Bool()))
  val io = IO(new FastNativePorts)
  val control = Module(new FastNativeController(readCredits,writeCredits))
  val core = Module(new FastEncoderCore)
  control.clock := clock; control.rst_n := rst_n; core.clock := clock; core.rst_n := rst_n
  io <> control.io
  core.io.write <> control.rawWrite; core.io.command <> control.coreCommand
  control.file <> core.io.output
}
