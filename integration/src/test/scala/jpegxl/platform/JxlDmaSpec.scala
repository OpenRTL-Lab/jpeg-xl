// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package jpegxl.platform

import chisel3._
import chisel3.experimental.UnlocatableSourceInfo
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._

object JxlTestEdge {
  def make = new TLEdgeOut(
    TLMasterPortParameters.v1(Seq(TLMasterParameters.v1(name="test",sourceId=IdRange(0,32)))),
    TLSlavePortParameters.v1(Seq(TLSlaveParameters.v1(
      address=AddressSet.misaligned(BigInt("80000000",16),BigInt(4)<<30),
      supportsGet=TransferSizes(64,64),supportsPutFull=TransferSizes(64,64),
      mayDenyGet=true,mayDenyPut=true)),beatBytes=64,minLatency=1),
    Parameters.empty,UnlocatableSourceInfo)
}
class JxlDmaSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "JxlDmaBridge"
  it should "map all 32 sources, handle reordering and reject non-DDR requests" in {
    test(new JxlDmaBridge(JxlTestEdge.make)).withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
      c.clock.setTimeout(20000)
      c.io.native.read.valid.poke(false.B); c.io.native.write.valid.poke(false.B)
      c.io.native.readResponse.ready.poke(true.B); c.io.native.writeResponse.ready.poke(true.B)
      c.io.tl.a.ready.poke(true.B); c.io.tl.d.valid.poke(false.B)
      // TL-UL has no BCE channels: touching the lazy accessors creates hardware.
      if (c.io.tl.params.hasBCE) {
        c.io.tl.b.valid.poke(false.B); c.io.tl.c.ready.poke(true.B); c.io.tl.e.ready.poke(true.B)
      }
      c.reset.poke(true.B); c.clock.step(3); c.reset.poke(false.B); c.clock.step()
      val requests=scala.collection.mutable.Map.empty[Int,(BigInt,BigInt)]
      def tick():Unit = {
        if(c.io.tl.a.valid.peek().litToBoolean && c.io.tl.a.ready.peek().litToBoolean) {
          val id=c.io.tl.a.bits.source.peek().litValue.toInt
          assert(!requests.contains(id))
          c.io.tl.a.bits.size.expect(6.U); c.io.tl.a.bits.mask.expect(((BigInt(1)<<64)-1).U)
          requests(id)=(c.io.tl.a.bits.address.peek().litValue,c.io.tl.a.bits.data.peek().litValue)
        }
        c.clock.step()
      }
      for(id <- 0 until 16) {
        c.io.native.read.bits.id.poke(id.U); c.io.native.read.bits.address.poke((0x80000000L+64*id).U)
        c.io.native.read.valid.poke(true.B)
        while(!c.io.native.read.ready.peek().litToBoolean) tick()
        tick(); c.io.native.read.valid.poke(false.B)
        c.io.native.write.bits.id.poke(id.U); c.io.native.write.bits.address.poke((0x100000000L+64*id).U)
        c.io.native.write.bits.data.poke((BigInt(1)<<id).U); c.io.native.write.valid.poke(true.B)
        while(!c.io.native.write.ready.peek().litToBoolean) tick()
        tick(); c.io.native.write.valid.poke(false.B)
      }
      for(_ <- 0 until 4) tick()
      assert(requests.keySet==Set.from(0 until 32))
      for(id <- 0 until 16) {
        assert(requests(id)._1==0x80000000L+64*id)
        assert(requests(16+id)==((BigInt(0x100000000L+64*id),BigInt(1)<<id)))
      }
      c.io.native.read.bits.id.poke(0.U); c.io.native.read.valid.poke(true.B)
      c.io.native.read.ready.expect(false.B); tick(); c.io.native.read.valid.poke(false.B)
      // D returns in reverse order; every fourth response is a real bus fault.
      for(source <- (0 until 32).reverse) {
        val write=source>=16; val id=source&15; val fault=source%4==0
        c.io.tl.d.bits.source.poke(source.U); c.io.tl.d.bits.opcode.poke((if(write)0 else 1).U)
        c.io.tl.d.bits.size.poke(6.U); c.io.tl.d.bits.param.poke(0.U); c.io.tl.d.bits.sink.poke(0.U)
        c.io.tl.d.bits.denied.poke(fault.B); c.io.tl.d.bits.corrupt.poke(false.B)
        c.io.tl.d.bits.data.poke((100+source).U); c.io.tl.d.valid.poke(true.B)
        while(!c.io.tl.d.ready.peek().litToBoolean) tick()
        tick(); c.io.tl.d.valid.poke(false.B)
        if(write) {
          while(!c.io.native.writeResponse.valid.peek().litToBoolean) tick()
          c.io.native.writeResponse.bits.id.expect(id.U); c.io.native.writeResponse.bits.fault.expect(fault.B)
        } else {
          while(!c.io.native.readResponse.valid.peek().litToBoolean) tick()
          c.io.native.readResponse.bits.id.expect(id.U); c.io.native.readResponse.bits.fault.expect(fault.B)
          c.io.native.readResponse.bits.data.expect((100+source).U)
        }
        tick()
      }
      val invalid=Seq(BigInt(0),BigInt("10040000",16),BigInt("7fffffc0",16),
        BigInt("180000000",16),BigInt("80000001",16))
      for(address <- invalid) {
        c.io.native.read.bits.id.poke(0.U); c.io.native.read.bits.address.poke(address.U)
        c.io.native.read.valid.poke(true.B)
        while(!c.io.native.read.ready.peek().litToBoolean) tick()
        tick(); c.io.native.read.valid.poke(false.B)
        while(!c.io.native.readResponse.valid.peek().litToBoolean) {
          c.io.tl.a.valid.expect(false.B); tick()
        }
        c.io.native.readResponse.bits.id.expect(0.U); c.io.native.readResponse.bits.fault.expect(true.B)
        c.io.native.readResponse.bits.data.expect(0.U); tick()
      }
    }
  }
}
