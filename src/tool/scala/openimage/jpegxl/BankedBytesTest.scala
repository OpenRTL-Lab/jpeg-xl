// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl
import chisel3._
import chisel3.util._

/** Independent address-pattern oracle, not a codec model. */
class BankedBytesTest extends Module {
  val io = IO(new Bundle { val finished = Output(Bool()) })
  val memory = Module(new BankedBytes(256))
  val cycle = RegInit(0.U(8.W)); cycle := cycle+1.U
  val writing = cycle < 32.U
  val patch = cycle === 32.U
  val address = Mux(patch,13.U(8.W),(cycle << 3)(7,0))
  val writeBytes = Wire(Vec(8,UInt(8.W)))
  for(i <- 0 until 8) {
    val at = (address+i.U)(7,0)
    val base = (at*37.U+11.U)(7,0)
    writeBytes(i) := Mux(patch,base ^ 165.U,base)
  }
  memory.io.write := writing || patch
  memory.io.writeAddress := address
  memory.io.writeData := writeBytes.asUInt
  memory.io.writeKeep := Mux(patch,31.U,255.U)
  val addresses = VecInit(Seq(0,1,7,8,9,12,13,15,17,23,127,248,249,253,254,255).map(_.U(8.W)))
  // Continuous responses first, then read/idle transitions and long holding.
  val reading = cycle >= 35.U && cycle < 67.U && (cycle < 43.U || cycle(0))
  val index = RegInit(0.U(4.W))
  memory.io.read := reading
  memory.io.readAddress := addresses(index)
  when(reading) { index := index+1.U }
  val response = RegNext(reading,false.B)
  val expectedAddress = RegEnable(addresses(index),reading)
  val seen = RegInit(false.B)
  when(response) { seen := true.B }
  when(response || seen) {
    for(i <- 0 until 8) {
      val at = (expectedAddress+i.U)(7,0)
      val base = (at*37.U+11.U)(7,0)
      val expected = Mux(at >= 13.U && at <= 17.U,base ^ 165.U,base)
      assert(memory.io.data(8*i+7,8*i) === expected,"BANK_DATA_ROTATION_MASK_HOLD")
    }
  }
  when(cycle === 72.U) { printf("BANKED_BYTES_PASS unaligned_wrap_mask_continuous_hold=1\n") }
  io.finished := cycle >= 73.U
}
