// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl.fast

import chisel3._
import chisel3.util._

class FastCacheRead extends Bundle {
  val context = Bool(); val wordIndices = Vec(4,UInt(13.W))
}
class FastCacheWindow extends Bundle {
  val context = Bool(); val tag = UInt(32.W); val channels = UInt(3.W)
  val wordIndices = Vec(4,UInt(13.W)); val totalBits = Vec(4,UInt(24.W))
  val windows = Vec(4,UInt(768.W))
}
private class FastCachePending extends Bundle {
  val command = new FastCacheRead
  val tag = UInt(32.W); val channels = UInt(3.W); val totalBits = Vec(4,UInt(24.W))
  val take = Vec(4,UInt(10.W))
}
/** 2 MiB, sixteen true 1R1W banks. Three adjacent words/channel per read. */
class FastChannelCache extends RawModule {
  val clock = IO(Input(Clock())); val rst_n = IO(Input(Bool()))
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new FastPoolWord))
    val read = Flipped(Decoupled(new FastCacheRead))
    val output = Decoupled(new FastCacheWindow)
    val release = Flipped(Valid(Bool()))
    val sealedContexts = Output(Vec(2,Bool()))
  })
  withClockAndReset(clock,(!rst_n).asAsyncReset) {
    val banks = Seq.fill(4)(Seq.fill(4)(FastFpgaMemory(256,4096,true,clock)))
    val owned = RegInit(VecInit(Seq.fill(2)(false.B)))
    val sealedContexts = RegInit(VecInit(Seq.fill(2)(false.B)))
    val tags = Reg(Vec(2,UInt(32.W))); val channels = Reg(Vec(2,UInt(3.W)))
    val ends = Reg(Vec(2,Vec(4,UInt(24.W))))
    io.input.ready := rst_n; io.sealedContexts := sealedContexts
    when(io.input.fire) {
      val in = io.input.bits; val ctx = in.context.asUInt
      assert(in.channels>=1.U && in.channels<=4.U,"FAST_CACHE_LAYOUT")
      when(in.first) {
        assert(!owned(ctx),"FAST_CACHE_CONTEXT_OWNED")
        owned(ctx) := true.B; tags(ctx) := in.tag; channels(ctx) := in.channels
      }.otherwise {
        assert(owned(ctx) && !sealedContexts(ctx) && tags(ctx)===in.tag && channels(ctx)===in.channels,
          "FAST_CACHE_WRITE_IDENTITY")
      }
      for (c <- 0 until 4) {
        val before = Mux(in.first,0.U,ends(ctx)(c))
        val next = before+&in.lengths(c)
        assert(in.offsets(c)===before && in.offsets(c)(7,0)===0.U,"FAST_CACHE_WRITE_OFFSET")
        assert(in.lengths(c)<=256.U && (in.last || in.lengths(c)===0.U || in.lengths(c)===256.U),
          "FAST_CACHE_WRITE_LENGTH")
        assert(next<=2097152.U && in.totalBits(c)<=2097152.U && next<=in.totalBits(c),"FAST_CACHE_CAPACITY")
        when(c.U>=in.channels) {
          assert(in.lengths(c)===0.U && in.totalBits(c)===0.U,"FAST_CACHE_INACTIVE_CHANNEL")
        }
        when(in.last) { assert(next===in.totalBits(c),"FAST_CACHE_FINAL_SIZE") }
        ends(ctx)(c) := next
        val word = in.offsets(c)(20,8)
        when(in.lengths(c)=/=0.U) {
          assert(before<2097152.U,"FAST_CACHE_NONEMPTY_ADDRESS")
          for (bank <- 0 until 4) when(word(1,0)===bank.U) {
            banks(c)(bank).io.wen := true.B
            banks(c)(bank).io.waddr := Cat(in.context,word(12,2))
            banks(c)(bank).io.wdata := in.bits(c)
          }
        }
      }
      sealedContexts(ctx) := in.last
    }
    val pending = Reg(new FastCachePending); val readValid = RegInit(false.B)
    val queuedRead = Reg(new FastCachePending); val queuedValid = RegInit(false.B)
    val output = Reg(new FastCacheWindow); val valid = RegInit(false.B)
    val advance = !valid || io.output.ready; val readAdvance = !readValid || advance
    val issueRead = rst_n && queuedValid && readAdvance
    val queueAdvance = !queuedValid || issueRead
    io.read.ready := rst_n && queueAdvance && sealedContexts(io.read.bits.context.asUInt)
    when(queueAdvance) {
      queuedValid := io.read.fire
      when(io.read.fire) {
        val ctx = io.read.bits.context.asUInt
        queuedRead.command := io.read.bits; queuedRead.tag := tags(ctx)
        queuedRead.channels := channels(ctx); queuedRead.totalBits := ends(ctx)
        queuedRead.take := 0.U.asTypeOf(queuedRead.take)
      }
    }
    io.output.valid := rst_n && valid; io.output.bits := output
    val reads = (0 until 4).map { c => (0 until 4).map { bank =>
      val first = queuedRead.command.wordIndices(c)
      val words = (0 until 3).map(i => first+&i.U(2.W))
      val selects = words.map(w => w<8192.U && w(1,0)===bank.U)
      val word = MuxCase(0.U(14.W),selects.zip(words))
      banks(c)(bank).io.raddr := Cat(queuedRead.command.context,word(12,2))
      banks(c)(bank).io.ren := issueRead && selects.reduce(_ || _)
      banks(c)(bank).io.rdata
    }}
    val heldRead = Reg(Vec(4,Vec(4,UInt(256.W)))); val holding = RegInit(false.B)
    when(readValid && !advance && !holding) {
      heldRead := VecInit(reads.map(row => VecInit(row))); holding := true.B
    }
    when(advance) { holding := false.B }
    when(issueRead) {
      pending := queuedRead
      for (c <- 0 until 4) {
        val firstBit = Cat(queuedRead.command.wordIndices(c),0.U(8.W))
        val remaining = Mux(queuedRead.totalBits(c)>firstBit,
          queuedRead.totalBits(c)-firstBit,0.U)
        pending.take(c) := Mux(remaining>=768.U,768.U,remaining)
      }
    }
    when(readAdvance) { readValid := issueRead }
    when(advance) {
      valid := readValid
      when(readValid) {
        output.context := pending.command.context; output.tag := pending.tag
        output.channels := pending.channels; output.totalBits := pending.totalBits
        output.wordIndices := pending.command.wordIndices
        for (c <- 0 until 4) {
          val response = VecInit((0 until 4).map(b => Mux(holding,heldRead(c)(b),reads(c)(b))))
          val words = (0 until 3).map(i => pending.command.wordIndices(c)+&i.U(2.W))
          val parts = words.map(w => Mux(w<8192.U,response(w(1,0)),0.U(256.W)))
          val mask = FastRetiming.mask(pending.take(c),768)
          output.windows(c) := Cat(parts.reverse)&mask
        }
      }
    }
    when(io.release.valid) {
      val ctx = io.release.bits.asUInt
      assert(owned(ctx) && sealedContexts(ctx),"FAST_CACHE_RELEASE_OWNERSHIP")
      assert(!(readValid && pending.command.context===io.release.bits) &&
        !(queuedValid && queuedRead.command.context===io.release.bits) &&
        !(valid && output.context===io.release.bits) &&
        !(io.read.fire && io.read.bits.context===io.release.bits),"FAST_CACHE_RELEASE_PENDING")
      assert(!(io.input.fire && io.input.bits.context===io.release.bits),"FAST_CACHE_RELEASE_WRITE")
      owned(ctx) := false.B; sealedContexts(ctx) := false.B
    }
  }
}
