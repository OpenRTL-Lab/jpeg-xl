// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package jpegxl.platform

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec

/** Interface-only actual RTL tests. Synthetic result producers below are NOT a
  * codec model and cannot qualify the real encoder/SoC or official output. */
class JxlCsrSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "JxlCsr"
  it should "preserve ownership, snapshots, IRQs and deny invalid accesses" in {
    test(new JxlCsr).withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
      c.clock.setTimeout(10000)
      c.io.request.valid.poke(false.B); c.io.response.ready.poke(true.B)
      c.io.host.command.ready.poke(true.B); c.io.host.cancel.ready.poke(true.B)
      c.io.host.result.valid.poke(false.B); c.io.host.cancelAck.valid.poke(false.B)
      c.io.host.quiescent.poke(true.B)
      c.io.host.result.bits.tag.poke(7.U); c.io.host.result.bits.status.poke(0.U)
      c.io.host.result.bits.outputBytes.poke(123.U); c.io.host.result.bits.faultAddress.poke(0.U)
      c.io.host.result.bits.acceptedCycle.poke(BigInt("100000003",16).U)
      c.io.host.result.bits.firstFileWriteCycle.poke(BigInt("100000013",16).U)
      c.io.host.result.bits.retiredCycle.poke(BigInt("200000003",16).U)
      c.io.host.result.bits.readRequests.poke(3.U); c.io.host.result.bits.writeRequests.poke(2.U)
      c.io.host.cancelAck.bits.tag.poke(7.U); c.io.host.cancelAck.bits.found.poke(true.B)
      c.reset.poke(true.B); c.clock.step(3); c.reset.poke(false.B); c.clock.step()
      def access(address:Int,write:Boolean=false,data:BigInt=0,mask:Int=15,error:Boolean=false):BigInt = {
        c.io.request.bits.address.poke(address.U); c.io.request.bits.write.poke(write.B)
        c.io.request.bits.data.poke(data.U); c.io.request.bits.mask.poke(mask.U)
        c.io.request.valid.poke(true.B)
        var wait=0
        while(!c.io.request.ready.peek().litToBoolean && wait<100) { c.clock.step(); wait+=1 }
        assert(wait<100)
        c.clock.step(); c.io.request.valid.poke(false.B)
        c.io.response.valid.expect(true.B); c.io.response.bits.error.expect(error.B)
        val result=c.io.response.bits.data.peek().litValue
        c.clock.step(); result
      }
      assert(access(0)==BigInt("4a584c31",16)); assert(access(4)==0x10000)
      access(0x100,error=true); access(1,error=true); access(0x20,true,1,mask=1,error=true)
      access(0,true,1,error=true); access(0x60,error=true); access(0x58,true,1,error=true)
      access(0x24,true,512,error=true); assert(access(0x24)==0)
      access(0x10,true,3); access(0x10,true,4,error=true); assert(access(0x10)==3)
      access(0x20,true,7); access(0x24,true,256); access(0x28,true,9); access(0x2c,true,4)
      access(0x30,true,0x80000000L); access(0x34,true,1)
      access(0x38,true,0x80100000L); access(0x40,true,4096); access(0x44,true,65536); access(0x48,true,65536)
      c.io.host.command.ready.poke(false.B)
      c.io.request.bits.address.poke(0x4c.U); c.io.request.bits.write.poke(true.B)
      c.io.request.bits.data.poke(1.U); c.io.request.bits.mask.poke(15.U); c.io.request.valid.poke(true.B)
      for(_ <- 0 until 8) {
        c.io.request.ready.expect(false.B); c.io.host.command.valid.expect(true.B)
        c.io.host.command.bits.tag.expect(7.U); c.io.host.command.bits.imageWidth.expect(256.U)
        c.io.host.command.bits.source.expect(BigInt("180000000",16).U)
        c.clock.step()
      }
      c.io.host.command.ready.poke(true.B); c.clock.step(); c.io.request.valid.poke(false.B)
      c.io.response.bits.error.expect(false.B); c.clock.step()
      assert(access(0x0c)==1)
      // Shadow changes cannot change the descriptor already handed to the core.
      access(0x20,true,99)
      c.io.host.result.valid.poke(true.B); c.io.host.quiescent.poke(false.B)
      c.io.irq.expect(true.B); c.io.host.result.ready.expect(false.B)
      assert(access(0x60)==7); assert(access(0x74)==3); assert(access(0x78)==1)
      assert(access(0x84)==3); assert(access(0x88)==2)
      c.io.host.result.ready.expect(false.B); assert(access(0x0c)==1)
      access(0x58,true,1); c.io.host.result.valid.poke(false.B)
      assert(access(0x0c)==0); c.io.irq.expect(false.B)
      access(0x50,true,7); access(0x54,true,1)
      c.io.host.cancelAck.valid.poke(true.B); c.io.irq.expect(true.B)
      assert(access(0x98)==7 && access(0x9c)==1)
      c.io.host.cancelAck.ready.expect(false.B); access(0x5c,true,1)
      c.io.host.cancelAck.valid.poke(false.B); c.io.irq.expect(false.B)
    }
  }
}
