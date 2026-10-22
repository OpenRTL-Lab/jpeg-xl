// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl.fast

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline
import _root_.circt.stage.ChiselStage

class ExternalFastPrefixMemo extends BlackBox {
  override def desiredName = "FastPrefixMemoGroup"
  val io = IO(new Bundle {
    val clock = Input(Clock()); val rst_n = Input(Bool())
    val io = new Bundle {
      val input = Flipped(Decoupled(new FastStatisticsResult))
      val output = Decoupled(new FastPrefixGroupResult)
    }
  })
}

/** Official values only. The scoreboard models identity/replacement, not codes. */
class FastPrefixMemoTest(rows: Int, file: String, negative: Boolean) extends Module {
  require(rows == 64)
  val io = IO(new Bundle { val finished = Output(Bool()) })
  val tasks = 37 // A/B/A/C/B then all sixteen official groups twice.
  def table(col: Int, bits: Int): Mem[UInt] = {
    val m = Mem(rows, UInt(bits.W))
    loadMemoryFromFileInline(m, file + s".column-$col.hex"); m
  }
  val layouts = table(1,3); val raw = table(2,342); val lz = table(3,594)
  val rawLengths = table(4,76); val rawBits = table(5,152)
  val lzLengths = table(6,132); val lzBits = table(7,528)
  val dut = Module(new ExternalFastPrefixMemo)
  val phase = RegInit(0.U(3.W)); val cycle = RegInit(0.U(32.W)); cycle := cycle+1.U
  val seq = RegInit(0.U(6.W)); val started = Reg(UInt(32.W))
  val waiting = RegInit(false.B); val offer = RegInit(false.B)
  val reported = RegInit(false.B); val firstAvailable = Reg(UInt(32.W))
  val expectedHit = Reg(Bool()); val hits = RegInit(0.U(8.W)); val misses = RegInit(0.U(8.W))
  val resetStart = Reg(UInt(32.W))
  val running = phase===2.U || phase===4.U
  val resetting = phase===1.U || phase===3.U
  dut.io.clock := clock; dut.io.rst_n := !reset.asBool && !resetting
  val pattern = VecInit(Seq(0,1,0,2,1).map(_.U(4.W)))
  val group = Mux(seq<5.U, pattern(Mux(seq<5.U,seq,0.U)), ((seq-5.U)>>1)(3,0))
  val address = group << 2
  val in = dut.io.io.input.bits
  in := 0.U.asTypeOf(in)
  in.tag := "h95200000".U + (phase << 8) + seq
  in.context := seq(0); in.channels := layouts(address)
  // Conservative sample-count key is exercised independently of table values.
  in.samples := Mux(seq<5.U,0.U,group)
  for (c <- 0 until 4) {
    in.raw(c) := raw(address+c.U).asTypeOf(in.raw(c))
    in.lz(c) := lz(address+c.U).asTypeOf(in.lz(c))
  }
  dut.io.io.input.valid := phase===0.U && cycle===0.U ||
    running && !waiting && (phase===2.U || offer)
  dut.io.io.output.ready := running && (phase===2.U || cycle%31.U>=23.U)
  when(phase===0.U && cycle===5.U) { phase:=1.U }
  when(phase===1.U && cycle===8.U) { phase:=2.U }
  when(phase===3.U && cycle-resetStart===3.U) { phase:=4.U }
  when(running && !waiting && cycle%7.U=/=0.U) { offer:=true.B }

  // Flatten the complete input key independently from the product's key helper.
  val requestKey = Cat(in.channels,in.samples,in.raw.asUInt,in.lz.asUInt)
  val known = Reg(Vec(2,UInt(3757.W)))
  val present = RegInit(VecInit(Seq.fill(2)(false.B)))
  val replace = RegInit(0.U(1.W)); val savedKey = Reg(UInt(3757.W))
  val savedTag = Reg(UInt(32.W))
  when(resetting) {
    present.foreach(_ := false.B); replace:=0.U
    assert(!dut.io.io.output.valid,"FAST_MEMO_RESET_CANCEL")
  }
  when(running && dut.io.io.input.fire) {
    assert(!waiting,"FAST_MEMO_TEST_ONE_REQUEST")
    waiting:=true.B; offer:=false.B; started:=cycle; reported:=false.B
    savedKey:=requestKey; savedTag:=in.tag
    val found = (0 until 2).map(i => present(i) && known(i)===requestKey).reduce(_ || _)
    expectedHit:=found
    when(found) { hits:=hits+1.U }.otherwise { misses:=misses+1.U }
  }
  when(running && dut.io.io.output.valid && !reported) {
    reported:=true.B; firstAvailable:=cycle
    assert(waiting,"FAST_MEMO_TEST_UNSOLICITED")
    when(expectedHit) { assert(cycle-started===1.U,"FAST_MEMO_HIT_ONE_CYCLE") }
      .otherwise { assert(cycle-started>100.U,"FAST_MEMO_REAL_COLD_SOLVE") }
  }
  val stalled = running && dut.io.io.output.valid && !dut.io.io.output.ready
  val wasStalled = RegNext(stalled,false.B)
  val held = RegEnable(dut.io.io.output.bits.asUInt,stalled)
  when(running && wasStalled) {
    assert(dut.io.io.output.valid && held===dut.io.io.output.bits.asUInt,"FAST_MEMO_OUTPUT_HOLD")
  }
  when(running && dut.io.io.output.fire) {
    val out=dut.io.io.output.bits
    assert(out.tag===savedTag && out.channels===layouts(address),"FAST_MEMO_RETAG")
    for(c <- 0 until 4) {
      val a=address+c.U; val code=out.tables(c)
      assert(code.tag===savedTag,"FAST_MEMO_CHANNEL_RETAG")
      assert(code.rawLengths.asUInt===rawLengths(a),"FAST_MEMO_OFFICIAL_RAW_LENGTHS")
      assert(code.rawBits.asUInt===(if(negative && c==0) rawBits(a)^1.U else rawBits(a)),
        "FAST_MEMO_OFFICIAL_RAW_BITS")
      assert(code.lzLengths.asUInt===lzLengths(a),"FAST_MEMO_OFFICIAL_LZ_LENGTHS")
      assert(code.lzBits.asUInt===lzBits(a),"FAST_MEMO_OFFICIAL_LZ_BITS")
      printf("FAST_MEMO_TABLE phase=%d task=%d index=%d raw_lengths=%x raw_bits=%x lz_lengths=%x lz_bits=%x\n",
        phase,seq,a,code.rawLengths.asUInt,code.rawBits.asUInt,code.lzLengths.asUInt,code.lzBits.asUInt)
    }
    printf("FAST_MEMO_RESULT phase=%d task=%d group=%d hit=%d start=%d available=%d done=%d\n",
      phase,seq,group,expectedHit,started,Mux(reported,firstAvailable,cycle),cycle)
    when(!expectedHit) { known(replace):=savedKey; present(replace):=true.B; replace:=replace^1.U }
    waiting:=false.B; seq:=seq+1.U; reported:=false.B; offer:=false.B
    when(seq===(tasks-1).U) {
      when(phase===2.U) { phase:=3.U; resetStart:=cycle; seq:=0.U }
      .otherwise {
        assert(hits>=32.U && misses>=32.U,"FAST_MEMO_HIT_MISS_COVERAGE")
        printf("FAST_MEMO_SUITE_PASS tasks=74 hits=%d misses=%d\n",hits,misses)
        phase:=5.U
      }
    }
  }
  assert(cycle<2000000.U,"FAST_MEMO_WATCHDOG")
  io.finished:=phase===5.U
}
object EmitFastPrefixMemo {
  def main(args: Array[String]): Unit = {
    require(args.length==1)
    ChiselStage.emitSystemVerilogFile(new FastPrefixMemoGroup,Array("--target-dir",args(0)),
      Array("--split-verilog","--disable-all-randomization","--strip-debug-info"))
  }
}
object EmitFastPrefixMemoTest {
  def main(args: Array[String]): Unit = {
    require(args.length==3)
    ChiselStage.emitSystemVerilogFile(new FastPrefixMemoTest(64,args(1),args(2)=="negative"),
      Array("--target-dir",args(0)),Array("--split-verilog","--disable-all-randomization","--strip-debug-info"))
  }
}
