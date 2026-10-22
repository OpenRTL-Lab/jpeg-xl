// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl.fast

import chisel3._
import chisel3.util._

/** Exact, conservative mathematical identity; no hash or truncated histogram. */
class FastPrefixMemoKey extends Bundle {
  val channels = UInt(3.W)
  val samples = UInt(10.W)
  val raw = Vec(4, Vec(19, UInt(18.W)))
  val lz = Vec(4, Vec(33, UInt(18.W)))
}

/** Two entries populated only by this instance's original four hardware solvers.
  * A cold miss has no extra pipeline stage. A hit is registered and retagged.
  * This component must be qualified before integration into FastEncoderCore.
  */
class FastPrefixMemoGroup extends RawModule {
  val clock = IO(Input(Clock())); val rst_n = IO(Input(Bool()))
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new FastStatisticsResult))
    val output = Decoupled(new FastPrefixGroupResult)
  })
  val original = Module(new FastPrefixGroup)
  original.clock := clock; original.rst_n := rst_n
  withClockAndReset(clock, (!rst_n).asAsyncReset) {
    val keys = Reg(Vec(2, new FastPrefixMemoKey))
    val values = Reg(Vec(2, new FastPrefixGroupResult))
    val valid = RegInit(VecInit(Seq.fill(2)(false.B)))
    val victim = RegInit(false.B)
    val pending = RegInit(false.B)
    val pendingKey = Reg(new FastPrefixMemoKey)
    val pendingTag = Reg(UInt(32.W))
    val hitValid = RegInit(false.B)
    val hitResult = Reg(new FastPrefixGroupResult)
    val key = Wire(new FastPrefixMemoKey)
    key.channels := io.input.bits.channels; key.samples := io.input.bits.samples
    key.raw := io.input.bits.raw; key.lz := io.input.bits.lz
    val matches = VecInit((0 until 2).map(i => valid(i) && keys(i).asUInt === key.asUInt))
    val hit = matches.asUInt.orR
    val selected = PriorityEncoder(matches.asUInt)
    val idle = !pending && !hitValid
    io.input.ready := rst_n && idle && (hit || original.io.input.ready)
    original.io.input.valid := rst_n && idle && io.input.valid && !hit
    original.io.input.bits := io.input.bits
    io.output.valid := rst_n && (hitValid || pending && original.io.output.valid)
    io.output.bits := Mux(hitValid, hitResult, original.io.output.bits)
    original.io.output.ready := rst_n && pending && !hitValid && io.output.ready
    when(io.input.fire) {
      when(hit) {
        hitResult := values(selected)
        hitResult.tag := io.input.bits.tag
        for (c <- 0 until 4) hitResult.tables(c).tag := io.input.bits.tag
        hitValid := true.B
      }.otherwise {
        pendingKey := key; pendingTag := io.input.bits.tag; pending := true.B
      }
    }
    when(original.io.output.fire) {
      assert(pending && original.io.output.bits.tag === pendingTag, "FAST_MEMO_MISS_TAG")
      assert(original.io.output.bits.channels === pendingKey.channels, "FAST_MEMO_MISS_LAYOUT")
      keys(victim.asUInt) := pendingKey
      values(victim.asUInt) := original.io.output.bits
      valid(victim.asUInt) := true.B
      victim := !victim; pending := false.B
    }
    when(io.output.fire && hitValid) { hitValid := false.B }
    assert(!(pending && hitValid), "FAST_MEMO_SINGLE_OWNER")
  }
}
