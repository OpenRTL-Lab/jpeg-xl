// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl.fast

import chisel3._
import chisel3.util._

class FastRunInput extends Bundle {
  val block = new FastChunkOutput
  val context = Bool()
  val first = Bool()
  val last = Bool()
  val sampling = Bool()
}

class FastRunOutput extends Bundle {
  val input = new FastRunInput
  val emit = Vec(4, Bool())
  val run = Vec(4, UInt(17.W))
  val skip = Vec(4, UInt(4.W))
}

/** Four parallel run carries, two interleavable contexts, one elastic stage.
  * Sampling intentionally ignores an unbroken final run, like official stats.
  * No image bytes or software expectations are stored in the product.
  */
class FastRunPlanner extends RawModule {
  val clock = IO(Input(Clock()))
  val rst_n = IO(Input(Bool()))
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new FastRunInput))
    val output = Decoupled(new FastRunOutput)
  })
  withClockAndReset(clock, (!rst_n).asAsyncReset) {
    val carries = RegInit(VecInit(Seq.fill(2)(VecInit(Seq.fill(4)(0.U(17.W))))))
    val active = RegInit(VecInit(Seq.fill(2)(false.B)))
    val tags = Reg(Vec(2, UInt(32.W)))
    val channels = Reg(Vec(2, UInt(3.W)))
    val sampling = Reg(Vec(2, Bool()))
    val output = Reg(new FastRunOutput)
    val valid = RegInit(false.B)
    val advance = !valid || io.output.ready
    io.input.ready := rst_n && advance
    io.output.valid := rst_n && valid
    io.output.bits := output
    when(advance) { valid := io.input.valid && rst_n }
    when(io.input.fire) {
      val in = io.input.bits
      val ctx = in.context.asUInt
      val block = in.block
      assert(block.count >= 1.U && block.count <= 8.U, "FAST_RUN_COUNT")
      assert(block.channels >= 1.U && block.channels <= 4.U, "FAST_RUN_CHANNELS")
      when(in.first) {
        assert(!active(ctx), "FAST_RUN_CONTEXT_ALREADY_ACTIVE")
        tags(ctx) := block.tag
        channels(ctx) := block.channels
        sampling(ctx) := in.sampling
      }.otherwise {
        assert(active(ctx), "FAST_RUN_CONTEXT_NOT_ACTIVE")
        assert(tags(ctx) === block.tag && channels(ctx) === block.channels &&
          sampling(ctx) === in.sampling, "FAST_RUN_CONTEXT_IDENTITY")
      }
      active(ctx) := !in.last
      output.input := in
      for (c <- 0 until 4) {
        val enabled = c.U < block.channels
        val prefix = block.zeroPrefix(c)
        val old = Mux(in.first, 0.U, carries(ctx)(c))
        val total = old +& prefix
        val continued = prefix === block.count && (old =/= 0.U || prefix >= 8.U)
        val broken = total >= 8.U
        val flush = in.last && !in.sampling
        when(enabled) {
          assert(prefix <= block.count, "FAST_RUN_PREFIX_RANGE")
          assert(old === 0.U || old >= 8.U, "FAST_RUN_CARRY_INVARIANT")
          assert(total <= 65536.U, "FAST_RUN_IMAGE_CAPACITY")
        }
        output.emit(c) := enabled && (!continued || flush)
        output.run(c) := Mux(enabled && (continued && flush || !continued && broken),
          total, 0.U)
        output.skip(c) := Mux(enabled && continued && flush, block.count,
          Mux(enabled && !continued && broken, prefix, 0.U))
        carries(ctx)(c) := Mux(enabled && continued && !in.last, total, 0.U)
      }
    }
  }
}
