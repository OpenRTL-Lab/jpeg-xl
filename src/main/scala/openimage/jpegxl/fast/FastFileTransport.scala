// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl.fast

import chisel3._
import chisel3.util._

class FastFileCommand extends Bundle {
  val context = Bool(); val imageWidth = UInt(9.W); val imageHeight = UInt(9.W)
  val outputLimit = UInt(24.W)
}
class FastFileWord extends Bundle {
  val context = Bool(); val tag = UInt(32.W)
  val bits = UInt(512.W); val byteOffset = UInt(24.W); val bytes = UInt(7.W)
  val totalBytes = UInt(24.W); val first = Bool(); val last = Bool()
}
private class FastFileSlice extends Bundle {
  val offset = UInt(26.W); val first = Bool(); val last = Bool(); val bytes = UInt(7.W)
  val source = Vec(4,UInt(8.W)); val destination = Vec(4,UInt(10.W))
  val take = Vec(4,UInt(10.W))
}
private class FastFileOrigins extends Bundle {
  val offset = UInt(26.W); val first = Bool(); val last = Bool(); val bytes = UInt(7.W)
  val begin = Vec(4,UInt(26.W)); val destination = Vec(4,UInt(26.W))
  val clippedStart = Vec(4,UInt(26.W)); val overlap = Vec(4,Bool())
}
/** Exact channel-bit concatenation with a hardware-derived file header.
  * No DMA completion semantics: the last stream handshake retires this job.
  */
class FastFileTransport extends RawModule {
  val clock = IO(Input(Clock())); val rst_n = IO(Input(Bool()))
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new FastPoolWord))
    val command = Flipped(Decoupled(new FastFileCommand))
    val output = Decoupled(new FastFileWord)
    val sealedContexts = Output(Vec(2,Bool()))
  })
  val cache = Module(new FastChannelCache); val header = Module(new FastFileHeader)
  cache.clock := clock; cache.rst_n := rst_n
  header.clock := clock; header.rst_n := rst_n
  cache.io.input <> io.input; io.sealedContexts := cache.io.sealedContexts
  withClockAndReset(clock,(!rst_n).asAsyncReset) {
    val totals = Reg(Vec(2,Vec(4,UInt(24.W))))
    val tags = Reg(Vec(2,UInt(32.W))); val layouts = Reg(Vec(2,UInt(3.W)))
    when(io.input.fire && io.input.bits.last) {
      val ctx = io.input.bits.context.asUInt
      totals(ctx) := io.input.bits.totalBits
      tags(ctx) := io.input.bits.tag; layouts(ctx) := io.input.bits.channels
    }
    val busy = RegInit(false.B); val readyFile = RegInit(false.B)
    val context = Reg(Bool()); val tag = Reg(UInt(32.W)); val channels = Reg(UInt(3.W))
    val channelBits = Reg(Vec(4,UInt(24.W)))
    val prefix = Reg(Vec(4,UInt(26.W))); val ends = Reg(Vec(4,UInt(26.W)))
    val groupBytes = Reg(UInt(24.W)); val limit = Reg(UInt(24.W))
    val fileBits = Reg(UInt(26.W)); val fileBytes = Reg(UInt(24.W))
    val head = Reg(UInt(256.W)); val cursor = Reg(UInt(26.W))
    val issuing = RegInit(false.B)
    val cmd = io.command.bits; val ctx = cmd.context.asUInt
    val sum = totals(ctx).map(_.pad(26)).reduce(_+_)
    val rounded = (sum+7.U)>>3
    header.io.input.valid := io.command.valid && !busy && cache.io.sealedContexts(ctx)
    header.io.input.bits.tag := tags(ctx)
    header.io.input.bits.channels := layouts(ctx)
    header.io.input.bits.imageWidth := cmd.imageWidth
    header.io.input.bits.imageHeight := cmd.imageHeight
    header.io.input.bits.groupBytes := rounded
    io.command.ready := rst_n && !busy && cache.io.sealedContexts(ctx) && header.io.input.ready
    when(io.command.fire) {
      assert(rounded>0.U && rounded<1048576.U,"FAST_FILE_TRANSPORT_GROUP_CAPACITY")
      context := cmd.context; tag := tags(ctx); channels := layouts(ctx)
      channelBits := totals(ctx); groupBytes := rounded; limit := cmd.outputLimit
      busy := true.B; readyFile := false.B
    }
    header.io.output.ready := busy && !readyFile
    when(header.io.output.fire) {
      val h = header.io.output.bits
      assert(h.tag===tag && h.length(2,0)===0.U,"FAST_FILE_TRANSPORT_HEADER")
      val n = groupBytes+(h.length>>3)
      assert(n<=limit,"FAST_FILE_TRANSPORT_OUTPUT_LIMIT")
      fileBytes := n; fileBits := n<<3
      head := h.bits & FastRetiming.mask(h.length,256)
      for (c <- 0 until 4) {
        val start = h.length.pad(26)+(if (c==0) 0.U else channelBits.take(c).map(_.pad(26)).reduce(_+_))
        prefix(c) := start; ends(c) := start+channelBits(c)
      }
      cursor := 0.U; readyFile := true.B; issuing := true.B
    }
    val slices = Module(new Queue(new FastFileSlice,4))
    val origins = Reg(new FastFileOrigins); val originsValid = RegInit(false.B)
    val issueSlice = originsValid && cache.io.read.ready && slices.io.enq.ready
    val originsAdvance = !originsValid || issueSlice
    when(originsAdvance) {
      originsValid := readyFile && issuing
      when(readyFile && issuing) {
        origins.offset := cursor; origins.first := cursor===0.U
        val last = cursor+512.U>=fileBits
        origins.last := last; origins.bytes := Mux(last,(fileBits-cursor)>>3,64.U)
        for (c <- 0 until 4) {
          origins.begin(c) := Mux(cursor>prefix(c),cursor-prefix(c),0.U)
          origins.destination(c) := Mux(prefix(c)>cursor,prefix(c)-cursor,0.U)
          origins.clippedStart(c) := Mux(cursor>prefix(c),cursor,prefix(c))
          origins.overlap(c) := ends(c)>cursor && prefix(c)<cursor+512.U && c.U<channels
        }
        cursor := cursor+512.U
        when(last) { issuing := false.B }
      }
    }
    val slice = Wire(new FastFileSlice)
    slice.offset := origins.offset; slice.first := origins.first
    slice.last := origins.last; slice.bytes := origins.bytes
    cache.io.read.bits.context := context
    for (c <- 0 until 4) {
      val begin = origins.begin(c); val destination = origins.destination(c)
      val overlap = origins.overlap(c)
      val available = Mux(overlap,ends(c)-origins.clippedStart(c),0.U)
      val room = Mux(destination<512.U,512.U-destination,0.U)
      val take = Mux(available<room,available,room)
      slice.source(c) := Mux(overlap,begin(7,0),0.U)
      slice.destination(c) := Mux(overlap,destination,0.U)
      slice.take(c) := take
      val nonempty = overlap && ends(c)>origins.clippedStart(c) && destination<512.U
      cache.io.read.bits.wordIndices(c) := Mux(nonempty,begin(20,8),0.U)
    }
    slices.io.enq.bits := slice
    cache.io.read.valid := rst_n && originsValid && slices.io.enq.ready
    slices.io.enq.valid := rst_n && originsValid && cache.io.read.ready
    val output = Reg(new FastFileWord); val valid = RegInit(false.B)
    val maskedFragments = Reg(Vec(4,UInt(512.W)))
    val fragmentSlice = Reg(new FastFileSlice); val fragmentValid = RegInit(false.B)
    val advance = !valid || io.output.ready
    val fragmentAdvance = !fragmentValid || advance
    cache.io.output.ready := fragmentAdvance && slices.io.deq.valid
    slices.io.deq.ready := fragmentAdvance && cache.io.output.valid
    when(advance) {
      valid := fragmentValid
      when(fragmentValid) {
        val part = fragmentSlice
        val fragments = (0 until 4).map(c => (maskedFragments(c)<<part.destination(c))(511,0))
        output.bits := fragments.reduce(_|_) | Mux(part.first,head.pad(512),0.U)
        output.context := context; output.tag := tag
        output.byteOffset := part.offset>>3; output.bytes := part.bytes
        output.totalBytes := fileBytes; output.first := part.first; output.last := part.last
      }
    }
    when(fragmentAdvance) {
      fragmentValid := cache.io.output.valid && slices.io.deq.valid
      when(cache.io.output.valid && slices.io.deq.valid) {
        val part = slices.io.deq.bits; val data = cache.io.output.bits
        assert(data.context===context && data.tag===tag && data.channels===channels,
          "FAST_FILE_TRANSPORT_CACHE_IDENTITY")
        fragmentSlice := part
        for (c <- 0 until 4) {
          val mask = FastRetiming.mask(part.take(c),512)
          maskedFragments(c) := (data.windows(c)>>part.source(c))(511,0)&mask
        }
      }
    }
    io.output.valid := rst_n && valid; io.output.bits := output
    cache.io.release.valid := io.output.fire && output.last
    cache.io.release.bits := context
    when(cache.io.release.valid) {
      busy := false.B; readyFile := false.B
      assert(!issuing && !originsValid && !slices.io.deq.valid && !fragmentValid,
        "FAST_FILE_TRANSPORT_DRAINED")
    }
  }
}
