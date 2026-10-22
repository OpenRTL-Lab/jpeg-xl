// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl.fast

import chisel3._
import chisel3.util._

class FastPixelWrite extends Bundle {
  val context = Bool(); val wordIndex = UInt(12.W); val data = UInt(512.W)
}
class FastWindowRequest extends Bundle {
  val context = Bool(); val tag = UInt(32.W)
  val currentByteOffset = UInt(18.W); val topByteOffset = UInt(18.W)
  val count = UInt(4.W); val channels = UInt(3.W)
  val rowFirst = Bool(); val imageFirstRow = Bool()
  val first = Bool(); val last = Bool(); val sampling = Bool()
}
class FastWindowResult extends Bundle {
  val block = new FastChunkInput
  val context = Bool(); val first = Bool(); val last = Bool(); val sampling = Bool()
}
/** Four true 1R1W replicas, two images; scanner/loaded lifecycle belongs above. */
class FastPixelWindow extends RawModule {
  val clock = IO(Input(Clock())); val rst_n = IO(Input(Bool()))
  val io = IO(new Bundle {
    val write = Flipped(Decoupled(new FastPixelWrite))
    val request = Flipped(Decoupled(new FastWindowRequest))
    val output = Decoupled(new FastWindowResult)
  })
  withClockAndReset(clock, (!rst_n).asAsyncReset) {
    val memories = Seq.fill(4)(FastFpgaMemory(512, 8192, true, clock))
    io.write.ready := rst_n
    when(io.write.fire) {
      val address = Cat(io.write.bits.context, io.write.bits.wordIndex)
      memories.foreach { memory =>
        memory.io.wen := true.B; memory.io.waddr := address; memory.io.wdata := io.write.bits.data
      }
    }
    val pending = Reg(new FastWindowRequest); val readValid = RegInit(false.B)
    val output = Reg(new FastWindowResult); val valid = RegInit(false.B)
    val advance = !valid || io.output.ready
    val readAdvance = !readValid || advance
    io.request.ready := rst_n && readAdvance
    io.output.valid := rst_n && valid; io.output.bits := output
    val request = io.request.bits
    val payloadBytes = request.count*request.channels
    val addresses = Wire(Vec(4, UInt(13.W)))
    addresses(0) := Cat(request.context, request.currentByteOffset(17, 6))
    addresses(1) := addresses(0)+Mux(request.currentByteOffset(5, 0)+&payloadBytes > 64.U, 1.U, 0.U)
    addresses(2) := Cat(request.context, request.topByteOffset(17, 6))
    addresses(3) := addresses(2)+Mux(request.topByteOffset(5, 0)+&payloadBytes > 64.U, 1.U, 0.U)
    val read = memories.zipWithIndex.map { case (m, i) =>
      m.io.raddr := addresses(i); m.io.ren := io.request.fire; m.io.rdata
    }
    // SyncReadMem does not promise an output value in a disabled-read cycle.
    // Capture a blocked response once; do not rely on inferred SRAM retention.
    val heldRead = Reg(Vec(4, UInt(512.W))); val holding = RegInit(false.B)
    when(readValid && !advance && !holding) {
      heldRead := VecInit(read); holding := true.B
    }
    when(advance) { holding := false.B }
    val response = (0 until 4).map(i => Mux(holding, heldRead(i), read(i)))
    when(io.write.fire && readValid) {
      assert(io.write.bits.context =/= pending.context, "FAST_WINDOW_PENDING_READ_IMMUTABLE")
    }
    when(io.request.fire) {
      assert(request.count >= 1.U && request.count <= 8.U && request.channels >= 1.U && request.channels <= 4.U,
        "FAST_WINDOW_FIELDS")
      assert(request.currentByteOffset +& payloadBytes <= 262144.U &&
        request.topByteOffset +& payloadBytes <= 262144.U, "FAST_WINDOW_RAW_BOUNDS")
      when(io.write.fire) {
        assert(io.write.bits.context =/= request.context, "FAST_WINDOW_IMMUTABLE_READ_CONTEXT")
      }
      pending := request
    }
    when(readAdvance) { readValid := io.request.valid && rst_n }
    val leftCarry = Reg(Vec(2, Vec(4, UInt(8.W))))
    val topCarry = Reg(Vec(2, Vec(4, UInt(8.W))))
    val carryTags = Reg(Vec(2, UInt(32.W))); val carryChannels = Reg(Vec(2, UInt(3.W)))
    val carryValid = RegInit(VecInit(Seq.fill(2)(false.B)))
    when(advance) {
      valid := readValid
      when(readValid) {
        val p = pending; val ctx = p.context.asUInt
        val current = (Cat(response(1), response(0)) >> Cat(p.currentByteOffset(5, 0), 0.U(3.W)))(255, 0)
        val top = (Cat(response(3), response(2)) >> Cat(p.topByteOffset(5, 0), 0.U(3.W)))(255, 0)
        val currentBytes = current.asTypeOf(Vec(32, UInt(8.W)))
        val topBytes = top.asTypeOf(Vec(32, UInt(8.W)))
        val pixels = Wire(Vec(8, Vec(4, UInt(8.W))))
        val topPixels = Wire(Vec(8, Vec(4, UInt(8.W))))
        for (lane <- 0 until 8; c <- 0 until 4) {
          val at = Wire(UInt(5.W)); at := lane.U*p.channels+c.U
          val enabled = c.U < p.channels && lane.U < p.count
          pixels(lane)(c) := Mux(enabled, currentBytes(at), 0.U)
          topPixels(lane)(c) := Mux(enabled, topBytes(at), 0.U)
        }
        when(!p.rowFirst) {
          assert(carryValid(ctx) && carryTags(ctx) === p.tag && carryChannels(ctx) === p.channels,
            "FAST_WINDOW_CARRY_IDENTITY")
        }
        val lastLane = Wire(UInt(3.W)); lastLane := p.count-1.U
        for (c <- 0 until 4) {
          leftCarry(ctx)(c) := pixels(lastLane)(c); topCarry(ctx)(c) := topPixels(lastLane)(c)
        }
        carryTags(ctx) := p.tag; carryChannels(ctx) := p.channels; carryValid(ctx) := !p.last
        output.block.tag := p.tag; output.block.count := p.count; output.block.channels := p.channels
        output.context := p.context; output.first := p.first; output.last := p.last; output.sampling := p.sampling
        for (lane <- 0 until 8; c <- 0 until 4) {
          val left = if (lane > 0) pixels(lane-1)(c) else
            Mux(p.rowFirst, Mux(p.imageFirstRow, 0.U, topPixels(0)(c)), leftCarry(ctx)(c))
          val topLeft = if (lane > 0) topPixels(lane-1)(c) else
            Mux(p.rowFirst, topPixels(0)(c), topCarry(ctx)(c))
          output.block.pixels(0)(lane)(c) := pixels(lane)(c)
          output.block.pixels(1)(lane)(c) := left
          output.block.pixels(2)(lane)(c) := Mux(p.imageFirstRow, left, topPixels(lane)(c))
          output.block.pixels(3)(lane)(c) := Mux(p.imageFirstRow, left, topLeft)
        }
      }
    }
  }
}

class FastWindowMetadata extends Bundle {
  val context = Bool(); val first = Bool(); val last = Bool(); val sampling = Bool()
}
class FastPixelFrontResult extends Bundle {
  val block = new FastChunkOutput
  val metadata = new FastWindowMetadata
}
/** Real raw-image memories -> neighbor gather -> three-stage arithmetic. */
class FastPixelFront extends RawModule {
  val clock = IO(Input(Clock())); val rst_n = IO(Input(Bool()))
  val io = IO(new Bundle {
    val write = Flipped(Decoupled(new FastPixelWrite))
    val request = Flipped(Decoupled(new FastWindowRequest))
    val output = Decoupled(new FastPixelFrontResult)
  })
  val window = Module(new FastPixelWindow); val arithmetic = Module(new FastChunk8)
  window.clock := clock; window.rst_n := rst_n
  arithmetic.clock := clock; arithmetic.rst_n := rst_n
  window.io.write <> io.write; window.io.request <> io.request
  withClockAndReset(clock, (!rst_n).asAsyncReset) {
    val metadata = Module(new Queue(new FastWindowMetadata, 4))
    metadata.io.enq.bits.context := window.io.output.bits.context
    metadata.io.enq.bits.first := window.io.output.bits.first
    metadata.io.enq.bits.last := window.io.output.bits.last
    metadata.io.enq.bits.sampling := window.io.output.bits.sampling
    metadata.io.enq.valid := window.io.output.valid && arithmetic.io.input.ready
    arithmetic.io.input.valid := window.io.output.valid && metadata.io.enq.ready
    arithmetic.io.input.bits := window.io.output.bits.block
    window.io.output.ready := arithmetic.io.input.ready && metadata.io.enq.ready
    io.output.valid := arithmetic.io.output.valid && metadata.io.deq.valid
    io.output.bits.block := arithmetic.io.output.bits
    io.output.bits.metadata := metadata.io.deq.bits
    arithmetic.io.output.ready := io.output.ready && metadata.io.deq.valid
    metadata.io.deq.ready := io.output.ready && arithmetic.io.output.valid
  }
}
