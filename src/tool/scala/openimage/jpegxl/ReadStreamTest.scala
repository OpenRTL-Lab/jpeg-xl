// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl
import chisel3._
import chisel3.util._

/** Independent address/data scoreboard; includes real pending/stalled resets. */
class ReadStreamTest(negative: Boolean = false, checkResponseAddress: Boolean = false,
                     addressNegative: Boolean = false) extends Module {
  val io = IO(new Bundle { val finished = Output(Bool()) })
  val cycle = RegInit(0.U(16.W)); cycle := cycle+1.U
  assert(cycle < 20000.U,"READ_STREAM_TEST_TIMEOUT")
  val cancel = cycle === 2.U || cycle === 20.U
  val dut = withReset(reset.asBool || cancel) { Module(new ReadStream) }
  val lengths = Seq(1024,1024) ++ (1 to 65) ++ Seq.fill(32)(256)
  val task = RegInit(0.U(7.W)); val active = RegInit(false.B)
  val length = VecInit(lengths.map(_.U(20.W)))(task)
  val received = RegInit(0.U(20.W)); val issued = RegInit(0.U(20.W))
  val finished = RegInit(false.B)
  val noStall = task >= 67.U
  dut.io.begin.valid := !active && !finished && !cancel
  dut.io.begin.bits := length
  // First cancel occurs with responses pending; second with a full stalled queue.
  dut.io.output.ready := !cancel && Mux(task < 2.U,false.B,
    noStall || (cycle % 11.U >= 6.U))
  def pattern(offset: UInt): UInt = Cat((0 until 8).reverse.map(i =>
    ((offset+i.U)*37.U+11.U+task)(7,0)))
  if(checkResponseAddress) {
    // E consumes this registered address when joining header/group responses.
    dut.io.data := pattern(dut.io.responseAddress)
    val responseValid = RegNext(dut.io.read,false.B)
    val wantedAddress = RegEnable(dut.io.address,dut.io.read)
    val previousCancel = RegNext(cancel,false.B)
    when(responseValid && !cancel && !previousCancel) {
      if(addressNegative) {
        assert(dut.io.responseAddress === (wantedAddress ^ 8.U),"NEGATIVE_READ_ADDRESS_EXPECTED")
      } else {
        assert(dut.io.responseAddress === wantedAddress,"READ_STREAM_RESPONSE_ADDRESS")
      }
    }
  } else {
    dut.io.data := RegEnable(pattern(dut.io.address),dut.io.read)
  }
  when(dut.io.begin.valid) { active := true.B; received := 0.U; issued := 0.U }
  when(dut.io.read) {
    assert(active && dut.io.address === issued,"READ_STREAM_ISSUE_ADDRESS")
    issued := issued+8.U
  }
  val previousFire = RegNext(dut.io.output.fire,false.B)
  val previousLast = RegNext(dut.io.output.bits.last)
  when(active && noStall && previousFire && !previousLast) {
    assert(dut.io.output.fire,"READ_STREAM_STEADY_THROUGHPUT")
  }
  when(dut.io.output.fire) {
    if(negative) {
      assert(dut.io.output.bits.data === (pattern(received) ^ 1.U),"NEGATIVE_READ_STREAM_EXPECTED")
    } else {
      assert(active && dut.io.output.bits.data === pattern(received),"READ_STREAM_DATA_ORDER")
    }
    val last = received+8.U >= length
    val keep = Mux(last,((1.U(9.W) << (length-received)(3,0))-1.U)(7,0),255.U)
    assert(dut.io.output.bits.last === last && dut.io.output.bits.keep === keep,"READ_STREAM_METADATA")
    received := received+8.U
    when(last) {
      active := false.B
      when(task === (lengths.size-1).U) {
        finished := true.B
        printf("READ_STREAM_PASS tails65_burst32_stalls_pending_reset=1\n")
        if(checkResponseAddress) { printf("READ_ADDRESS_PASS e_variant_response_alignment=1\n") }
      }.otherwise { task := task+1.U }
    }
  }
  val stalled = RegNext(dut.io.output.valid && !dut.io.output.ready && !cancel,false.B)
  val previous = RegNext(dut.io.output.bits.asUInt)
  when(stalled && !cancel) {
    assert(dut.io.output.valid && dut.io.output.bits.asUInt === previous,"READ_STREAM_STABLE")
  }
  when(cancel) { task := task+1.U; active := false.B; received := 0.U; issued := 0.U }
  io.finished := finished
}
