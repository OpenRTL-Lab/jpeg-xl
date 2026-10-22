// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl.fast

import chisel3._
import chisel3.util._

class FastScanCommand extends Bundle {
  val context = Bool(); val tag = UInt(32.W)
  val imageWidth = UInt(9.W); val imageHeight = UInt(9.W); val channels = UInt(3.W)
  val baseByteOffset = UInt(18.W); val sampling = Bool()
}

/** Native-local image command and raw write interface; not a DMA endpoint. */
class FastImageFront extends RawModule {
  val clock = IO(Input(Clock())); val rst_n = IO(Input(Bool()))
  val io = IO(new Bundle {
    val command = Flipped(Decoupled(new FastScanCommand))
    val write = Flipped(Decoupled(new FastPixelWrite))
    val output = Decoupled(new FastPixelFrontResult)
    val empty = Decoupled(new FastScanEmpty)
    val scanBusy = Output(Vec(2,Bool()))
  })
  val scanner = Module(new FastScanController); val pixels = Module(new FastPixelFront)
  scanner.clock := clock; scanner.rst_n := rst_n
  pixels.clock := clock; pixels.rst_n := rst_n
  scanner.io.command <> io.command; pixels.io.write <> io.write
  pixels.io.request <> scanner.io.request
  io.output <> pixels.io.output; io.empty <> scanner.io.empty; io.scanBusy := scanner.io.busy
}
class FastScanEmpty extends Bundle {
  val context = Bool(); val tag = UInt(32.W); val channels = UInt(3.W)
}
private class FastScanState extends Bundle {
  val tag = UInt(32.W); val channels = UInt(3.W); val sampling = Bool()
  val scanWidth = UInt(9.W); val stride = UInt(11.W)
  val currentLine = UInt(18.W); val topLine = UInt(18.W)
  val x = UInt(9.W); val xBytes = UInt(11.W); val rows = UInt(9.W)
  val first = Bool(); val imageFirstRow = Bool()
}
/** Two command contexts, fair coordinate issue. No reference/software work. */
class FastScanController extends RawModule {
  val clock = IO(Input(Clock())); val rst_n = IO(Input(Bool()))
  val io = IO(new Bundle {
    val command = Flipped(Decoupled(new FastScanCommand))
    val request = Decoupled(new FastWindowRequest)
    val empty = Decoupled(new FastScanEmpty)
    val busy = Output(Vec(2,Bool()))
  })
  withClockAndReset(clock,(!rst_n).asAsyncReset) {
    val state = Reg(Vec(2,new FastScanState))
    val busy = RegInit(VecInit(Seq.fill(2)(false.B)))
    val eligible = RegInit(VecInit(Seq.fill(2)(false.B)))
    val nextContext = RegInit(false.B)
    val output = Reg(new FastWindowRequest); val valid = RegInit(false.B)
    val empty = Reg(new FastScanEmpty); val emptyValid = RegInit(false.B)
    val command = io.command.bits; val context = command.context.asUInt
    val sampleRows = command.imageHeight >> 7
    val sampleWidth = Cat(command.imageWidth(8,3),0.U(3.W))
    val isEmpty = command.sampling && (sampleRows === 0.U || sampleWidth === 0.U)
    io.command.ready := rst_n && !busy(context) && (!isEmpty || !emptyValid || io.empty.ready)
    io.request.valid := rst_n && valid; io.request.bits := output
    io.empty.valid := rst_n && emptyValid; io.empty.bits := empty
    io.busy := busy
    when(io.request.fire && output.last) { busy(output.context.asUInt) := false.B }
    when(io.empty.fire) { emptyValid := false.B; busy(empty.context.asUInt) := false.B }
    when(io.command.fire) {
      assert(command.imageWidth >= 1.U && command.imageWidth <= 256.U &&
        command.imageHeight >= 1.U && command.imageHeight <= 256.U &&
        command.channels >= 1.U && command.channels <= 4.U, "FAST_SCAN_COMMAND_FIELDS")
      val stride = command.imageWidth*command.channels
      val rawBytes = FastFpgaMultiply(stride, command.imageHeight)
      assert(command.baseByteOffset +& rawBytes <= 262144.U,"FAST_SCAN_RAW_CAPACITY")
      busy(context) := true.B
      when(isEmpty) {
        eligible(context) := false.B
        empty.context := command.context; empty.tag := command.tag; empty.channels := command.channels
        emptyValid := true.B
      }.otherwise {
        val beginRow = Mux(command.sampling,((command.imageHeight-sampleRows) >> 1)+1.U,0.U)
        val previousRow = Mux(beginRow === 0.U,0.U,beginRow-1.U)
        state(context).tag := command.tag; state(context).channels := command.channels
        state(context).sampling := command.sampling; state(context).stride := stride
        state(context).scanWidth := Mux(command.sampling,sampleWidth,command.imageWidth)
        state(context).currentLine := command.baseByteOffset+FastFpgaMultiply(beginRow, stride)
        state(context).topLine := command.baseByteOffset+FastFpgaMultiply(previousRow, stride)
        state(context).x := 0.U; state(context).xBytes := 0.U
        state(context).rows := Mux(command.sampling,sampleRows,command.imageHeight)
        state(context).first := true.B; state(context).imageFirstRow := !command.sampling
        eligible(context) := true.B
      }
    }
    val preferred = nextContext.asUInt
    val selected = Mux(eligible(preferred),preferred,(!nextContext).asUInt)
    val advance = !valid || io.request.ready
    when(advance) {
      valid := eligible.asUInt.orR
      when(eligible.asUInt.orR) {
        val s = state(selected)
        val left = s.scanWidth-s.x
        val count = Mux(left >= 8.U,8.U,left)
        val endRow = left <= 8.U
        val last = endRow && s.rows === 1.U
        output.context := selected.asBool; output.tag := s.tag
        output.count := count; output.channels := s.channels
        output.currentByteOffset := s.currentLine+s.xBytes
        output.topByteOffset := s.topLine+s.xBytes
        output.rowFirst := s.x === 0.U; output.imageFirstRow := s.imageFirstRow
        output.first := s.first; output.last := last; output.sampling := s.sampling
        nextContext := !selected.asBool
        state(selected).first := false.B
        when(last) { eligible(selected) := false.B }
        .elsewhen(endRow) {
          state(selected).x := 0.U; state(selected).xBytes := 0.U
          state(selected).currentLine := s.currentLine+s.stride
          state(selected).topLine := s.currentLine
          state(selected).rows := s.rows-1.U; state(selected).imageFirstRow := false.B
        }.otherwise {
          state(selected).x := s.x+count
          state(selected).xBytes := s.xBytes+count*s.channels
        }
      }
    }
  }
}
