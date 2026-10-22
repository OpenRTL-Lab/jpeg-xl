// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl
import chisel3._
import chisel3.util._

class FileByte extends Bundle {
  val data = UInt(8.W)
  val last = Bool()
}

/** Serial bounded bit requests with stable responses and real EOF detection.
  * Appending and consuming never update the pool in the same cycle.
  */
class BitReader extends Module {
  val io = IO(new Bundle {
    val clear = Input(Bool())
    val input = Flipped(Decoupled(new FileByte))
    val request = Flipped(Decoupled(UInt(6.W)))
    val response = Decoupled(UInt(32.W))
    val consumed = Output(UInt(32.W))
    val failed = Output(Bool())
  })
  val pool = RegInit(0.U(64.W))
  val available = RegInit(0.U(7.W))
  val consumed = RegInit(0.U(32.W))
  val ended = RegInit(false.B)
  val failed = RegInit(false.B)
  val pending = RegInit(false.B)
  val requested = Reg(UInt(6.W))
  val valid = RegInit(false.B)
  val result = Reg(UInt(32.W))
  val consume = pending && available >= requested && !valid && !failed
  io.input.ready := !ended && !failed && available <= 56.U && !consume
  io.request.ready := !pending && !valid && !failed
  io.response.valid := valid
  io.response.bits := result
  io.consumed := consumed
  io.failed := failed
  when(io.input.fire) {
    pool := pool | (io.input.bits.data << available)
    available := available+8.U
    ended := io.input.bits.last
  }
  when(io.request.fire) {
    requested := io.request.bits
    pending := true.B
    when(io.request.bits > 32.U) { failed := true.B }
  }
  when(consume) {
    val mask = ((1.U(33.W) << requested)-1.U)(31, 0)
    result := pool(31, 0) & mask
    valid := true.B
    pool := pool >> requested
    available := available-requested
    consumed := consumed+requested
    pending := false.B
  }
  when(pending && ended && available < requested && !valid) { failed := true.B }
  when(io.response.fire) { valid := false.B }
  when(io.clear) {
    pool := 0.U; available := 0.U; consumed := 0.U; ended := false.B
    failed := false.B; pending := false.B; valid := false.B
  }
}
