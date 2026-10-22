// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package jpegxl.platform

import chisel3._
import chisel3.util._

// Wire contract of the immutable Chisel-7 native delivery. This file is compiled
// with the baseline SoC's Chisel 6; it does not implement any codec algorithm.
class JxlCommand extends Bundle {
  val tag = UInt(32.W)
  val imageWidth = UInt(9.W); val imageHeight = UInt(9.W); val channels = UInt(3.W)
  val source = UInt(33.W); val destination = UInt(33.W)
  val sourceSpan = UInt(24.W); val destinationSpan = UInt(24.W); val outputLimit = UInt(24.W)
}
class JxlResult extends Bundle {
  val tag = UInt(32.W); val status = UInt(3.W); val outputBytes = UInt(24.W)
  val faultAddress = UInt(33.W)
  val acceptedCycle = UInt(64.W); val firstFileWriteCycle = UInt(64.W); val retiredCycle = UInt(64.W)
  val readRequests = UInt(13.W); val writeRequests = UInt(15.W)
}
class JxlCancelAck extends Bundle { val tag = UInt(32.W); val found = Bool() }
class JxlRead extends Bundle { val id = UInt(4.W); val address = UInt(33.W) }
class JxlWrite extends Bundle {
  val id = UInt(4.W); val address = UInt(33.W); val data = UInt(512.W)
}
class JxlReadResponse extends Bundle { val id = UInt(4.W); val data = UInt(512.W); val fault = Bool() }
class JxlWriteResponse extends Bundle { val id = UInt(4.W); val fault = Bool() }
class JxlMemory extends Bundle {
  val read = Decoupled(new JxlRead)
  val readResponse = Flipped(Decoupled(new JxlReadResponse))
  val write = Decoupled(new JxlWrite)
  val writeResponse = Flipped(Decoupled(new JxlWriteResponse))
}
class JxlHost extends Bundle {
  val command = Decoupled(new JxlCommand)
  val result = Flipped(Decoupled(new JxlResult))
  val cancel = Decoupled(UInt(32.W))
  val cancelAck = Flipped(Decoupled(new JxlCancelAck))
  val quiescent = Input(Bool())
}
class JxlNativeBlackBox extends BlackBox {
  override def desiredName = "FastNativeEncoder"
  val io = IO(new Bundle {
    val clock = Input(Clock()); val rst_n = Input(Bool())
    val io = new Bundle {
      val command = Flipped(Decoupled(new JxlCommand))
      val result = Decoupled(new JxlResult)
      val cancel = Flipped(Decoupled(UInt(32.W)))
      val cancelAck = Decoupled(new JxlCancelAck)
      val read = Decoupled(new JxlRead)
      val readResponse = Flipped(Decoupled(new JxlReadResponse))
      val write = Decoupled(new JxlWrite)
      val writeResponse = Flipped(Decoupled(new JxlWriteResponse))
      val quiescent = Output(Bool())
    }
  })
}

class JxlCsrRequest extends Bundle {
  val address = UInt(12.W); val write = Bool(); val data = UInt(32.W); val mask = UInt(4.W)
}
class JxlCsrResponse extends Bundle { val data = UInt(32.W); val error = Bool() }

/** MMIO snapshot front end. Native result.ready is asserted ONLY by RESULT_POP:
  * do not release buffer/tag ownership by silently moving results to a FIFO.
  * SUBMIT directly handshakes the native four-slot controller; no hidden queue
  * can reorder a cancellation ahead of a command not yet visible to the core.
  */
class JxlCsr extends Module {
  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new JxlCsrRequest))
    val response = Decoupled(new JxlCsrResponse)
    val host = new JxlHost
    val irq = Output(Bool())
  })
  val shadow = RegInit(0.U.asTypeOf(new JxlCommand))
  val cancelTag = RegInit(0.U(32.W))
  val irqEnable = RegInit(0.U(2.W))
  val outstanding = RegInit(0.U(3.W))
  val responseValid = RegInit(false.B)
  val response = Reg(new JxlCsrResponse)
  io.response.valid := responseValid; io.response.bits := response
  val a = io.request.bits.address
  val w = io.request.bits.write
  val data = io.request.bits.data
  val aligned = a(1, 0) === 0.U && io.request.bits.mask === 15.U
  val submit = w && a === 0x4c.U && data === 1.U && aligned
  val cancel = w && a === 0x54.U && data === 1.U && aligned
  val space = !responseValid || io.response.ready
  io.request.ready := space && Mux(submit, io.host.command.ready,
    Mux(cancel, io.host.cancel.ready, true.B))
  io.host.command.valid := io.request.valid && space && submit
  io.host.command.bits := shadow
  io.host.cancel.valid := io.request.valid && space && cancel
  io.host.cancel.bits := cancelTag
  io.host.result.ready := false.B; io.host.cancelAck.ready := false.B
  val pending = Cat(io.host.cancelAck.valid, io.host.result.valid)
  io.irq := (pending & irqEnable).orR
  when(io.response.fire) { responseValid := false.B }
  when(io.host.command.fire =/= io.host.result.fire) {
    outstanding := Mux(io.host.command.fire, outstanding + 1.U, outstanding - 1.U)
  }
  assert(outstanding <= 4.U)
  when(io.host.result.fire) { assert(outstanding =/= 0.U) }
  when(io.request.fire) {
    responseValid := true.B
    response.data := 0.U; response.error := !aligned
    when(aligned) {
      when(w) {
        response.error := false.B
        switch(a) {
          is(0x10.U) { when(!data(31, 2).orR) { irqEnable := data(1, 0) }; response.error := data(31, 2).orR }
          is(0x20.U) { shadow.tag := data }
          is(0x24.U) { when(!data(31, 9).orR) { shadow.imageWidth := data }; response.error := data(31, 9).orR }
          is(0x28.U) { when(!data(31, 9).orR) { shadow.imageHeight := data }; response.error := data(31, 9).orR }
          is(0x2c.U) { when(!data(31, 3).orR) { shadow.channels := data }; response.error := data(31, 3).orR }
          is(0x30.U) { shadow.source := Cat(shadow.source(32), data) }
          is(0x34.U) { when(!data(31, 1).orR) { shadow.source := Cat(data(0), shadow.source(31, 0)) }; response.error := data(31, 1).orR }
          is(0x38.U) { shadow.destination := Cat(shadow.destination(32), data) }
          is(0x3c.U) { when(!data(31, 1).orR) { shadow.destination := Cat(data(0), shadow.destination(31, 0)) }; response.error := data(31, 1).orR }
          is(0x40.U) { when(!data(31, 24).orR) { shadow.sourceSpan := data }; response.error := data(31, 24).orR }
          is(0x44.U) { when(!data(31, 24).orR) { shadow.destinationSpan := data }; response.error := data(31, 24).orR }
          is(0x48.U) { when(!data(31, 24).orR) { shadow.outputLimit := data }; response.error := data(31, 24).orR }
          is(0x4c.U) { response.error := data =/= 1.U }
          is(0x50.U) { cancelTag := data }
          is(0x54.U) { response.error := data =/= 1.U }
          is(0x58.U) { io.host.result.ready := data === 1.U; response.error := data =/= 1.U || !io.host.result.valid }
          is(0x5c.U) { io.host.cancelAck.ready := data === 1.U; response.error := data =/= 1.U || !io.host.cancelAck.valid }
        }
        val known = Seq(0x10,0x20,0x24,0x28,0x2c,0x30,0x34,0x38,0x3c,0x40,0x44,0x48,0x4c,0x50,0x54,0x58,0x5c)
          .map(x => a === x.U).reduce(_ || _)
        when(!known) { response.error := true.B }
      }.otherwise {
        response.error := false.B
        switch(a) {
          is(0x00.U) { response.data := "h4a584c31".U }
          is(0x04.U) { response.data := "h00010000".U }
          is(0x08.U) { response.data := Cat(io.host.quiescent, io.host.cancelAck.valid, io.host.result.valid, io.host.command.ready) }
          is(0x0c.U) { response.data := outstanding }
          is(0x10.U) { response.data := irqEnable }
          is(0x14.U) { response.data := pending }
          is(0x20.U) { response.data := shadow.tag }
          is(0x24.U) { response.data := shadow.imageWidth }
          is(0x28.U) { response.data := shadow.imageHeight }
          is(0x2c.U) { response.data := shadow.channels }
          is(0x30.U) { response.data := shadow.source(31,0) }
          is(0x34.U) { response.data := shadow.source(32) }
          is(0x38.U) { response.data := shadow.destination(31,0) }
          is(0x3c.U) { response.data := shadow.destination(32) }
          is(0x40.U) { response.data := shadow.sourceSpan }
          is(0x44.U) { response.data := shadow.destinationSpan }
          is(0x48.U) { response.data := shadow.outputLimit }
          is(0x50.U) { response.data := cancelTag }
          is(0x60.U) { response.data := io.host.result.bits.tag }
          is(0x64.U) { response.data := io.host.result.bits.status }
          is(0x68.U) { response.data := io.host.result.bits.outputBytes }
          is(0x6c.U) { response.data := io.host.result.bits.faultAddress(31,0) }
          is(0x70.U) { response.data := io.host.result.bits.faultAddress(32) }
          is(0x74.U) { response.data := io.host.result.bits.acceptedCycle(31,0) }
          is(0x78.U) { response.data := io.host.result.bits.acceptedCycle(63,32) }
          is(0x7c.U) { response.data := io.host.result.bits.firstFileWriteCycle(31,0) }
          is(0x80.U) { response.data := io.host.result.bits.firstFileWriteCycle(63,32) }
          is(0x84.U) { response.data := io.host.result.bits.retiredCycle(31,0) }
          is(0x88.U) { response.data := io.host.result.bits.retiredCycle(63,32) }
          is(0x8c.U) { response.data := io.host.result.bits.readRequests }
          is(0x90.U) { response.data := io.host.result.bits.writeRequests }
          is(0x98.U) { response.data := io.host.cancelAck.bits.tag }
          is(0x9c.U) { response.data := io.host.cancelAck.bits.found }
        }
        val known = Seq(0,4,8,12,16,20,0x20,0x24,0x28,0x2c,0x30,0x34,0x38,0x3c,0x40,0x44,0x48,0x50,
          0x60,0x64,0x68,0x6c,0x70,0x74,0x78,0x7c,0x80,0x84,0x88,0x8c,0x90,0x98,0x9c)
          .map(x => a === x.U).reduce(_ || _)
        when(!known || (a >= 0x60.U && a <= 0x90.U && !io.host.result.valid) ||
          (a >= 0x98.U && !io.host.cancelAck.valid)) { response.error := true.B; response.data := 0.U }
      }
    }
  }
}
