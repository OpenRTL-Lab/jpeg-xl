// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl.fast

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline
import _root_.circt.stage.ChiselStage

class ExternalFastPixelFront extends BlackBox {
  override def desiredName = "FastPixelFront"
  val io = IO(new Bundle {
    val clock = Input(Clock()); val rst_n = Input(Bool())
    val io = new Bundle {
      val write = Flipped(Decoupled(new FastPixelWrite))
      val request = Flipped(Decoupled(new FastWindowRequest))
      val output = Decoupled(new FastPixelFrontResult)
    }
  })
}
class FastWindowTest(rows: Int, loads: Int, path: String, negative: Boolean) extends Module {
  val io = IO(new Bundle { val finished = Output(Bool()) })
  def table(n: Int, bits: Int, load: Boolean = false): Mem[UInt] = {
    val m = Mem(if (load) loads else rows, UInt(bits.W))
    loadMemoryFromFileInline(m, path+(if (load) ".loads.tsv" else "")+s".column-$n.hex"); m
  }
  val batches = table(1,6); val contexts = table(2,1); val tags = table(3,32)
  val layouts = table(4,3); val counts = table(5,4); val flags = table(6,5)
  val current = table(7,18); val top = table(8,18)
  val residual = table(9,512); val tokens = table(10,160); val extraBits = table(11,128)
  val extras = table(12,512); val prefixes = table(13,16)
  val loadBatch = table(0,6,true); val loadContext = table(1,1,true)
  val loadWord = table(2,12,true); val loadData = table(3,512,true)
  val dut = Module(new ExternalFastPixelFront)
  val phase = RegInit(0.U(3.W)) // warm loaded, active reset, ideal, stalls, done
  val cycle = RegInit(0.U(32.W)); cycle := cycle+1.U
  val resetCycles = RegInit(0.U(4.W)); val warmCycles = RegInit(0.U(4.W))
  val batch = RegInit(0.U(6.W)); val loading = RegInit(true.B)
  val si = RegInit(0.U(log2Ceil(rows+1).W)); val ri = RegInit(0.U(log2Ceil(rows+1).W))
  val li = RegInit(0.U(log2Ceil(loads+1).W)); val offer = RegInit(false.B)
  val sentAt = Mem(rows, UInt(32.W)); val lastSent = Reg(UInt(32.W)); val lastReceived = Reg(UInt(32.W))
  val running = phase === 2.U || phase === 3.U
  val source = Mux(phase === 0.U || si >= rows.U, 0.U, si)
  val loadIndex = Mux(li < loads.U, li, 0.U)
  dut.io.clock := clock; dut.io.rst_n := !reset.asBool && phase =/= 1.U
  dut.io.io.write.valid := (phase === 0.U || running) && loading && li < loads.U && loadBatch(loadIndex) === batch
  dut.io.io.write.bits.context := loadContext(loadIndex).asBool
  dut.io.io.write.bits.wordIndex := loadWord(loadIndex); dut.io.io.write.bits.data := loadData(loadIndex)
  when(dut.io.io.write.fire) { li := li+1.U }
  when(loading && (li === loads.U || loadBatch(loadIndex) =/= batch)) { loading := false.B }
  val request = dut.io.io.request.bits
  request.context := contexts(source).asBool; request.tag := tags(source)
  request.channels := layouts(source); request.count := counts(source)
  request.currentByteOffset := current(source); request.topByteOffset := top(source)
  request.rowFirst := flags(source)(0); request.imageFirstRow := flags(source)(1)
  request.first := flags(source)(2); request.last := flags(source)(3); request.sampling := flags(source)(4)
  dut.io.io.request.valid := !loading && ((phase === 0.U) ||
    (running && si < rows.U && batches(source) === batch && (phase === 2.U || offer)))
  dut.io.io.output.ready := running && (phase === 2.U || cycle % 23.U >= 16.U)
  when(phase === 0.U && !loading) {
    warmCycles := warmCycles+1.U
    when(warmCycles === 10.U) { phase := 1.U }
  }
  when(phase === 1.U) {
    assert(!dut.io.io.output.valid && !dut.io.io.request.ready, "FAST_WINDOW_RESET_CANCEL")
    resetCycles := resetCycles+1.U
    when(resetCycles === 2.U) {
      phase := 2.U; loading := true.B; li := 0.U; si := 0.U; ri := 0.U; batch := 0.U
    }
  }
  when(phase === 3.U && si < rows.U && cycle % 7.U =/= 0.U) { offer := true.B }
  when(running && dut.io.io.request.fire) {
    sentAt.write(si,cycle)
    when(phase === 2.U && si =/= 0.U && batches(si-1.U) === batch) {
      assert(cycle-lastSent === 1.U, "FAST_WINDOW_IDEAL_II_ONE")
    }
    lastSent := cycle; si := si+1.U; offer := false.B
  }
  val stalled = dut.io.io.output.valid && !dut.io.io.output.ready
  val priorStall = RegNext(stalled,false.B); val held = RegEnable(dut.io.io.output.bits.asUInt,stalled)
  when(running && priorStall) {
    assert(dut.io.io.output.valid && held === dut.io.io.output.bits.asUInt, "FAST_WINDOW_OUTPUT_HOLD")
  }
  when(running && dut.io.io.output.fire) {
    val out = dut.io.io.output.bits; val block = out.block; val meta = out.metadata
    assert(ri < rows.U && block.tag === tags(ri), "FAST_WINDOW_TAG")
    assert(block.channels === layouts(ri) && block.count === counts(ri), "FAST_WINDOW_LAYOUT")
    assert(meta.context === contexts(ri).asBool && meta.first === flags(ri)(2) &&
      meta.last === flags(ri)(3) && meta.sampling === flags(ri)(4), "FAST_WINDOW_METADATA")
    assert(block.residual.asUInt === (if (negative) residual(ri)^1.U else residual(ri)),
      "FAST_WINDOW_OFFICIAL_RESIDUAL")
    assert(block.token.asUInt === tokens(ri) && block.extraBits.asUInt === extraBits(ri) &&
      block.extra.asUInt === extras(ri) && block.zeroPrefix.asUInt === prefixes(ri), "FAST_WINDOW_OFFICIAL_SYMBOLS")
    when(phase === 2.U) {
      assert(cycle-sentAt(ri) === 5.U, "FAST_WINDOW_IDEAL_LATENCY_FIVE")
      when(ri =/= 0.U && batches(ri-1.U) === batch) {
        assert(cycle-lastReceived === 1.U, "FAST_WINDOW_IDEAL_OUTPUT_II_ONE")
      }
    }
    lastReceived := cycle
    printf("FAST_WINDOW_RESULT phase=%d index=%d residual=%x extra=%x\n",phase,ri,block.residual.asUInt,block.extra.asUInt)
    ri := ri+1.U
    when(ri === (rows-1).U) {
      assert(si === rows.U && li === loads.U, "FAST_WINDOW_ALL_INPUTS")
      when(phase === 3.U) { printf("FAST_WINDOW_SUITE_PASS\n") }
      phase := phase+1.U; loading := true.B; batch := 0.U; li := 0.U; si := 0.U; ri := 0.U; offer := false.B
    }.elsewhen(batches(ri+1.U) =/= batch) {
      assert(si === ri+1.U, "FAST_WINDOW_BATCH_DRAIN")
      batch := batch+1.U; loading := true.B
    }
  }
  assert(cycle < 500000.U, "FAST_WINDOW_WATCHDOG")
  io.finished := phase === 4.U
}
object EmitFastWindow {
  def main(args: Array[String]): Unit = {
    require(args.length == 1)
    ChiselStage.emitSystemVerilogFile(new FastPixelFront, Array("--target-dir",args(0)),
      Array("--split-verilog","--disable-all-randomization","--strip-debug-info"))
  }
}
object EmitFastWindowTest {
  def main(args: Array[String]): Unit = {
    require(args.length == 3)
    def count(p: String): Int = {
      val s = scala.io.Source.fromFile(p); try s.getLines().count(_.nonEmpty) finally s.close()
    }
    ChiselStage.emitSystemVerilogFile(new FastWindowTest(count(args(1)),count(args(1)+".loads.tsv"),
      args(1),args(2)=="negative"),Array("--target-dir",args(0)),
      Array("--split-verilog","--disable-all-randomization","--strip-debug-info"))
  }
}
