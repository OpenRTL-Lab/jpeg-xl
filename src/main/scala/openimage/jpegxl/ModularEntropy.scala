// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl
import chisel3._
import chisel3.util._

/** Bounded prefix entropy stream: simple context map, hybrid integers, and
  * distance-one residual RLE. No pixel prediction or image grammar here.
  * All bit requests, including prefix-table loading, share one ordered port.
  */
class ModularEntropy extends Module {
  val io = IO(new Bundle {
    val begin = Flipped(Decoupled(UInt(4.W))) // 1..8 original contexts
    val request = Decoupled(UInt(6.W))
    val response = Flipped(Decoupled(UInt(32.W)))
    val context = Flipped(Decoupled(UInt(4.W)))
    val value = Decoupled(UInt(32.W))
    val ready = Output(Bool())
    val failed = Output(Bool())
    val status = Output(UInt(3.W)) // 3 malformed, 4 legal outside profile
    val runRemaining = Output(UInt(19.W))
    val fast18 = Output(Bool())
  })
  object S extends ChiselEnum {
    val idle, lzFlag, minSymbol, minSymbolExtra, minLength, minLengthExtra,
      configSplit, configMsb, configLsb, configStore, contextStart, contextFlag,
      contextBits, contextValue, contextCheck, prefixFlag, alphabetPresent,
      alphabetWidth, alphabetValue, alphabetStore, tableBegin, prefixBegin,
      prefixWait, available, symbolStart, symbolWait, symbolBit, hybridStart,
      hybridExtra, hybridDone, offer, bad, readRequest, readResponse = Value
  }
  val state = RegInit(S.idle)
  val returnState = Reg(S())
  val readN = Reg(UInt(6.W)); val bits = Reg(UInt(32.W))
  val contexts = Reg(UInt(4.W)); val originals = Reg(UInt(4.W))
  val maps = Reg(Vec(9, UInt(4.W)))
  val seen = RegInit(0.U(9.W)); val histograms = Reg(UInt(4.W))
  val index = Reg(UInt(4.W)); val mapBits = Reg(UInt(2.W))
  val lz = Reg(Bool()); val minSym = Reg(UInt(16.W)); val minLen = Reg(UInt(9.W))
  val configIndex = Reg(UInt(4.W)); val configLimit = Reg(UInt(4.W))
  val splits = Reg(Vec(10, UInt(4.W))); val msbs = Reg(Vec(10, UInt(4.W)))
  val lsbs = Reg(Vec(10, UInt(4.W)))
  val split = Reg(UInt(4.W)); val msb = Reg(UInt(4.W)); val lsb = Reg(UInt(4.W))
  val alphabets = Reg(Vec(9, UInt(10.W))); val alphabet = Reg(UInt(17.W)); val width = Reg(UInt(4.W))
  val selected = Reg(UInt(4.W)); val token = Reg(UInt(32.W))
  val hybridConfig = Reg(UInt(4.W)); val hybridKind = Reg(UInt(2.W)) // literal/length/distance
  val hybridValue = Reg(UInt(32.W)); val hybridHigh = Reg(UInt(32.W))
  val hybridLow = Reg(UInt(32.W)); val hybridLsb = Reg(UInt(4.W)); val hybridN = Reg(UInt(6.W))
  val history = Reg(UInt(32.W)); val haveHistory = RegInit(false.B)
  val run = RegInit(0.U(19.W)); val runLength = Reg(UInt(19.W))
  val result = Reg(UInt(32.W)); val status = RegInit(0.U(3.W))
  val tables = Seq.fill(9)(Module(new CanonicalTable))
  val parser = Module(new PrefixParser)
  val tableBuilt = VecInit(tables.map(_.io.built))
  val tableBad = VecInit(tables.map(_.io.failed))
  val tableDecodeReady = VecInit(tables.map(_.io.decode.ready))
  val tableBitReady = VecInit(tables.map(_.io.bit.ready))
  val tableSymbolValid = VecInit(tables.map(_.io.symbol.valid))
  val tableSymbol = VecInit(tables.map(_.io.symbol.bits))
  val tableLengthReady = VecInit(tables.map(_.io.length.ready))
  val tableBeginReady = VecInit(tables.map(_.io.begin.ready))

  def read(n: UInt, next: S.Type): Unit = { readN := n; returnState := next; state := S.readRequest }
  def fail(code: Int): Unit = { status := code.U; state := S.bad }
  def bitLength(x: UInt): UInt = {
    val n = WireDefault(0.U(4.W))
    for(i <- 0 until 4) { when(x(i)) { n := (i+1).U } }
    n
  }
  val loadingPrefix = state === S.prefixWait
  io.begin.ready := state === S.idle || state === S.available || state === S.bad
  io.request.valid := Mux(loadingPrefix, parser.io.request.valid, state === S.readRequest)
  io.request.bits := Mux(loadingPrefix, parser.io.request.bits, readN)
  io.response.ready := Mux(loadingPrefix, parser.io.response.ready, state === S.readResponse)
  parser.io.request.ready := loadingPrefix && io.request.ready
  parser.io.response.valid := loadingPrefix && io.response.valid
  parser.io.response.bits := io.response.bits
  parser.io.begin.valid := state === S.prefixBegin
  parser.io.begin.bits := alphabets(index)
  parser.io.length.ready := loadingPrefix && tableLengthReady(index)
  io.context.ready := state === S.available && !io.begin.valid
  io.value.valid := state === S.offer
  io.value.bits := result
  io.ready := state === S.available
  io.failed := state === S.bad
  io.status := status
  io.runRemaining := run
  io.fast18 := io.ready && !lz && histograms === 1.U &&
    splits(0) === 2.U && msbs(0) === 0.U && lsbs(0) === 1.U && tables.head.io.fast18
  for((t,i) <- tables.zipWithIndex) {
    t.io.begin.valid := state === S.tableBegin && index === i.U
    t.io.begin.bits := alphabets(index)
    t.io.length.valid := loadingPrefix && index === i.U && parser.io.length.valid
    t.io.length.bits := parser.io.length.bits
    t.io.decode.valid := state === S.symbolStart && selected === i.U
    t.io.decode.bits := true.B
    t.io.bit.valid := state === S.symbolBit && selected === i.U
    t.io.bit.bits := bits(0)
    t.io.symbol.ready := state === S.symbolWait && selected === i.U
  }
  when(state === S.readRequest && io.request.fire) { state := S.readResponse }
  when(state === S.readResponse && io.response.fire) { bits := io.response.bits; state := returnState }
  when(io.begin.fire) {
    contexts := io.begin.bits; originals := io.begin.bits
    status := 0.U; run := 0.U; haveHistory := false.B; seen := 0.U
    when(io.begin.bits === 0.U || io.begin.bits > 8.U) { fail(3) }
      .otherwise { read(1.U, S.lzFlag) }
  }
  switch(state) {
    is(S.lzFlag) {
      lz := bits(0)
      when(bits(0)) { contexts := contexts+1.U; read(2.U,S.minSymbol) }
        .otherwise { state := S.contextStart }
    }
    is(S.minSymbol) {
      val base = VecInit(Seq(224,512,4096,8).map(_.U(16.W)))(bits(1,0))
      minSym := base
      when(bits(1,0) === 3.U) { read(15.U,S.minSymbolExtra) }
        .otherwise { read(2.U,S.minLength) }
    }
    is(S.minSymbolExtra) { minSym := minSym+bits; read(2.U,S.minLength) }
    is(S.minLength) {
      minLen := VecInit(Seq(3,4,5,9).map(_.U(9.W)))(bits(1,0))
      read(VecInit(Seq(0,0,2,8).map(_.U(6.W)))(bits(1,0)),S.minLengthExtra)
    }
    is(S.minLengthExtra) {
      minLen := minLen+bits; configIndex := 9.U; configLimit := 8.U
      read(4.U,S.configSplit)
    }
    is(S.configSplit) {
      split := bits(3,0); msb := 0.U; lsb := 0.U
      when(bits > configLimit) { fail(3) }
        .elsewhen(bits === configLimit) { state := S.configStore }
        .otherwise { read(bitLength(bits(3,0)),S.configMsb) }
    }
    is(S.configMsb) {
      msb := bits(3,0)
      when(bits > split) { fail(3) }
        .otherwise { read(bitLength((split-bits)(3,0)),S.configLsb) }
    }
    is(S.configLsb) {
      lsb := bits(3,0)
      when(bits+&msb > split) { fail(3) }.otherwise { state := S.configStore }
    }
    is(S.configStore) {
      splits(configIndex) := split; msbs(configIndex) := msb; lsbs(configIndex) := lsb
      when(configIndex === 9.U) { state := S.contextStart }
        .elsewhen(configIndex === histograms-1.U) { index := 0.U; read(1.U,S.alphabetPresent) }
        .otherwise { configIndex := configIndex+1.U; read(4.U,S.configSplit) }
    }
    is(S.contextStart) {
      index := 0.U; histograms := 1.U; seen := 0.U
      when(contexts === 1.U) { maps(0) := 0.U; seen := 1.U; state := S.contextCheck }
        .otherwise { read(1.U,S.contextFlag) }
    }
    is(S.contextFlag) {
      when(!bits(0)) { fail(4) }.otherwise { read(2.U,S.contextBits) }
    }
    is(S.contextBits) { mapBits := bits(1,0); read(bits(1,0),S.contextValue) }
    is(S.contextValue) {
      maps(index) := bits(3,0); seen := seen | (1.U(9.W) << bits(2,0))
      when(bits+1.U > histograms) { histograms := bits+1.U }
      when(index === contexts-1.U) { state := S.contextCheck }
        .otherwise { index := index+1.U; read(mapBits,S.contextValue) }
    }
    is(S.contextCheck) {
      when(seen =/= ((1.U(10.W) << histograms)-1.U)) { fail(3) }
        .otherwise { read(1.U,S.prefixFlag) }
    }
    is(S.prefixFlag) {
      when(!bits(0)) { fail(4) }
        .otherwise { configIndex := 0.U; configLimit := 15.U; read(4.U,S.configSplit) }
    }
    is(S.alphabetPresent) {
      alphabet := 1.U
      when(bits(0)) { read(4.U,S.alphabetWidth) }.otherwise { state := S.alphabetStore }
    }
    is(S.alphabetWidth) {
      width := bits(3,0); read(bits(3,0),S.alphabetValue)
    }
    is(S.alphabetValue) { alphabet := (1.U(17.W) << width)+bits+1.U; state := S.alphabetStore }
    is(S.alphabetStore) {
      when(alphabet > 512.U) { fail(4) }
      .otherwise {
        alphabets(index) := alphabet(9,0)
        when(index === histograms-1.U) { index := 0.U; state := S.tableBegin }
          .otherwise { index := index+1.U; read(1.U,S.alphabetPresent) }
      }
    }
    is(S.tableBegin) { when(tableBeginReady(index)) { state := S.prefixBegin } }
    is(S.prefixBegin) { when(parser.io.begin.fire) { state := S.prefixWait } }
    is(S.prefixWait) {
      when(parser.io.failed || tableBad(index)) { fail(3) }
        .elsewhen(parser.io.done && tableBuilt(index)) {
          when(index === histograms-1.U) { state := S.available }
            .otherwise { index := index+1.U; state := S.tableBegin }
        }
    }
    is(S.available) {
      when(io.context.fire) {
        when(io.context.bits >= originals) { fail(3) }
          .elsewhen(run =/= 0.U) { result := history; run := run-1.U; state := S.offer }
          .otherwise { selected := maps(io.context.bits); hybridKind := 0.U; state := S.symbolStart }
      }
    }
    is(S.symbolStart) { when(tableDecodeReady(selected)) { state := S.symbolWait } }
    is(S.symbolWait) {
      when(tableBad(selected)) { fail(3) }
      .elsewhen(tableSymbolValid(selected)) {
        val incoming = tableSymbol(selected)
        when(hybridKind === 0.U && lz && incoming >= minSym) {
          token := incoming-minSym; hybridConfig := 9.U; hybridKind := 1.U
        }.otherwise { token := incoming; hybridConfig := selected }
        state := S.hybridStart
      }.elsewhen(tableBitReady(selected)) { read(1.U,S.symbolBit) }
    }
    is(S.symbolBit) { when(tableBitReady(selected)) { state := S.symbolWait } }
    is(S.hybridStart) {
      val s = splits(hybridConfig); val m = msbs(hybridConfig); val l = lsbs(hybridConfig)
      val threshold = 1.U(32.W) << s
      val remainder = token-threshold
      val extra = (s-m-l)+&(remainder >> (m+&l))
      when(token < threshold) { hybridValue := token; state := S.hybridDone }
      .elsewhen(extra > 24.U) { fail(3) }
      .otherwise {
        hybridHigh := (1.U(32.W) << m) | ((remainder >> l) & ((1.U(32.W) << m)-1.U))
        hybridLow := remainder & ((1.U(32.W) << l)-1.U)
        hybridLsb := l; hybridN := extra; read(extra,S.hybridExtra)
      }
    }
    is(S.hybridExtra) {
      hybridValue := (((hybridHigh << hybridN) | bits) << hybridLsb) | hybridLow
      state := S.hybridDone
    }
    is(S.hybridDone) {
      when(hybridKind === 1.U) {
        val length = hybridValue+&minLen
        when(length > 262144.U || !haveHistory) { fail(3) }
        .otherwise {
          runLength := length(18,0); selected := maps(contexts-1.U)
          hybridKind := 2.U; state := S.symbolStart
        }
      }.elsewhen(hybridKind === 2.U) {
        when(hybridValue =/= 1.U) { fail(4) }
        .otherwise { run := runLength-1.U; result := history; state := S.offer }
      }.otherwise {
        history := hybridValue; haveHistory := true.B; result := hybridValue; state := S.offer
      }
    }
    is(S.offer) { when(io.value.fire) { state := S.available } }
  }
}
