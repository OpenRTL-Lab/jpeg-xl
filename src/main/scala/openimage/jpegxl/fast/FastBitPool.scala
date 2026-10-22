// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl.fast

import chisel3._
import chisel3.util._

class FastPoolWord extends Bundle {
  val tag = UInt(32.W); val context = Bool(); val channels = UInt(3.W)
  val first = Bool(); val last = Bool()
  val bits = Vec(4, UInt(256.W)); val lengths = Vec(4, UInt(9.W))
  val offsets = Vec(4, UInt(24.W)); val totalBits = Vec(4, UInt(24.W))
}
/** Independent channel bit order, two contexts, no byte padding or memory bus. */
class FastBitPool extends RawModule {
  val clock = IO(Input(Clock())); val rst_n = IO(Input(Bool()))
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new FastPackedBlock))
    val output = Decoupled(new FastPoolWord)
  })
  withClockAndReset(clock, (!rst_n).asAsyncReset) {
    // Isolate the upstream round-robin grant from the reservoir feedback cone.
    val acceptedBlock = Module(new Queue(new FastPackedBlock, 1, pipe = true, flow = false))
    acceptedBlock.io.enq.valid := rst_n && io.input.valid
    acceptedBlock.io.enq.bits := io.input.bits
    io.input.ready := rst_n && acceptedBlock.io.enq.ready
    val input = acceptedBlock.io.deq
    val active = RegInit(VecInit(Seq.fill(2)(false.B)))
    val emitted = Reg(Vec(2, Bool()))
    val tags = Reg(Vec(2, UInt(32.W))); val channels = Reg(Vec(2, UInt(3.W)))
    val buffer = Reg(Vec(2, Vec(4, UInt(256.W))))
    val counts = Reg(Vec(2, Vec(4, UInt(8.W))))
    val totals = Reg(Vec(2, Vec(4, UInt(24.W))))
    val output = Reg(new FastPoolWord); val tail = Reg(new FastPoolWord)
    val valid = RegInit(false.B); val tailValid = RegInit(false.B)
    val advance = !valid || io.output.ready
    input.ready := rst_n && advance && !tailValid
    io.output.valid := rst_n && valid
    io.output.bits := output
    when(advance) {
      valid := tailValid
      when(tailValid) { output := tail; tailValid := false.B }
    }
    def mask256(length: UInt): UInt = FastRetiming.mask(length, 256)
    when(input.fire) {
      val in = input.bits; val ctx = in.context.asUInt
      when(in.first) {
        assert(!active(ctx), "FAST_POOL_CONTEXT_ACTIVE")
        tags(ctx) := in.tag; channels(ctx) := in.channels
      }.otherwise {
        assert(active(ctx) && tags(ctx) === in.tag && channels(ctx) === in.channels,
          "FAST_POOL_CONTEXT_IDENTITY")
      }
      assert(in.channels >= 1.U && in.channels <= 4.U, "FAST_POOL_LAYOUT")
      active(ctx) := !in.last
      val sums = Wire(Vec(4, UInt(10.W)))
      val combined = Wire(Vec(4, UInt(512.W)))
      val previous = Wire(Vec(4, UInt(24.W)))
      val oldCounts = Wire(Vec(4, UInt(8.W)))
      for (c <- 0 until 4) {
        val old = Mux(in.first, 0.U, buffer(ctx)(c))
        oldCounts(c) := Mux(in.first, 0.U, counts(ctx)(c))
        previous(c) := Mux(in.first, 0.U, totals(ctx)(c))
        assert(in.lengths(c) <= 256.U, "FAST_POOL_BLOCK_LENGTH")
        when(c.U >= in.channels) { assert(in.lengths(c) === 0.U, "FAST_POOL_INACTIVE_CHANNEL") }
        sums(c) := oldCounts(c) +& in.lengths(c)
        combined(c) := old | ((in.bits(c) & mask256(in.lengths(c))).pad(512) << oldCounts(c))
        assert(sums(c) <= 511.U, "FAST_POOL_COMBINED_CAPACITY")
      }
      val twoPackets = in.last && sums.map(_ > 256.U).reduce(_ || _)
      val finalSingle = in.last && !twoPackets
      val send = in.last || sums.map(_ >= 256.U).reduce(_ || _)
      val wasEmitted = !in.first && emitted(ctx)
      val nextWord = Wire(new FastPoolWord); val lastWord = Wire(new FastPoolWord)
      nextWord := 0.U.asTypeOf(nextWord); lastWord := 0.U.asTypeOf(lastWord)
      nextWord.tag := in.tag; nextWord.context := in.context; nextWord.channels := in.channels
      nextWord.first := !wasEmitted; nextWord.last := in.last && !twoPackets
      lastWord.tag := in.tag; lastWord.context := in.context; lastWord.channels := in.channels
      lastWord.first := false.B; lastWord.last := true.B
      for (c <- 0 until 4) {
        val take = Mux(finalSingle, sums(c), Mux(sums(c) >= 256.U, 256.U, 0.U))
        // Otherwise take is exactly 0 or 256 and sum<=511. There is no
        // variable right barrel shifter or wide subtract-one mask here.
        val remaining = Mux(finalSingle, 0.U(8.W), sums(c)(7, 0))
        val remainderBits = Mux(sums(c) >= 256.U, combined(c)(511, 256),
          combined(c)(255, 0)) & mask256(remaining)
        val after = previous(c) +& in.lengths(c)
        assert(!after(24), "FAST_POOL_TOTAL_OVERFLOW")
        totals(ctx)(c) := after
        nextWord.lengths(c) := take
        nextWord.bits(c) := combined(c)(255, 0) & mask256(take)
        nextWord.offsets(c) := previous(c) - oldCounts(c)
        nextWord.totalBits(c) := after
        lastWord.lengths(c) := remaining
        lastWord.bits(c) := remainderBits
        lastWord.offsets(c) := previous(c) - oldCounts(c) + take
        lastWord.totalBits(c) := after
        buffer(ctx)(c) := remainderBits
        counts(ctx)(c) := remaining
      }
      emitted(ctx) := wasEmitted || send
      when(send) { output := nextWord; valid := true.B }
      when(twoPackets) { tail := lastWord; tailValid := true.B }
    }
  }
}
