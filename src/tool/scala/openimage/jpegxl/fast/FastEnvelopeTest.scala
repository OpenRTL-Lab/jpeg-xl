// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl.fast

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline
import _root_.circt.stage.ChiselStage

class ExternalFastEnvelope extends BlackBox {
  override def desiredName = "FastEnvelope"
  val io = IO(new Bundle {
    val clock = Input(Clock()); val rst_n = Input(Bool())
    val io = new Bundle {
      val input = Flipped(Decoupled(new FastEnvelopeInput))
      val output = Decoupled(new FastEnvelopeResult)
    }
  })
}
class FastEnvelopeTest(rows: Int, file: String, negative: Boolean) extends Module {
  val io = IO(new Bundle { val finished = Output(Bool()) })
  def table(col: Int, bits: Int): Mem[UInt] = {
    val m = Mem(rows, UInt(bits.W)); loadMemoryFromFileInline(m, file+s".column-$col.hex"); m
  }
  val layouts = table(1, 3); val widths = table(2, 9); val heights = table(3, 9)
  val sizes = table(4, 20); val descriptors = table(5, 2048); val descriptorLengths = table(6, 40)
  val goldenGlobal = table(7, 2048); val globalLengths = table(8, 12)
  val headers = table(9, 256); val headerLengths = table(10, 9)
  val dut = Module(new ExternalFastEnvelope)
  val phase = RegInit(0.U(3.W)); val cycle = RegInit(0.U(32.W)); cycle := cycle+1.U
  val si = RegInit(0.U(log2Ceil(rows+1).W)); val ri = RegInit(0.U(log2Ceil(rows+1).W))
  val offer = RegInit(false.B); val started = Reg(UInt(32.W)); val sentAt = Reg(Vec(rows, UInt(32.W)))
  val running = phase === 2.U || phase === 3.U
  val index = Mux(si < rows.U, si, 0.U)
  val in = dut.io.io.input.bits
  dut.io.clock := clock; dut.io.rst_n := !reset.asBool && phase =/= 1.U
  in.file.tag := index; in.file.channels := layouts(index); in.file.imageWidth := widths(index)
  in.file.imageHeight := heights(index); in.file.groupBytes := sizes(index)
  val hbits = descriptors(index).asTypeOf(Vec(4, UInt(512.W)))
  val hlen = descriptorLengths(index).asTypeOf(Vec(4, UInt(10.W)))
  for (c <- 0 until 4) { in.headers(c).tag := index; in.headers(c).bits := hbits(c); in.headers(c).length := hlen(c) }
  dut.io.io.input.valid := (phase === 0.U && cycle < 4.U) ||
    (running && si < rows.U && (phase === 2.U || offer))
  dut.io.io.output.ready := running && (phase === 2.U || cycle % 13.U >= 8.U)
  when(phase === 0.U && cycle === 5.U) { phase := 1.U }
  when(phase === 1.U) { assert(!dut.io.io.output.valid, "FAST_ENVELOPE_RESET_CANCEL") }
  when(phase === 1.U && cycle === 8.U) { phase := 2.U }
  when(phase === 3.U && si < rows.U && cycle % 5.U =/= 0.U) { offer := true.B }
  when(running && dut.io.io.input.fire) {
    sentAt(si) := cycle
    when(si === 0.U) { started := cycle }
    when(phase === 2.U && si =/= 0.U) { assert(cycle-started === si, "FAST_ENVELOPE_IDEAL_II_ONE") }
    si := si+1.U; offer := false.B
  }
  val stall = dut.io.io.output.valid && !dut.io.io.output.ready
  val priorStall = RegNext(stall, false.B)
  val held = RegEnable(dut.io.io.output.bits.asUInt, stall)
  when(running && priorStall) {
    assert(dut.io.io.output.valid && held === dut.io.io.output.bits.asUInt, "FAST_ENVELOPE_OUTPUT_HOLD")
  }
  when(running && dut.io.io.output.fire) {
    val out = dut.io.io.output.bits
    assert(ri < rows.U && out.dcGlobal.tag === ri && out.file.tag === ri, "FAST_ENVELOPE_TAG")
    assert(out.dcGlobal.channels === layouts(ri), "FAST_ENVELOPE_LAYOUT")
    assert(out.dcGlobal.bits === (if (negative) goldenGlobal(ri)^1.U else goldenGlobal(ri)), "FAST_ENVELOPE_OFFICIAL_GLOBAL")
    assert(out.dcGlobal.length === globalLengths(ri), "FAST_ENVELOPE_GLOBAL_LENGTH")
    assert(out.file.bits === headers(ri) && out.file.length === headerLengths(ri), "FAST_ENVELOPE_OFFICIAL_FILE")
    when(phase === 2.U) { assert(cycle-sentAt(ri) === 4.U, "FAST_ENVELOPE_IDEAL_LATENCY_FOUR") }
    printf("FAST_ENVELOPE_RESULT phase=%d index=%d global=%x glen=%d file=%x flen=%d\n",
      phase, ri, out.dcGlobal.bits, out.dcGlobal.length, out.file.bits, out.file.length)
    ri := ri+1.U
    when(ri === (rows-1).U) {
      assert(si === rows.U, "FAST_ENVELOPE_ALL_RECORDS")
      when(phase === 3.U) { printf("FAST_ENVELOPE_SUITE_PASS\n") }
      phase := phase+1.U; si := 0.U; ri := 0.U; offer := false.B
    }
  }
  assert(cycle < 200000.U, "FAST_ENVELOPE_WATCHDOG")
  io.finished := phase === 4.U
}
object EmitFastEnvelope {
  def main(args: Array[String]): Unit = {
    require(args.length == 1)
    ChiselStage.emitSystemVerilogFile(new FastEnvelope, Array("--target-dir", args(0)),
      Array("--split-verilog", "--disable-all-randomization", "--strip-debug-info"))
  }
}
object EmitFastEnvelopeTest {
  def main(args: Array[String]): Unit = {
    require(args.length == 3)
    val f = scala.io.Source.fromFile(args(1))
    val n = try f.getLines().count(_.nonEmpty) finally f.close()
    ChiselStage.emitSystemVerilogFile(new FastEnvelopeTest(n, args(1), args(2) == "negative"),
      Array("--target-dir", args(0)),
      Array("--split-verilog", "--disable-all-randomization", "--strip-debug-info"))
  }
}
