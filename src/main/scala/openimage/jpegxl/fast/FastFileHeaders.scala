// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl.fast

import chisel3._
import chisel3.util._

/** Compile-time syntax fields, not pre-encoded image data or a reference model. */
private[fast] object FastHeaderFields {
  def literal(fields: Seq[(Int, BigInt)]): (BigInt, Int) = {
    var bits = BigInt(0); var length = 0
    fields.foreach { case (n, v) =>
      require(n >= 0 && v >= 0 && v < (BigInt(1) << n))
      bits |= v << length; length += n
    }
    (bits, length)
  }
  def field(n: Int, value: Int): (Int, BigInt) = (n, BigInt(value))
  def bytePad(fields: Seq[(Int, BigInt)]): Seq[(Int, BigInt)] = {
    val n = fields.map(_._1).sum
    fields ++ (if (n % 8 == 0) Seq.empty else Seq(field(8-n % 8, 0)))
  }
}

class FastGlobalHeaderResult extends Bundle {
  val tag = UInt(32.W); val channels = UInt(3.W)
  val bits = UInt(2048.W); val length = UInt(12.W)
}
class FastGlobalHeader extends RawModule {
  val clock = IO(Input(Clock())); val rst_n = IO(Input(Bool()))
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new FastHeaderGroupResult))
    val output = Decoupled(new FastGlobalHeaderResult)
  })
  import FastHeaderFields._
  val tree = Seq(1, 2, 1, 4, 1, 0, 0, 5, 0, 0, 0, 0, 5,
    0, 0, 0, 0, 5, 0, 0, 0, 0, 5, 0, 0, 0)
  val symbolBits = Seq(0, 2, 1, 5, 3, 7); val symbolLengths = Seq(2, 2, 3, 3, 4, 4)
  val prefixFields = Seq(field(1, 1), field(1, 1), field(1, 0), field(1, 1), field(2, 0),
    field(1, 1), field(4, 0), field(6, 35), field(2, 1), field(2, 3),
    field(2, 0), field(2, 1), field(2, 2), field(2, 3), field(1, 0)) ++
    tree.map(v => field(symbolLengths(v), symbolBits(v))) ++
    Seq(field(1, 1), field(2, 0), field(4, 10), field(4, 4), field(3, 0), field(3, 0),
      field(1, 1), field(2, 3), field(3, 4), field(3, 3), field(3, 2), field(3, 1), field(3, 0),
      field(1, 1), field(4, 0)) ++ Seq.fill(4)(field(4, 0)) ++ Seq(field(5, 1)) ++
    Seq.fill(4)(Seq(field(1, 1), field(4, 8), field(8, 0))).flatten ++
    Seq(field(2, 1), field(2, 0), field(1, 1))
  val (prefixBits, prefixLength) = literal(prefixFields)
  val (graySuffix, grayLength) = literal(Seq(field(1, 1), field(1, 1), field(2, 0)))
  val (colorSuffix, colorLength) = literal(Seq(field(1, 1), field(1, 1), field(2, 1),
    field(2, 0), field(5, 0), field(2, 0)))
  require(prefixLength+4*358+colorLength <= 2048)
  withClockAndReset(clock, (!rst_n).asAsyncReset) {
    val valid1 = RegInit(false.B); val valid2 = RegInit(false.B)
    val valid3 = RegInit(false.B); val valid4 = RegInit(false.B)
    val maskedHeaders = Reg(Vec(4, UInt(512.W)))
    val headerLengths = Reg(Vec(4, UInt(10.W)))
    val maskedTag = Reg(UInt(32.W)); val maskedChannels = Reg(UInt(3.W))
    val tags = Reg(UInt(32.W)); val channels = Reg(UInt(3.W))
    val pairs = Reg(Vec(2, UInt(1024.W))); val lengths = Reg(Vec(2, UInt(11.W)))
    val joinedPayload = Reg(UInt(2048.W)); val suffixOffset = Reg(UInt(12.W))
    val totalLength = Reg(UInt(12.W)); val suffixBits = Reg(UInt(colorLength.W))
    val joinedTag = Reg(UInt(32.W)); val joinedChannels = Reg(UInt(3.W))
    val output = Reg(new FastGlobalHeaderResult)
    val advance4 = !valid4 || io.output.ready
    val advance3 = !valid3 || advance4
    val advance2 = !valid2 || advance3
    val advance1 = !valid1 || advance2
    io.input.ready := rst_n && advance1
    io.output.valid := rst_n && valid4; io.output.bits := output
    when(advance4) {
      valid4 := valid3
      when(valid3) {
        output.tag := joinedTag; output.channels := joinedChannels
        output.bits := prefixBits.U(2048.W) | (joinedPayload << prefixLength) |
          (suffixBits.pad(2048) << suffixOffset)
        output.length := totalLength
      }
    }
    when(advance3) {
      valid3 := valid2
      when(valid2) {
        val color = channels > 2.U
        val n = lengths(0)+&lengths(1)
        joinedPayload := pairs(0).pad(2048) | (pairs(1).pad(2048) << lengths(0))
        suffixOffset := n+prefixLength.U
        totalLength := n+prefixLength.U+Mux(color, colorLength.U, grayLength.U)
        suffixBits := Mux(color, colorSuffix.U, graySuffix.U)
        joinedTag := tags; joinedChannels := channels
      }
    }
    when(advance2) {
      valid2 := valid1
      when(valid1) {
        tags := maskedTag; channels := maskedChannels
        for (p <- 0 until 2) {
          pairs(p) := maskedHeaders(2*p).pad(1024) |
            (maskedHeaders(2*p+1).pad(1024) << headerLengths(2*p))
          lengths(p) := headerLengths(2*p)+&headerLengths(2*p+1)
        }
      }
    }
    when(advance1) {
      valid1 := io.input.valid
      when(io.input.valid) {
        val in = io.input.bits
        assert(in.channels >= 1.U && in.channels <= 4.U, "FAST_GLOBAL_LAYOUT")
        maskedTag := in.tag; maskedChannels := in.channels
        for (c <- 0 until 4) {
          val a = in.headers(c)
          assert(a.tag === in.tag, "FAST_GLOBAL_HEADER_TAG")
          assert(a.length <= 358.U, "FAST_GLOBAL_HEADER_CAPACITY")
          maskedHeaders(c) := a.bits & FastRetiming.mask(a.length, 512)
          headerLengths(c) := a.length
        }
      }
    }
  }
}

class FastFileHeaderInput extends Bundle {
  val tag = UInt(32.W); val imageWidth = UInt(9.W); val imageHeight = UInt(9.W)
  val channels = UInt(3.W); val groupBytes = UInt(20.W)
}
class FastFileHeaderResult extends Bundle {
  val tag = UInt(32.W); val bits = UInt(256.W); val length = UInt(9.W)
}
class FastFileHeader extends RawModule {
  val clock = IO(Input(Clock())); val rst_n = IO(Input(Bool()))
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new FastFileHeaderInput))
    val output = Decoupled(new FastFileHeaderResult)
  })
  import FastHeaderFields._
  val fixed = (1 to 4).map { ch =>
    val alpha = ch == 2 || ch == 4
    val dimension = Seq(field(16, 0x0aff), field(1, 0), field(2, 0), field(9, 0),
      field(3, 0), field(2, 0), field(9, 0))
    val metadata = Seq(field(1, 0), field(1, 0), field(1, 0), field(2, 0), field(1, 1)) ++
      (if (alpha) Seq(field(2, 1), field(1, 1)) else Seq(field(2, 0))) ++ Seq(field(1, 0)) ++
      (if (ch > 2) Seq(field(1, 1)) else Seq(field(1, 0), field(1, 0), field(2, 1),
        field(2, 1), field(1, 0), field(2, 2), field(4, 11), field(2, 1))) ++
      Seq(field(2, 0), field(1, 1))
    val frame = Seq(field(1, 0), field(2, 0), field(1, 1), field(2, 0), field(1, 0), field(2, 0)) ++
      (if (alpha) Seq(field(2, 0)) else Seq.empty) ++
      Seq(field(2, 1), field(2, 0), field(1, 0), field(2, 0)) ++
      (if (alpha) Seq(field(2, 0)) else Seq.empty) ++
      Seq(field(1, 1), field(2, 0), field(1, 0), field(1, 0), field(2, 0), field(2, 0),
        field(2, 0), field(1, 0))
    literal(bytePad(dimension++metadata)++bytePad(frame))
  }
  require(fixed.map(_._2).max+24 <= 256)
  withClockAndReset(clock, (!rst_n).asAsyncReset) {
    val valid1 = RegInit(false.B); val valid2 = RegInit(false.B)
    val headBits = Reg(UInt(256.W)); val headLength = Reg(UInt(8.W))
    val tocBits = Reg(UInt(24.W)); val tocLength = Reg(UInt(5.W)); val tag = Reg(UInt(32.W))
    val output = Reg(new FastFileHeaderResult)
    val advance2 = !valid2 || io.output.ready; val advance1 = !valid1 || advance2
    io.input.ready := rst_n && advance1; io.output.valid := rst_n && valid2; io.output.bits := output
    when(advance2) {
      valid2 := valid1
      when(valid1) {
        output.tag := tag; output.bits := headBits | (tocBits.pad(256) << headLength)
        output.length := headLength+&tocLength
      }
    }
    when(advance1) {
      valid1 := io.input.valid
      when(io.input.valid) {
        val in = io.input.bits
        assert(in.imageWidth >= 1.U && in.imageWidth <= 256.U && in.imageHeight >= 1.U && in.imageHeight <= 256.U,
          "FAST_FILE_DIMENSIONS")
        assert(in.channels >= 1.U && in.channels <= 4.U && in.groupBytes > 0.U, "FAST_FILE_FIELDS")
        val index = Wire(UInt(2.W)); index := in.channels-1.U
        val constants = VecInit(fixed.map(_._1.U(256.W)))
        val lengths = VecInit(fixed.map(_._2.U(8.W)))
        headBits := constants(index) | ((in.imageHeight-1.U).pad(256) << 19) | ((in.imageWidth-1.U).pad(256) << 33)
        headLength := lengths(index); tag := in.tag
        val bucket = Mux(in.groupBytes >= 17408.U, 2.U(2.W), Mux(in.groupBytes >= 1024.U, 1.U(2.W), 0.U(2.W)))
        val delta = in.groupBytes-Mux(bucket === 2.U, 17408.U, Mux(bucket === 1.U, 1024.U, 0.U))
        tocBits := (delta << 2) | bucket
        tocLength := Mux(bucket === 2.U, 24.U, 16.U)
      }
    }
  }
}

class FastEnvelopeInput extends Bundle {
  val file = new FastFileHeaderInput
  val headers = Vec(4, new FastHeaderResult)
}
class FastEnvelopeResult extends Bundle {
  val dcGlobal = new FastGlobalHeaderResult
  val file = new FastFileHeaderResult
}
/** Two independently generated header types, joined without software assembly. */
class FastEnvelope extends RawModule {
  val clock = IO(Input(Clock())); val rst_n = IO(Input(Bool()))
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new FastEnvelopeInput))
    val output = Decoupled(new FastEnvelopeResult)
  })
  val globalHeader = Module(new FastGlobalHeader); val file = Module(new FastFileHeader)
  globalHeader.clock := clock; globalHeader.rst_n := rst_n; file.clock := clock; file.rst_n := rst_n
  // The file branch has two stages versus four in globalHeader. Matching
  // storage prevents the shorter branch filling up and throttling ideal II=1
  // before the first joined output can be consumed.
  val fileJoin = withClockAndReset(clock, (!rst_n).asAsyncReset) {
    Module(new Queue(new FastFileHeaderResult, 2, pipe = true, flow = false))
  }
  fileJoin.io.enq <> file.io.output
  globalHeader.io.input.bits.tag := io.input.bits.file.tag
  globalHeader.io.input.bits.channels := io.input.bits.file.channels
  globalHeader.io.input.bits.headers := io.input.bits.headers
  file.io.input.bits := io.input.bits.file
  globalHeader.io.input.valid := io.input.valid && file.io.input.ready
  file.io.input.valid := io.input.valid && globalHeader.io.input.ready
  io.input.ready := globalHeader.io.input.ready && file.io.input.ready
  io.output.valid := globalHeader.io.output.valid && fileJoin.io.deq.valid
  globalHeader.io.output.ready := io.output.ready && fileJoin.io.deq.valid
  fileJoin.io.deq.ready := io.output.ready && globalHeader.io.output.valid
  io.output.bits.dcGlobal := globalHeader.io.output.bits; io.output.bits.file := fileJoin.io.deq.bits
}
