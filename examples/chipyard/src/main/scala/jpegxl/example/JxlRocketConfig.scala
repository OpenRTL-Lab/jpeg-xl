// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
package jpegxl.example

import org.chipsalliance.cde.config.{Config, Parameters}
import freechips.rocketchip.subsystem._
import jpegxl.soc.{CanHaveJxl, JxlKey, WithJxl}

class JxlExampleSystem(implicit p: Parameters) extends chipyard.DigitalTop with CanHaveJxl
class WithJxlExampleSystem extends Config((site, here, up) => {
  case chipyard.BuildSystem => (p: Parameters) => new JxlExampleSystem()(p)
  case SystemBusKey => up(SystemBusKey, site).copy(beatBytes = 16)
  case MemoryBusKey => up(MemoryBusKey, site).copy(beatBytes = 64)
  // Upstream SimDRAM accepts at most 64-bit AXI, independently of MBUS width.
  case ExtMem => up(ExtMem, site).map(p => p.copy(master = p.master.copy(beatBytes = 8,
    base = BigInt("80000000", 16), size = BigInt(4) << 30)))
})

class JxlRocketConfig extends Config(
  new WithJxlExampleSystem ++ new WithJxl ++
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++ new chipyard.config.AbstractConfig)

object CheckJxlRocketConfig extends App {
  val p = new JxlRocketConfig
  require(p(JxlKey).nonEmpty)
  require(p(SystemBusKey).beatBytes == 16 && p(MemoryBusKey).beatBytes == 64)
  require(p(ExtMem).get.master.base == BigInt("80000000", 16))
  require(p(ExtMem).get.master.size == (BigInt(4) << 30))
  require(p(ExtMem).get.master.beatBytes == 8)
  require(p(TilesLocated(InSubsystem)).size == 1)
  println("JXL_EXAMPLE_CONFIG_PASS cores=1 sbus_bits=128 mbus_bits=512")
}
