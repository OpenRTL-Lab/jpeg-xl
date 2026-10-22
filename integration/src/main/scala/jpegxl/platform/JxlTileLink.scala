// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package jpegxl.platform

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.prci._
import freechips.rocketchip.interrupts._

class JxlDmaBridge(edge: TLEdgeOut) extends Module {
  require(edge.bundle.dataBits == 512 && edge.bundle.addressBits <= 33)
  val io = IO(new Bundle { val native = Flipped(new JxlMemory); val tl = new TLBundle(edge.bundle) })
  val readBusy = RegInit(VecInit(Seq.fill(16)(false.B)))
  val writeBusy = RegInit(VecInit(Seq.fill(16)(false.B)))
  val localRead = Module(new Queue(new JxlReadResponse, 16))
  val localWrite = Module(new Queue(new JxlWriteResponse, 16))
  val readOut = Module(new Queue(new JxlReadResponse, 2))
  val writeOut = Module(new Queue(new JxlWriteResponse, 2))
  io.native.readResponse <> readOut.io.deq; io.native.writeResponse <> writeOut.io.deq
  val r = io.native.read.bits; val w = io.native.write.bits
  def inDDR(a: UInt): Bool = a >= "h080000000".U && a <= "h17fffffc0".U && !a(5,0).orR
  val ra = r.address(edge.bundle.addressBits-1,0); val wa = w.address(edge.bundle.addressBits-1,0)
  val rlegal = inDDR(r.address) && (r.address >> edge.bundle.addressBits) === 0.U && edge.manager.supportsGetSafe(ra, 6.U)
  val wlegal = inDDR(w.address) && (w.address >> edge.bundle.addressBits) === 0.U && edge.manager.supportsPutFullSafe(wa, 6.U)
  val arb = Module(new RRArbiter(new TLBundleA(edge.bundle), 2))
  val aQueue = Module(new Queue(new TLBundleA(edge.bundle), 2))
  aQueue.io.enq <> arb.io.out; io.tl.a <> aQueue.io.deq
  arb.io.in(0).valid := io.native.read.valid && !readBusy(r.id) && rlegal
  arb.io.in(0).bits := edge.Get(r.id, ra, 6.U)._2
  arb.io.in(1).valid := io.native.write.valid && !writeBusy(w.id) && wlegal
  arb.io.in(1).bits := edge.Put(Cat(1.U(1.W),w.id), wa, 6.U, w.data)._2
  io.native.read.ready := !readBusy(r.id) && Mux(rlegal, arb.io.in(0).ready, localRead.io.enq.ready)
  io.native.write.ready := !writeBusy(w.id) && Mux(wlegal, arb.io.in(1).ready, localWrite.io.enq.ready)
  localRead.io.enq.valid := io.native.read.valid && !readBusy(r.id) && !rlegal
  localRead.io.enq.bits.id := r.id; localRead.io.enq.bits.data := 0.U; localRead.io.enq.bits.fault := true.B
  localWrite.io.enq.valid := io.native.write.valid && !writeBusy(w.id) && !wlegal
  localWrite.io.enq.bits.id := w.id; localWrite.io.enq.bits.fault := true.B
  when(io.native.read.fire) { readBusy(r.id) := true.B }
  when(io.native.write.fire) { writeBusy(w.id) := true.B }
  when(io.native.readResponse.fire) { readBusy(io.native.readResponse.bits.id) := false.B }
  when(io.native.writeResponse.fire) { writeBusy(io.native.writeResponse.bits.id) := false.B }
  val rr = Module(new RRArbiter(new JxlReadResponse, 2))
  val wr = Module(new RRArbiter(new JxlWriteResponse, 2))
  rr.io.in(0) <> localRead.io.deq; wr.io.in(0) <> localWrite.io.deq
  readOut.io.enq <> rr.io.out; writeOut.io.enq <> wr.io.out
  val source = io.tl.d.bits.source
  val isWrite = source(4)
  val id = source(3,0)
  rr.io.in(1).valid := io.tl.d.valid && !isWrite
  rr.io.in(1).bits.id := id; rr.io.in(1).bits.data := io.tl.d.bits.data
  rr.io.in(1).bits.fault := io.tl.d.bits.denied || io.tl.d.bits.corrupt
  wr.io.in(1).valid := io.tl.d.valid && isWrite
  wr.io.in(1).bits.id := id; wr.io.in(1).bits.fault := io.tl.d.bits.denied || io.tl.d.bits.corrupt
  io.tl.d.ready := Mux(isWrite, wr.io.in(1).ready, rr.io.in(1).ready)
  when(io.tl.d.valid) {
    assert(source < 32.U)
    assert(Mux(isWrite, writeBusy(id), readBusy(id)))
    assert(io.tl.d.bits.size === 6.U)
    assert(io.tl.d.bits.opcode === Mux(isWrite, TLMessages.AccessAck, TLMessages.AccessAckData))
  }
  io.tl.b.ready := true.B
  io.tl.c.valid := false.B; io.tl.c.bits := 0.U.asTypeOf(io.tl.c.bits)
  io.tl.e.valid := false.B; io.tl.e.bits := 0.U.asTypeOf(io.tl.e.bits)
}

case class JxlParams(address: BigInt = 0x10040000L)
class JxlTileLink(params: JxlParams)(implicit p: Parameters)
    extends ClockSinkDomain(ClockSinkParameters())(p) {
  require((params.address & 4095) == 0)
  val device = new SimpleDevice("jpegxl", Seq("openrtl,jpegxl-fast-v1"))
  val csrNode = TLManagerNode(Seq(TLSlavePortParameters.v1(Seq(TLSlaveParameters.v1(
    address = Seq(AddressSet(params.address,4095)), resources = device.reg("control"),
    regionType = RegionType.GET_EFFECTS, executable = false, mayDenyGet = true, mayDenyPut = true,
    supportsGet = TransferSizes(1,4), supportsPutFull = TransferSizes(1,4), fifoId = Some(0))),
    beatBytes = 4, minLatency = 1)))
  val dmaNode = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(
    TLMasterParameters.v1(name="jpegxl-dma", sourceId=IdRange(0,32))))))
  val intNode = IntSourceNode(IntSourcePortSimple(num=1, resources=device.int))
  override lazy val module = new Impl {
    withClockAndReset(clock, reset) {
      val core = Module(new JxlNativeBlackBox)
      core.io.clock := clock; core.io.rst_n := !reset.asBool
      val control = Module(new JxlCsr)
      core.io.io.command <> control.io.host.command
      control.io.host.result <> core.io.io.result
      core.io.io.cancel <> control.io.host.cancel
      control.io.host.cancelAck <> core.io.io.cancelAck
      control.io.host.quiescent := core.io.io.quiescent
      val (dma, dmaEdge) = dmaNode.out(0)
      val bridge = Module(new JxlDmaBridge(dmaEdge))
      bridge.io.native.read <> core.io.io.read; core.io.io.readResponse <> bridge.io.native.readResponse
      bridge.io.native.write <> core.io.io.write; core.io.io.writeResponse <> bridge.io.native.writeResponse
      dma <> bridge.io.tl
      val (csr, edge) = csrNode.in(0)
      val active = RegInit(false.B); val rejected = RegInit(false.B)
      val meta = Reg(new TLBundleA(edge.bundle))
      val validAccess = csr.a.bits.size === 2.U && csr.a.bits.address(1,0) === 0.U &&
        (csr.a.bits.opcode === TLMessages.Get || csr.a.bits.opcode === TLMessages.PutFullData)
      control.io.request.valid := csr.a.valid && !active && validAccess
      control.io.request.bits.address := csr.a.bits.address(11,0)
      control.io.request.bits.write := csr.a.bits.opcode === TLMessages.PutFullData
      control.io.request.bits.data := csr.a.bits.data; control.io.request.bits.mask := csr.a.bits.mask
      csr.a.ready := !active && (!validAccess || control.io.request.ready)
      when(csr.a.fire) { meta := csr.a.bits; active := true.B; rejected := !validAccess }
      val error = rejected || control.io.response.bits.error
      csr.d.valid := active && (rejected || control.io.response.valid)
      csr.d.bits := Mux(meta.opcode === TLMessages.Get,
        edge.AccessAck(meta,Mux(rejected,0.U,control.io.response.bits.data),error,error),edge.AccessAck(meta,error))
      control.io.response.ready := active && !rejected && csr.d.ready
      when(csr.d.fire) { active := false.B }
      csr.b.valid := false.B; csr.b.bits := 0.U.asTypeOf(csr.b.bits)
      csr.c.ready := true.B; csr.e.ready := true.B
      intNode.out(0)._1(0) := control.io.irq
    }
  }
}
