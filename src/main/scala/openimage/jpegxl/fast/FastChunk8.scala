// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl.fast

import chisel3._
import chisel3.util._

/** The frozen non-AVX official reference uses eight-sample chunks.
  * Planes: current, left, top, top-left. Each lane has interleaved G/GA/RGB/RGBA.
  * Gathering neighboring raw pixels and carrying runs belong to other modules.
  */
class FastChunkInput extends Bundle {
  val pixels = Vec(4, Vec(8, Vec(4, UInt(8.W))))
  val count = UInt(4.W)
  val channels = UInt(3.W)
  val tag = UInt(32.W)
}

class FastChunkOutput extends Bundle {
  val residual = Vec(4, Vec(8, UInt(16.W)))
  val token = Vec(4, Vec(8, UInt(5.W)))
  val extraBits = Vec(4, Vec(8, UInt(4.W)))
  val extra = Vec(4, Vec(8, UInt(16.W)))
  val zeroPrefix = Vec(4, UInt(4.W))
  val count = UInt(4.W)
  val channels = UInt(3.W)
  val tag = UInt(32.W)
}

class FastChunkIO extends Bundle {
  val input = Flipped(Decoupled(new FastChunkInput))
  val output = Decoupled(new FastChunkOutput)
}

private class ColorBlock extends Bundle {
  val planes = Vec(4, Vec(4, Vec(8, SInt(16.W))))
  val count = UInt(4.W)
  val channels = UInt(3.W)
  val tag = UInt(32.W)
}

private class ResidualBlock extends Bundle {
  val residual = Vec(4, Vec(8, UInt(16.W)))
  val count = UInt(4.W)
  val channels = UInt(3.W)
  val tag = UInt(32.W)
}

/** Four channels x eight pixels, three elastic stages, target II=1.
  * This is a codec component, NOT a complete image encoder or subsystem.
  * No reference bytes, software calls, clocks or platform primitives inside.
  */
class FastChunk8 extends RawModule {
  val clock = IO(Input(Clock()))
  val rst_n = IO(Input(Bool()))
  val io = IO(new FastChunkIO)

  withClockAndReset(clock, (!rst_n).asAsyncReset) {
    val color = Reg(new ColorBlock)
    val residual = Reg(new ResidualBlock)
    val result = Reg(new FastChunkOutput)
    val cv = RegInit(false.B)
    val rv = RegInit(false.B)
    val ov = RegInit(false.B)
    val outputAdvance = !ov || io.output.ready
    val residualAdvance = !rv || outputAdvance
    val colorAdvance = !cv || residualAdvance
    io.input.ready := colorAdvance && rst_n
    io.output.valid := ov && rst_n
    io.output.bits := result

    def word16(x: SInt): SInt = x.asUInt(15, 0).asSInt
    when(io.input.fire) {
      assert(io.input.bits.count >= 1.U && io.input.bits.count <= 8.U,
        "FAST_CHUNK_COUNT_CONTRACT")
      assert(io.input.bits.channels >= 1.U && io.input.bits.channels <= 4.U,
        "FAST_CHUNK_CHANNEL_CONTRACT")
      color.count := io.input.bits.count
      color.channels := io.input.bits.channels
      color.tag := io.input.bits.tag
      for (p <- 0 until 4; lane <- 0 until 8) {
        val in = io.input.bits.pixels(p)(lane)
        val r = in(0).zext.pad(16)
        val g = in(1).zext.pad(16)
        val b = in(2).zext.pad(16)
        val a = in(3).zext.pad(16)
        val co = word16(r - b)
        val middle = word16(b + (co >> 1))
        val cg = word16(g - middle)
        val y = word16(middle + (cg >> 1))
        val rgb = io.input.bits.channels >= 3.U
        color.planes(p)(0)(lane) := Mux(rgb, y, r)
        color.planes(p)(1)(lane) := Mux(rgb, co, Mux(io.input.bits.channels === 2.U, g, 0.S))
        color.planes(p)(2)(lane) := Mux(rgb, cg, 0.S)
        color.planes(p)(3)(lane) := Mux(io.input.bits.channels === 4.U, a, 0.S)
      }
    }
    when(colorAdvance) { cv := io.input.valid && rst_n }

    when(residualAdvance) {
      rv := cv
      when(cv) {
        residual.tag := color.tag
        residual.count := color.count
        residual.channels := color.channels
        for (c <- 0 until 4; lane <- 0 until 8) {
          val px = color.planes(0)(c)(lane)
          val left = color.planes(1)(c)(lane)
          val top = color.planes(2)(c)(lane)
          val tl = color.planes(3)(c)(lane)
          val ac = word16(left - tl)
          val ab = word16(left - top)
          val bc = word16(top - tl)
          val gradient = word16(ac + top)
          val differentSide = (ab.asUInt ^ bc.asUInt)(15)
          val outside = (ac.asUInt ^ bc.asUInt)(15)
          val clamp = Mux(differentSide, top, left)
          val prediction = Mux(outside, gradient, clamp)
          val delta = px -& prediction
          val packed = Mux(delta < 0.S, ((-delta).asUInt << 1) - 1.U,
            delta.asUInt << 1)
          residual.residual(c)(lane) := Mux(c.U < color.channels && lane.U < color.count,
            packed(15, 0), 0.U)
        }
      }
    }
    when(outputAdvance) {
      ov := rv
      when(rv) {
        result.tag := residual.tag
        result.count := residual.count
        result.channels := residual.channels
        result.residual := residual.residual
        for (c <- 0 until 4) {
          val isZero = (0 until 8).map(lane => residual.residual(c)(lane) === 0.U)
          result.zeroPrefix(c) := Mux(c.U < residual.channels,
            MuxCase(residual.count, (0 until 8).map(lane =>
              (lane.U < residual.count && !isZero(lane)) -> lane.U(4.W))), 0.U)
          for (lane <- 0 until 8) {
            val r = residual.residual(c)(lane)
            val high = 15.U(4.W) - PriorityEncoder(Reverse(r))
            result.token(c)(lane) := Mux(r === 0.U, 0.U, high +& 1.U)
            result.extraBits(c)(lane) := Mux(r === 0.U, 0.U, high)
            result.extra(c)(lane) := Mux(r === 0.U, 0.U, r - (1.U(16.W) << high))
          }
        }
      }
    }
  }
}
