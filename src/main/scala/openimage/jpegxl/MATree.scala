// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl
import chisel3._
import chisel3.util._

class LeafPrediction extends Bundle {
  val context = UInt(4.W)
  val predictor = UInt(3.W)
}

/** Breadth-first bounded MA tree. Entropy is owned by the caller, so the same
  * physical prefix bank can subsequently be rebuilt for image residuals.
  * Only static channel/group properties are accepted by this profile.
  */
class MATree extends Module {
  val io = IO(new Bundle {
    val begin = Flipped(Decoupled(Bool()))
    val codeBegin = Decoupled(UInt(4.W))
    val codeContext = Decoupled(UInt(4.W))
    val codeValue = Flipped(Decoupled(UInt(32.W)))
    val codeReady = Input(Bool())
    val codeRunRemaining = Input(UInt(19.W))
    val codeFailed = Input(Bool())
    val codeStatus = Input(UInt(3.W))
    val lookup = Flipped(Decoupled(UInt(3.W))) // channel; group always zero
    val leaf = Decoupled(new LeafPrediction)
    val done = Output(Bool())
    val leaves = Output(UInt(4.W))
    val failed = Output(Bool())
    val status = Output(UInt(3.W))
  })
  val idle :: beginCode :: request :: receive :: checkEnd :: complete :: walk :: offer :: bad :: Nil = Enum(9)
  val state = RegInit(idle)
  val field = Reg(UInt(3.W))
  val node = Reg(UInt(4.W)); val total = Reg(UInt(5.W)); val leaves = Reg(UInt(4.W))
  val property = Reg(Vec(15, UInt(2.W))) // zero=leaf, one=channel, two=group
  val split = Reg(Vec(15, SInt(33.W)))
  val left = Reg(Vec(15, UInt(4.W))); val right = Reg(Vec(15, UInt(4.W)))
  val predictor = Reg(Vec(15, UInt(3.W))); val context = Reg(Vec(15, UInt(4.W)))
  val status = RegInit(0.U(3.W)); val channel = Reg(UInt(3.W))
  val selected = Reg(UInt(4.W)); val steps = Reg(UInt(4.W))
  val result = Reg(new LeafPrediction)
  val treeReady = state === complete || state === walk || state === offer
  io.begin.ready := state === idle || state === complete || state === bad
  io.codeBegin.valid := state === beginCode
  io.codeBegin.bits := 6.U
  io.codeContext.valid := state === request
  io.codeContext.bits := MuxLookup(field, 1.U(4.W))(Seq(
    0.U -> 1.U, 1.U -> 0.U, 2.U -> 2.U, 3.U -> 3.U, 4.U -> 4.U, 5.U -> 5.U))
  io.codeValue.ready := state === receive
  io.lookup.ready := state === complete && !io.begin.valid
  io.leaf.valid := state === offer
  io.leaf.bits := result
  io.done := treeReady
  io.leaves := leaves
  io.failed := state === bad
  io.status := status
  def fail(code: UInt): Unit = { status := code; state := bad }
  def nextNode(): Unit = { node := node+1.U; field := 0.U; state := checkEnd }
  when(io.begin.fire) {
    total := 1.U; node := 0.U; leaves := 0.U; field := 0.U
    status := 0.U; state := beginCode
  }
  when(io.codeBegin.fire) { state := request }
  when(io.codeContext.fire) { state := receive }
  when(io.codeValue.fire) {
    val v = io.codeValue.bits
    switch(field) {
      is(0.U) {
        when(v > 2.U) { fail(4.U) }
        .otherwise {
          property(node) := v(1,0)
          when(v === 0.U) { field := 2.U; state := request }
          .elsewhen(total+2.U > 15.U) { fail(4.U) }
          .otherwise {
            left(node) := total(3,0); right(node) := (total+1.U)(3,0)
            total := total+2.U; field := 1.U; state := request
          }
        }
      }
      is(1.U) {
        val magnitude = Cat(0.U(1.W),v >> 1).asSInt
        split(node) := Mux(v(0), -magnitude-1.S, magnitude)
        nextNode()
      }
      is(2.U) {
        when(v =/= 0.U && v =/= 1.U && v =/= 2.U && v =/= 5.U) { fail(4.U) }
        .otherwise { predictor(node) := v(2,0); field := 3.U; state := request }
      }
      is(3.U) {
        when(v =/= 0.U) { fail(4.U) }.otherwise { field := 4.U; state := request }
      }
      is(4.U) {
        when(v =/= 0.U) { fail(4.U) }.otherwise { field := 5.U; state := request }
      }
      is(5.U) {
        when(v =/= 0.U) { fail(4.U) }
        .otherwise { context(node) := leaves; leaves := leaves+1.U; nextNode() }
      }
    }
  }
  when(state === checkEnd) {
    when(node === total) {
      when(io.codeRunRemaining =/= 0.U || leaves === 0.U) { fail(3.U) }
        .elsewhen(io.codeReady) { state := complete }
    }.otherwise { state := request }
  }
  when(io.lookup.fire) { channel := io.lookup.bits; selected := 0.U; steps := 0.U; state := walk }
  when(state === walk) {
    when(selected >= total || steps >= 15.U) { fail(3.U) }
    .elsewhen(property(selected) === 0.U) {
      result.context := context(selected); result.predictor := predictor(selected); state := offer
    }.otherwise {
      val value = Mux(property(selected) === 1.U, Cat(0.U(1.W),channel).asSInt,0.S)
      selected := Mux(value > split(selected),left(selected),right(selected))
      steps := steps+1.U
    }
  }
  when(io.leaf.fire) { state := complete }
  when(io.codeFailed && (state === request || state === receive || state === checkEnd)) { fail(io.codeStatus) }
}
