// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl.fast

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline
import _root_.circt.stage.ChiselStage

class ExternalFastChunk8 extends BlackBox {
  override def desiredName = "FastChunk8"
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val rst_n = Input(Bool())
    val io = new FastChunkIO
  })
}

class FastChunkTest(rows: Seq[Array[String]], negative: Boolean, vectorPath: String) extends Module {
  val io = IO(new Bundle { val finished = Output(Bool()) })
  require(rows.nonEmpty)
  private val width = log2Ceil(rows.size + 1)
  def table(column: Int, bits: Int, radix: Int = 16): Mem[UInt] = {
    val rom = Mem(rows.size, UInt(bits.W))
    loadMemoryFromFileInline(rom, vectorPath + ".column-" + column + ".hex")
    rom
  }
  val tags = table(0, 32, 10)
  val channels = table(1, 3, 10)
  val counts = table(2, 4, 10)
  val pixels = table(3, 1024)
  val expectedResidual = table(4, 512)
  val expectedToken = table(5, 160)
  val expectedBits = table(6, 128)
  val expectedExtra = table(7, 512)
  val expectedPrefix = table(8, 16)
  val dut = Module(new ExternalFastChunk8)
  val cycle = RegInit(0.U(32.W)); cycle := cycle + 1.U
  val phase = RegInit(0.U(3.W)) // warmup stalled, reset, ideal, backpressure, finished
  val sent = RegInit(0.U(width.W))
  val received = RegInit(0.U(width.W))
  val firstSent = Reg(UInt(32.W))
  val lastReceived = Reg(UInt(32.W))
  val firstLatency = Reg(UInt(32.W))
  val offered = RegInit(false.B)
  dut.io.clock := clock
  dut.io.rst_n := !reset.asBool && phase =/= 1.U
  val running = phase === 2.U || phase === 3.U
  val sourceIndex = Mux(phase === 0.U, 0.U, Mux(sent < rows.size.U, sent, 0.U))
  dut.io.io.input.valid := phase === 0.U ||
    (running && sent < rows.size.U && (phase === 2.U || offered))
  dut.io.io.input.bits.tag := tags(sourceIndex)
  dut.io.io.input.bits.channels := channels(sourceIndex)
  dut.io.io.input.bits.count := counts(sourceIndex)
  dut.io.io.input.bits.pixels := pixels(sourceIndex).asTypeOf(dut.io.io.input.bits.pixels)
  dut.io.io.output.ready := running && (phase === 2.U || (cycle % 5.U >= 2.U))
  when(phase === 0.U && cycle === 10.U) { phase := 1.U }
  when(phase === 1.U && cycle === 13.U) { phase := 2.U }
  when(phase === 1.U) {
    assert(!dut.io.io.output.valid, "FAST_CHUNK_RESET_CANCEL")
  }
  when(phase === 3.U && !offered && sent < rows.size.U && cycle % 7.U =/= 3.U) {
    offered := true.B
  }
  when(running && dut.io.io.input.fire) {
    when(sent === 0.U) { firstSent := cycle }
    sent := sent + 1.U
    offered := false.B
  }
  val stalled = dut.io.io.output.valid && !dut.io.io.output.ready
  val priorStall = RegNext(stalled, false.B)
  val priorOutput = RegEnable(dut.io.io.output.bits.asUInt, stalled)
  when(priorStall && running) {
    assert(dut.io.io.output.valid && dut.io.io.output.bits.asUInt === priorOutput,
      "FAST_CHUNK_OUTPUT_HOLD")
  }
  when(running && dut.io.io.output.fire) {
    assert(received < rows.size.U, "FAST_CHUNK_NO_EXTRA_OUTPUT")
    val output = dut.io.io.output.bits
    assert(output.tag === tags(received), "FAST_CHUNK_TAG")
    assert(output.channels === channels(received) && output.count === counts(received),
      "FAST_CHUNK_METADATA")
    val expected = if (negative) expectedResidual(received) ^ 1.U else expectedResidual(received)
    assert(output.residual.asUInt === expected, "FAST_CHUNK_OFFICIAL_RESIDUAL")
    assert(output.extra.asUInt === expectedExtra(received), "FAST_CHUNK_OFFICIAL_EXTRA")
    for (c <- 0 until 4) {
      assert(output.zeroPrefix(c) === expectedPrefix(received)(c * 4 + 3, c * 4),
        "FAST_CHUNK_OFFICIAL_ZERO_PREFIX")
      for (lane <- 0 until 8) {
        val index = c * 8 + lane
        assert(output.token(c)(lane) === expectedToken(received)(index * 5 + 4, index * 5),
          "FAST_CHUNK_OFFICIAL_TOKEN")
        assert(output.extraBits(c)(lane) === expectedBits(received)(index * 4 + 3, index * 4),
          "FAST_CHUNK_OFFICIAL_EXTRA_BITS")
      }
    }
    when(received === 0.U) {
      firstLatency := cycle - firstSent
      when(phase === 2.U) { assert(cycle - firstSent === 3.U, "FAST_CHUNK_IDEAL_LATENCY") }
    }.elsewhen(phase === 2.U) {
      assert(cycle - lastReceived === 1.U, "FAST_CHUNK_IDEAL_II")
    }
    lastReceived := cycle
    printf("FAST_CHUNK_OUT phase=%d index=%d tag=%x residual=%x extra=%x\n",
      phase, received, output.tag, output.residual.asUInt, output.extra.asUInt)
    received := received + 1.U
    when(received === (rows.size - 1).U) {
      assert(sent === rows.size.U, "FAST_CHUNK_ALL_INPUTS_ACCEPTED")
      printf("FAST_CHUNK_PHASE_PASS phase=%d blocks=%d first_latency=%d elapsed=%d\n",
        phase, rows.size.U, firstLatency, cycle - firstSent + 1.U)
      when(phase === 3.U) {
        printf("FAST_CHUNK_SUITE_PASS official_cases=%d phases=2 reset_cancel=1\n", rows.size.U)
      }
      phase := phase + 1.U
      sent := 0.U; received := 0.U; offered := false.B
    }
  }
  assert(cycle < 20000.U, "FAST_CHUNK_HARDWARE_WATCHDOG")
  io.finished := phase === 4.U
}

object EmitFastChunk {
  def main(args: Array[String]): Unit = {
    require(args.length == 1)
    ChiselStage.emitSystemVerilogFile(new FastChunk8, Array("--target-dir", args(0)),
      Array("--split-verilog", "--disable-all-randomization", "--strip-debug-info"))
  }
}

object EmitFastChunkTest {
  def main(args: Array[String]): Unit = {
    require(args.length == 3)
    val source = scala.io.Source.fromFile(args(1))
    val rows = try source.getLines().filter(_.nonEmpty).map(_.split("\t")).toVector finally source.close()
    ChiselStage.emitSystemVerilogFile(new FastChunkTest(rows, args(2) == "negative", args(1)),
      Array("--target-dir", args(0)),
      Array("--split-verilog", "--disable-all-randomization", "--strip-debug-info"))
  }
}
