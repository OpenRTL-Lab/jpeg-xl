// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl
import chisel3._
import chisel3.util._

/** One-cycle synchronous read responses, three registered output slots.
  * Read admission reserves the next response slot without using output.ready.
  * The RAM latency is unchanged; the extra register belongs to this consumer.
  */
class ReadStream extends Module {
  val io = IO(new Bundle {
    val begin = Flipped(Valid(UInt(20.W)))
    val read = Output(Bool()); val address = Output(UInt(20.W))
    val responseAddress = Output(UInt(20.W)); val data = Input(UInt(64.W))
    val output = Decoupled(new Stream)
  })
  val active = RegInit(false.B)
  val length = Reg(UInt(20.W)); val address = RegInit(0.U(20.W))
  val count = RegInit(0.U(2.W)); val pending = RegInit(false.B)
  val slots = Reg(Vec(3,new Stream))
  val responseAddress = Reg(UInt(20.W))
  val responseKeep = Reg(UInt(8.W)); val responseLast = Reg(Bool())
  val issue = active && count +& pending < 3.U
  val last = address+8.U >= length
  val remaining = (length-address)(3,0)
  io.read := issue; io.address := address; io.responseAddress := responseAddress
  io.output.valid := count =/= 0.U; io.output.bits := slots(0)
  val pop = io.output.fire
  val retained = count-pop
  pending := issue
  when(io.begin.valid) {
    assert(!active && !pending && count === 0.U,"READ_STREAM_BEGIN_BUSY")
    assert(io.begin.bits =/= 0.U,"READ_STREAM_EMPTY")
    length := io.begin.bits; address := 0.U; active := true.B
  }
  when(issue) {
    responseAddress := address
    responseKeep := Mux(last,((1.U(9.W) << remaining)-1.U)(7,0),255.U)
    responseLast := last
    address := address+8.U
    when(last) { active := false.B }
  }
  when(pop) { slots(0) := slots(1); slots(1) := slots(2) }
  when(pending) {
    assert(retained < 3.U,"READ_STREAM_OVERFLOW")
    for(i <- 0 until 3) {
      when(retained === i.U) {
        slots(i).data := io.data; slots(i).keep := responseKeep; slots(i).last := responseLast
      }
    }
  }
  count := retained+pending
  assert(count +& pending <= 3.U,"READ_STREAM_CREDIT")
}
