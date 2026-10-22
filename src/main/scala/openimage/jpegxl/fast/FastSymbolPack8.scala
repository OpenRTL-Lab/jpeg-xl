// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl.fast

import chisel3._
import chisel3.util._

class FastPackInput extends Bundle {
  val planned = new FastRunOutput
  val tables = Vec(4, new FastPrefixResult)
}
class FastPackedBlock extends Bundle {
  val tag = UInt(32.W)
  val context = Bool()
  val first = Bool(); val last = Bool()
  val channels = UInt(3.W); val count = UInt(4.W)
  val bits = Vec(4, UInt(256.W))
  val lengths = Vec(4, UInt(9.W))
}
class FastPackSegments extends Bundle {
  val metadata = new FastPackedBlock
  val bits = Vec(4, Vec(9, UInt(48.W)))
  val lengths = Vec(4, Vec(9, UInt(6.W)))
}

/** Four channels, eight literals plus a run prefix, two elastic stages.
  * No byte padding and no context bit-pool/file assembly in this component.
  */
class FastSymbolPack8 extends RawModule {
  val clock = IO(Input(Clock())); val rst_n = IO(Input(Bool()))
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new FastPackInput))
    val output = Decoupled(new FastPackedBlock)
  })
  withClockAndReset(clock, (!rst_n).asAsyncReset) {
    val segments = Reg(new FastPackSegments)
    val packed = Reg(new FastPackedBlock)
    val valid1 = RegInit(false.B); val valid2 = RegInit(false.B)
    val advance2 = !valid2 || io.output.ready
    val advance1 = !valid1 || advance2
    io.input.ready := rst_n && advance1
    io.output.valid := rst_n && valid2
    io.output.bits := packed
    when(advance1) { valid1 := rst_n && io.input.valid }
    when(advance2) { valid2 := rst_n && valid1 }

    def masked(value: UInt, length: UInt): UInt = {
      val mask = ((1.U(49.W) << length) - 1.U)(47, 0)
      value.pad(48)(47, 0) & mask
    }
    when(io.input.fire) {
      val in = io.input.bits.planned
      val block = in.input.block
      assert(!in.input.sampling, "FAST_PACK_ENCODING_ONLY")
      assert(block.count >= 1.U && block.count <= 8.U, "FAST_PACK_COUNT")
      assert(block.channels >= 1.U && block.channels <= 4.U, "FAST_PACK_LAYOUT")
      segments.metadata := 0.U.asTypeOf(segments.metadata)
      segments.metadata.tag := block.tag
      segments.metadata.context := in.input.context
      segments.metadata.first := in.input.first; segments.metadata.last := in.input.last
      segments.metadata.channels := block.channels; segments.metadata.count := block.count
      for (c <- 0 until 4) {
        val code = io.input.bits.tables(c)
        val enabled = c.U < block.channels && in.emit(c)
        assert(code.tag === block.tag, "FAST_PACK_TABLE_TAG")
        assert(in.skip(c) <= block.count, "FAST_PACK_SKIP")
        assert(in.run(c) === 0.U || in.run(c) >= 8.U && in.run(c) <= 65536.U, "FAST_PACK_RUN")
        val adjusted = Mux(in.run(c) >= 8.U, in.run(c) - 8.U, 0.U)
        val top = 16.U(5.W) - PriorityEncoder(Reverse(adjusted))
        val token = Wire(UInt(6.W))
        token := Mux(adjusted < 16.U, adjusted(4, 0), top + 12.U)
        val extraLength = Mux(adjusted < 16.U, 0.U, top)
        val extra = Mux(adjusted < 16.U, 0.U,
          adjusted ^ (1.U(17.W) << top)(16, 0))
        val rle = enabled && in.run(c) =/= 0.U
        val rleLength = code.rawLengths(0) +& code.lzLengths(token) +& extraLength
        val prefix = (masked(extra, extraLength) << code.lzLengths(token)) |
          masked(code.lzBits(token), code.lzLengths(token))
        val rleBits = (prefix << code.rawLengths(0)) | masked(code.rawBits(0), code.rawLengths(0))
        when(rle) {
          assert(code.rawLengths(0) >= 1.U && code.rawLengths(0) <= 8.U &&
            code.lzLengths(token) >= 1.U, "FAST_PACK_RLE_CODE")
          assert(rleLength <= 48.U, "FAST_PACK_RLE_CAPACITY")
        }
        segments.bits(c)(0) := Mux(rle, rleBits, 0.U)
        segments.lengths(c)(0) := Mux(rle, rleLength, 0.U)
        for (lane <- 0 until 8) {
          val active = enabled && lane.U >= in.skip(c) && lane.U < block.count
          val symbol = block.token(c)(lane)
          val length = code.rawLengths(symbol)
          val bits = (masked(block.extra(c)(lane), block.extraBits(c)(lane)) << length) |
            masked(code.rawBits(symbol), length)
          when(active) {
            assert(symbol < 19.U && length >= 1.U && length <= 8.U, "FAST_PACK_LITERAL_CODE")
          }
          segments.bits(c)(lane + 1) := Mux(active, bits, 0.U)
          segments.lengths(c)(lane + 1) := Mux(active, length +& block.extraBits(c)(lane), 0.U)
        }
      }
    }
    def concatenate(xs: Seq[(UInt, UInt)]): (UInt, UInt) = {
      if (xs.size == 1) xs.head
      else concatenate(xs.grouped(2).map { pair =>
        if (pair.size == 1) pair.head
        else (((pair(1)._1 << pair(0)._2) | pair(0)._1)(255, 0),
          (pair(0)._2 +& pair(1)._2)(8, 0))
      }.toSeq)
    }
    when(advance2 && valid1) {
      packed := segments.metadata
      for (c <- 0 until 4) {
        val joined = concatenate((0 until 9).map(i =>
          (segments.bits(c)(i).pad(256), segments.lengths(c)(i).pad(9))))
        assert(joined._2 <= 256.U, "FAST_PACK_BLOCK_CAPACITY")
        packed.bits(c) := joined._1; packed.lengths(c) := joined._2
      }
    }
  }
}
