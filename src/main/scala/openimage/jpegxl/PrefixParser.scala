// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl
import chisel3._
import chisel3.util._

/** JPEG XL prefix-length grammar. Emits one length per alphabet symbol;
  * consumers still validate/build the resulting canonical table. No image or
  * reference-generated codebook is embedded here.
  */
class PrefixParser extends Module {
  val io = IO(new Bundle {
    val begin = Flipped(Decoupled(UInt(10.W)))
    val request = Decoupled(UInt(6.W))
    val response = Flipped(Decoupled(UInt(32.W)))
    val length = Decoupled(UInt(4.W))
    val done = Output(Bool())
    val failed = Output(Bool())
  })
  private val states = Enum(25)
  val idle = states(0)
  val getMode = states(1)
  val gotMode = states(2)
  val gotSimpleCount = states(3)
  val gotSimpleValue = states(4)
  val gotSimpleShape = states(5)
  val simpleEmit = states(6)
  val clFirst = states(7)
  val clTwo = states(8)
  val clThree = states(9)
  val clFour = states(10)
  val clAccept = states(11)
  val clBegin = states(12)
  val clLoad = states(13)
  val clWait = states(14)
  val symbolBegin = states(15)
  val symbolWait = states(16)
  val bitFeed = states(17)
  val gotRepeat = states(18)
  val emit = states(19)
  val zeroEmit = states(20)
  val complete = states(21)
  val bad = states(22)
  val readRequest = states(23)
  val readResponse = states(24)
  val state = RegInit(idle)
  val returnState = Reg(UInt(5.W)); val requestN = Reg(UInt(6.W)); val value = Reg(UInt(32.W))
  val alphabet = Reg(UInt(10.W))
  val simpleCount = Reg(UInt(3.W)); val simpleIndex = Reg(UInt(3.W))
  val simpleValues = Reg(Vec(4, UInt(9.W))); val simpleShape = Reg(Bool())
  val symbolWidth = Reg(UInt(4.W))
  val index = RegInit(0.U(10.W))
  val clLengths = Reg(Vec(18, UInt(4.W)))
  val clIndex = Reg(UInt(5.W)); val clBudget = Reg(UInt(6.W)); val clValue = Reg(UInt(4.W))
  val clActive = Reg(UInt(5.W))
  val capacity = Reg(UInt(17.W))
  val previous = Reg(UInt(4.W))
  val repeatTotal = Reg(UInt(32.W)); val repeatedValue = Reg(UInt(4.W))
  val repeatCode = Reg(UInt(5.W)); val repeated = RegInit(false.B)
  val emitValue = Reg(UInt(4.W)); val emitRemaining = Reg(UInt(32.W))
  val clTable = Module(new CanonicalTable)
  val clOrder = VecInit(Seq(1,2,3,4,0,5,17,6,16,7,8,9,10,11,12,13,14,15).map(_.U(5.W)))

  io.begin.ready := state === idle || state === complete || state === bad
  io.request.valid := state === readRequest
  io.request.bits := requestN
  io.response.ready := state === readResponse
  io.length.valid := state === simpleEmit || state === emit || state === zeroEmit
  io.length.bits := Mux(state === emit, emitValue, 0.U)
  io.done := state === complete
  io.failed := state === bad
  clTable.io.begin.valid := state === clBegin
  clTable.io.begin.bits := 18.U
  clTable.io.length.valid := state === clLoad
  clTable.io.length.bits := clLengths(clIndex)
  clTable.io.decode.valid := state === symbolBegin
  clTable.io.decode.bits := true.B
  clTable.io.bit.valid := state === bitFeed
  clTable.io.bit.bits := value(0)
  clTable.io.symbol.ready := state === symbolWait

  def read(n: UInt, next: UInt): Unit = {
    requestN := n; returnState := next; state := readRequest
  }
  when(io.request.fire) { state := readResponse }
  when(io.response.fire) { value := io.response.bits; state := returnState }
  when(io.begin.fire) {
    alphabet := io.begin.bits; index := 0.U; repeated := false.B
    for (i <- 0 until 18) { clLengths(i) := 0.U }
    capacity := 32768.U; previous := 8.U; repeatTotal := 0.U
    clBudget := 32.U; clActive := 0.U
    val n = WireDefault(0.U(4.W))
    for (i <- 0 until 9) { when((io.begin.bits-1.U)(i)) { n := (i+1).U } }
    symbolWidth := n
    when(io.begin.bits === 0.U || io.begin.bits > 512.U) { state := bad }
    .elsewhen(io.begin.bits === 1.U) {
      simpleCount := 1.U; simpleValues(0) := 0.U; simpleShape := false.B; state := simpleEmit
    }.otherwise { state := getMode }
  }
  switch(state) {
    is(getMode) { read(2.U, gotMode) }
    is(gotMode) {
      when(value === 1.U) { read(2.U, gotSimpleCount) }
      .otherwise { clIndex := value(4, 0); state := clFirst }
    }
    is(gotSimpleCount) {
      simpleCount := value(2, 0)+1.U; simpleIndex := 0.U; simpleShape := false.B
      read(symbolWidth, gotSimpleValue)
    }
    is(gotSimpleValue) {
      val duplicate = (0 until 4).map(i => i.U < simpleIndex && simpleValues(i) === value).reduce(_ || _)
      when(value >= alphabet || duplicate) { state := bad }
      .otherwise {
        simpleValues(simpleIndex(1, 0)) := value(8, 0)
        when(simpleIndex === simpleCount-1.U) {
          when(simpleCount === 4.U) { read(1.U, gotSimpleShape) }.otherwise { state := simpleEmit }
        }.otherwise { simpleIndex := simpleIndex+1.U; read(symbolWidth, gotSimpleValue) }
      }
    }
    is(gotSimpleShape) { simpleShape := value(0); state := simpleEmit }
    is(simpleEmit) {
      val lengths = Wire(Vec(4, UInt(4.W)))
      lengths(0) := Mux(simpleCount <= 2.U || (simpleCount === 4.U && simpleShape), 1.U,
        Mux(simpleCount === 3.U, 1.U, 2.U))
      lengths(1) := Mux(simpleCount === 2.U, 1.U, 2.U)
      lengths(2) := Mux(simpleCount === 4.U && simpleShape, 3.U, 2.U)
      lengths(3) := Mux(simpleShape, 3.U, 2.U)
      for (i <- 0 until 4) {
        when(i.U < simpleCount && index === simpleValues(i)) { io.length.bits := lengths(i) }
      }
      when(io.length.fire) {
        when(index === alphabet-1.U) { state := complete }.otherwise { index := index+1.U }
      }
    }
    is(clFirst) { read(2.U, clTwo) }
    is(clTwo) {
      when(value === 3.U) { read(1.U, clThree) }
      .otherwise { clValue := Mux(value === 0.U, 0.U, Mux(value === 1.U, 4.U, 3.U)); state := clAccept }
    }
    is(clThree) {
      when(value === 0.U) { clValue := 2.U; state := clAccept }
        .otherwise { read(1.U, clFour) }
    }
    is(clFour) { clValue := Mux(value === 0.U, 1.U, 5.U); state := clAccept }
    is(clAccept) {
      val weight = Mux(clValue === 0.U, 0.U, 32.U(6.W) >> clValue)
      val activeNow = clActive+Mux(clValue === 0.U, 0.U, 1.U)
      clLengths(clOrder(clIndex)) := clValue
      clBudget := clBudget-weight; clActive := activeNow
      when(weight > clBudget) { state := bad }
      .elsewhen(weight === clBudget || clIndex === 17.U) {
        when(weight =/= clBudget && activeNow =/= 1.U) { state := bad }
          .otherwise { state := clBegin; clIndex := 0.U }
      }.otherwise { clIndex := clIndex+1.U; state := clFirst }
    }
    is(clBegin) { when(clTable.io.begin.fire) { clIndex := 0.U; state := clLoad } }
    is(clLoad) {
      when(clTable.io.length.fire) {
        when(clIndex === 17.U) { state := clWait }.otherwise { clIndex := clIndex+1.U }
      }
    }
    is(clWait) {
      when(clTable.io.failed) { state := bad }
        .elsewhen(clTable.io.built) { state := symbolBegin }
    }
    is(symbolBegin) { when(clTable.io.decode.fire) { state := symbolWait } }
    is(symbolWait) {
      when(clTable.io.failed) { state := bad }
      .elsewhen(clTable.io.symbol.fire) {
        val s = clTable.io.symbol.bits
        when(s < 16.U) {
          emitValue := s(3, 0); emitRemaining := 1.U; repeatTotal := 0.U
          when(s =/= 0.U) { previous := s(3, 0) }
          state := emit
        }.elsewhen(s <= 17.U) { repeatCode := s(4, 0); read(s-14.U, gotRepeat) }
        .otherwise { state := bad }
      }.elsewhen(clTable.io.bit.ready) { read(1.U, bitFeed) }
    }
    is(bitFeed) { when(clTable.io.bit.fire) { state := symbolWait } }
    is(gotRepeat) {
      val repeatedNow = Mux(repeatCode === 16.U, previous, 0.U)
      val old = Mux(repeated && repeatedValue === repeatedNow, repeatTotal, 0.U)
      val total = Mux(old === 0.U, 0.U, (old-2.U) << (repeatCode-14.U))+&value+3.U
      val delta = total-old
      when(total > 512.U || delta === 0.U || index+&delta > alphabet) { state := bad }
      .otherwise {
        repeated := true.B; repeatedValue := repeatedNow; repeatTotal := total
        emitValue := repeatedNow; emitRemaining := delta; state := emit
      }
    }
    is(emit) {
      when(io.length.fire) {
        val weight = Mux(emitValue === 0.U, 0.U, 32768.U(17.W) >> emitValue)
        capacity := capacity-weight; index := index+1.U; emitRemaining := emitRemaining-1.U
        when(weight > capacity) { state := bad }
        .elsewhen(index === alphabet-1.U) { state := complete }
        .elsewhen(emitRemaining === 1.U) {
          state := Mux(weight === capacity, zeroEmit, symbolBegin)
        }
      }
    }
    is(zeroEmit) {
      when(io.length.fire) {
        when(index === alphabet-1.U) { state := complete }.otherwise { index := index+1.U }
      }
    }
  }
}
