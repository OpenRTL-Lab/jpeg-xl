// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl.fast

import chisel3._
import chisel3.util._

class FastLengthCommand extends Bundle {
  val tag = UInt(32.W)
  val frequencies = Vec(33, UInt(18.W))
  val minimum = Vec(33, UInt(4.W))
  val maximum = Vec(33, UInt(4.W))
}
class FastLengthResult extends Bundle {
  val tag = UInt(32.W)
  val lengths = Vec(33, UInt(4.W))
  val cost = UInt(24.W)
  val cycles = UInt(32.W)
}

/** Bounded exact Kraft DP, not a full PrefixCode/encoder.
  * 64 offsets/cycle by default; 9 wide synchronous read copies for 14 candidates.
  * Same-cost choice is the shortest current length, matching reverse traceback.
  * Approved profile requires shortest clamped minimum == 1.
  */
class FastLengthSolver(val lanes: Int = 64, val maxPrecision: Int = 14) extends RawModule {
  require(isPow2(lanes) && lanes >= 2 && lanes <= 64)
  require(maxPrecision >= log2Ceil(lanes) && maxPrecision <= 14)
  private val bankBits = log2Ceil(lanes)
  private val rows = (1 << maxPrecision) / lanes + 1
  private val rowBits = log2Ceil(rows)
  val clock = IO(Input(Clock()))
  val rst_n = IO(Input(Bool()))
  val io = IO(new Bundle {
    val command = Flipped(Decoupled(new FastLengthCommand))
    val output = Decoupled(new FastLengthResult)
  })
  withClockAndReset(clock, (!rst_n).asAsyncReset) {
    val idle :: prepareStart :: prepareWait :: initialize :: symbolCapture :: weightStart :: weightWait :: issue :: drain :: traceRequest :: traceResponse :: done :: Nil = Enum(12)
    val state = RegInit(idle)
    val freq = Reg(Vec(33, UInt(18.W)))
    val lo = Reg(Vec(33, UInt(4.W))); val hi = Reg(Vec(33, UInt(4.W)))
    val original = Reg(Vec(33, UInt(6.W)))
    val number = Reg(UInt(6.W)); val precision = Reg(UInt(4.W))
    val infinity = Reg(UInt(24.W)); val target = Reg(UInt((maxPrecision + 1).W))
    val page = RegInit(false.B)
    val symbol = Reg(UInt(6.W)); val row = Reg(UInt(rowBits.W))
    val finalRow = Reg(UInt(rowBits.W))
    val result = Reg(new FastLengthResult)
    val capturedCommand = Reg(new FastLengthCommand)
    val elapsed = Reg(UInt(32.W))
    when(state =/= idle && state =/= done) { elapsed := elapsed + 1.U }
    io.command.ready := rst_n && state === idle
    io.output.valid := rst_n && state === done
    io.output.bits := result
    when(io.output.fire) { state := idle }

    when(io.command.fire) {
      capturedCommand := io.command.bits
      page := false.B; row := 0.U; symbol := 0.U; elapsed := 0.U
      result := 0.U.asTypeOf(result); result.tag := io.command.bits.tag
      state := prepareStart
    }
    val preparing = state === prepareStart
    val usedReg = RegEnable(VecInit(capturedCommand.frequencies.map(_ =/= 0.U)), preparing)
    val minimumReg = RegEnable(VecInit(capturedCommand.minimum.map(m => Mux(m===0.U,1.U,m))), preparing)
    val usedValid = RegNext(preparing, false.B)
    when(preparing) {
      for (i <- 0 until 33) when(capturedCommand.frequencies(i)=/=0.U) {
        assert(Mux(capturedCommand.minimum(i)===0.U,1.U,capturedCommand.minimum(i)) <=
          capturedCommand.maximum(i), "FAST_LENGTH_LIMITS")
      }
      state := prepareWait
    }
    val totalTree = FastRetiming.tree(capturedCommand.frequencies.toSeq, preparing,24,"totalTree")(_ + _)
    val maximumTree = FastRetiming.tree((0 until 33).map(i =>
      Mux(capturedCommand.frequencies(i)=/=0.U,capturedCommand.maximum(i),0.U(4.W))),
      preparing,4,"maximumTree")((a,b) => Mux(a>b,a,b))
    val countTree = FastRetiming.tree(usedReg.toSeq.map(_.asUInt),usedValid,6,"countTree")(_ + _)
    when(countTree._2) {
      assert(countTree._1>=2.U,"FAST_LENGTH_AT_LEAST_TWO")
      number := countTree._1
    }
    when(totalTree._2) {
      assert(maximumTree._2,"FAST_LENGTH_MAXIMUM_ALIGNMENT")
      val hasOne = (0 until 33).map(i => usedReg(i) && minimumReg(i)===1.U).reduce(_ || _)
      assert(totalTree._1<=339682.U && maximumTree._1<=maxPrecision.U && hasOne,"FAST_LENGTH_PROFILE")
      precision := maximumTree._1
      val fullTarget = (1.U((maxPrecision+1).W) << maximumTree._1)(maxPrecision,0)
      target := fullTarget; finalRow := (fullTarget >> bankBits)(rowBits-1,0)
    }
    // Exact total*highest: four shifted terms, two explicitly instantiated DSP
    // levels. Do not truncate total to the 12-bit address-multiply interface.
    val productTerms = (0 until 4).map(i => {
      val t = Wire(UInt(24.W)); t := Mux(maximumTree._1(i),totalTree._1 << i,0.U); t
    })
    val productPairs = Module(new FastFpgaAdd)
    productPairs.io.a0 := productTerms(0); productPairs.io.b0 := productTerms(1)
    productPairs.io.a1 := productTerms(2); productPairs.io.b1 := productTerms(3)
    val partialPairs = RegEnable(VecInit(Seq(productPairs.io.s0(23,0),productPairs.io.s1(23,0))),totalTree._2)
    val partialValid = RegNext(totalTree._2,false.B)
    val productSum = Module(new FastFpgaAdd)
    productSum.io.a0 := partialPairs(0); productSum.io.b0 := partialPairs(1)
    productSum.io.a1 := 0.U; productSum.io.b1 := 0.U
    val infinityReady = RegInit(false.B)
    when(io.command.fire) { infinityReady := false.B }
    when(partialValid) {
      assert(!productSum.io.s0(24),"FAST_LENGTH_INFINITY_OVERFLOW")
      infinity := productSum.io.s0(23,0); infinityReady := true.B
    }
    // Parallel prefix scan with one 6-bit add per register, not a priority
    // chain of dynamic writes. Every compact destination is a one-hot mux.
    var ranks: Seq[UInt] = usedReg.toSeq.map(b => { val u=Wire(UInt(6.W)); u:=b; u })
    var rankValid = usedValid
    for (distance <- Seq(1,2,4,8,16,32)) {
      val before = ranks; val enable = rankValid
      ranks = before.indices.map { i =>
        val value = Wire(UInt(6.W)); value := (if(i>=distance) before(i)+before(i-distance) else before(i))
        RegEnable(value,enable).suggestName(s"rankScan${distance}_$i")
      }
      rankValid = RegNext(enable,false.B)
    }
    val exclusiveRanks = RegEnable(VecInit(ranks.indices.map(i => ranks(i)-usedReg(i).asUInt)),rankValid)
    val compactValid = RegNext(rankValid,false.B)
    when(compactValid) {
      assert(state===prepareWait && infinityReady,"FAST_LENGTH_PREPARE_ALIGNMENT")
      for(j <- 0 until 33) {
        val select = (0 until 33).map(i => usedReg(i) && exclusiveRanks(i)===j.U)
        assert(PopCount(select)===Mux(j.U<number,1.U,0.U),"FAST_LENGTH_COMPACT_ONE_HOT")
        freq(j) := Mux1H(select,capturedCommand.frequencies.toSeq)
        lo(j) := Mux1H(select,minimumReg.toSeq)
        hi(j) := Mux1H(select,capturedCommand.maximum.toSeq)
        original(j) := Mux1H(select,(0 until 33).map(_.U(6.W)))
      }
      state := initialize
    }

    val symbolFrequency = Reg(UInt(18.W)); val symbolMinimum = Reg(UInt(4.W))
    val symbolMaximum = Reg(UInt(4.W))
    when(state===symbolCapture) {
      symbolFrequency := freq(symbol); symbolMinimum := lo(symbol); symbolMaximum := hi(symbol)
      state := weightStart
    }
    val weightLaunch = state===weightStart
    val weightValid = RegNext(weightLaunch,false.B)
    val weights = Wire(Vec(maxPrecision,UInt(24.W)))
    for(bits <- 1 to maxPrecision) {
      val terms = (0 until 4).filter(i => ((bits>>i)&1)!=0).map { i =>
        val t=Wire(UInt(24.W)); t:=symbolFrequency<<i; t
      }.padTo(4,0.U(24.W))
      val pairs = Module(new FastFpgaAdd)
      pairs.io.a0:=terms(0); pairs.io.b0:=terms(1); pairs.io.a1:=terms(2); pairs.io.b1:=terms(3)
      val partial = RegEnable(VecInit(Seq(pairs.io.s0(23,0),pairs.io.s1(23,0))),weightLaunch)
        .suggestName(s"symbolWeights0_${bits-1}")
      val add = Module(new FastFpgaAdd)
      add.io.a0:=partial(0); add.io.b0:=partial(1); add.io.a1:=0.U; add.io.b1:=0.U
      weights(bits-1):=RegEnable(add.io.s0(23,0),weightValid).suggestName(s"symbolWeights1_${bits-1}")
    }
    val weightsReady = RegNext(weightValid,false.B)
    when(weightLaunch) { state:=weightWait }
    when(state===weightWait && weightsReady) { state:=issue }

    // For each candidate bit length, one synchronous read per bank.
    // Page selection separates all previous reads from current writes.
    // Keep every read copy and the one-cycle latency. Shallow 514x1536
    // cost tables waste URAM depth: explicitly map four copies to BRAM,
    // leaving the remaining five and the deep traceback in URAM.
    val costs = Seq.tabulate(1 + math.max(1, maxPrecision - bankBits)) { index =>
      FastFpgaMemory(lanes * 24, 2 * rows, maxPrecision > bankBits && index >= 4, clock)
    }
    val choices = FastFpgaMemory(lanes * 4, 33 * rows, maxPrecision > bankBits, clock)
    val reading = state === issue
    val pending = RegNext(reading, false.B)
    val pendingRow = RegEnable(row, reading)
    // For delta<lanes, every predecessor is in row or row-1. All larger
    // powers of two share a row address across every lane. Broadcast writes
    // are identical across read copies, so pack lanes instead of tiny RAMs.
    val readPage = Mux(page, rows.U, 0.U)
    for ((memory, index) <- costs.zipWithIndex) {
      val distance = if (index == 0) 0 else 1 << (index - 1)
      memory.io.ren := reading && row >= distance.U
      memory.io.raddr := readPage +& (row - distance.U)
    }
    val evaluation = Module(new FastLengthRow(lanes, maxPrecision))
    evaluation.clock := clock; evaluation.rst_n := rst_n; evaluation.io.inputValid := pending
    evaluation.io.reads := VecInit(costs.map(_.io.rdata))
    evaluation.io.precision := precision
    evaluation.io.minimum := symbolMinimum; evaluation.io.maximum := symbolMaximum
    evaluation.io.weighted := weights; evaluation.io.infinity := infinity
    evaluation.io.target := target; evaluation.io.row := pendingRow
    val best = evaluation.io.costs.asTypeOf(Vec(lanes, UInt(24.W)))
    val selected = evaluation.io.lengths.asTypeOf(Vec(lanes, UInt(4.W)))
    val initial = VecInit((0 until lanes).map { b =>
      val off = (row << bankBits) + b.U
      Mux(off === 0.U, 0.U(24.W), infinity)
    })
    val initializing = state === initialize
    val writing = evaluation.io.outputValid
    val latency = 3+log2Ceil(maxPrecision)
    def rowDelay[T <: Data](data: T): T = {
      var value=data; var enable=pending
      for (_ <- 0 until latency) {
        value=RegEnable(value,enable)
        enable=RegNext(enable,false.B)
      }
      value
    }
    val writeRow=rowDelay(pendingRow); val writePage=rowDelay(page); val writeSymbol=rowDelay(symbol)
    val writeLast=rowDelay(pendingRow===finalRow)
    assert(!(initializing && (pending || writing)), "FAST_LENGTH_SINGLE_WRITE_PHASE")
    for (memory <- costs) {
      memory.io.wen := initializing || writing
      memory.io.waddr := Mux(initializing, row, Mux(writePage, 0.U, rows.U) +& writeRow)
      memory.io.wdata := Mux(initializing, initial.asUInt, best.asUInt)
    }
    when(state === initialize) {
      when(row === finalRow) { row := 0.U; state := symbolCapture }
        .otherwise { row := row + 1.U }
    }
    when(reading) {
      when(row === finalRow) { state := drain }
        .otherwise { row := row + 1.U }
    }
    val traceSymbol = Reg(UInt(6.W))
    val traceOffset = Reg(UInt((maxPrecision + 1).W))
    when(writing) {
      assert(writePage===page && writeSymbol===symbol,"FAST_LENGTH_WRITE_IDENTITY")
      when(writeLast) {
        when(writeSymbol + 1.U === number) {
          val endCost = best(target(bankBits - 1, 0))
          assert(endCost < infinity, "FAST_LENGTH_FEASIBLE_KRAFT")
          result.cost := endCost
          traceSymbol := writeSymbol; traceOffset := target; state := traceRequest
        }.otherwise {
          symbol := writeSymbol + 1.U; page := !writePage; row := 0.U; state := symbolCapture
        }
      }
    }
    choices.io.wen := writing
    choices.io.waddr := writeSymbol * rows.U + writeRow
    choices.io.wdata := selected.asUInt
    choices.io.ren := state === traceRequest
    choices.io.raddr := traceSymbol * rows.U + (traceOffset >> bankBits)
    val trace = choices.io.rdata.asTypeOf(Vec(lanes, UInt(4.W)))
    when(state === traceRequest) { state := traceResponse }
    when(state === traceResponse) {
      val bits = trace(traceOffset(bankBits - 1, 0))
      val delta = (1.U((maxPrecision + 1).W) << (precision - bits))(maxPrecision, 0)
      assert(bits >= lo(traceSymbol) && bits <= hi(traceSymbol) && traceOffset >= delta,
        "FAST_LENGTH_TRACEBACK")
      result.lengths(original(traceSymbol)) := bits
      traceOffset := traceOffset - delta
      when(traceSymbol === 0.U) {
        assert(traceOffset === delta, "FAST_LENGTH_FULL_KRAFT")
        result.cycles := elapsed + 1.U; state := done
      }.otherwise { traceSymbol := traceSymbol - 1.U; state := traceRequest }
    }
  }
}
