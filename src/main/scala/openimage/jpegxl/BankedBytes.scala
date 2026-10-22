// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl
import chisel3._
import chisel3.util._

/** Eight consecutive bytes per synchronous access; unaligned bank rotation. */
class BankedBytes(bytes: Int) extends Module {
  require(bytes >= 8 && isPow2(bytes))
  val width = log2Ceil(bytes)
  val io = IO(new Bundle {
    val write = Input(Bool()); val writeAddress = Input(UInt(width.W))
    val writeData = Input(UInt(64.W)); val writeKeep = Input(UInt(8.W))
    val read = Input(Bool()); val readAddress = Input(UInt(width.W))
    val data = Output(UInt(64.W))
  })
  val readLane = RegEnable(io.readAddress(2,0),io.read)
  val bankData = Wire(Vec(8,UInt(8.W)))
  for(i <- 0 until 8) {
    val bank = SyncReadMem(bytes/8,UInt(8.W))
    val wl = (i.U(3.W)-io.writeAddress(2,0))(2,0)
    val wr = (io.writeAddress >> 3)+&(i.U < io.writeAddress(2,0))
    when(io.write && io.writeKeep(wl)) {
      bank.write(wr(width-4,0),(io.writeData >> (wl << 3))(7,0))
    }
    val rr = (io.readAddress >> 3)+&(i.U < io.readAddress(2,0))
    bankData(i) := bank.read(rr(width-4,0),io.read)
  }
  val response = Cat((0 until 8).reverse.map(i => bankData((readLane+i.U)(2,0))))
  // SyncReadMem explicitly makes disabled-read output undefined. Preserve the
  // previous response without adding a cycle to continuous one-beat reads.
  val responding = RegNext(io.read,false.B)
  val heldResponse = RegEnable(response,responding)
  io.data := Mux(responding,response,heldResponse)
}
