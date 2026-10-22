// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package jpegxl.soc

import org.chipsalliance.cde.config.{Parameters, Config, Field}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.subsystem.{BaseSubsystem, SBUS}
import freechips.rocketchip.tilelink._
import jpegxl.platform.{JxlTileLink, JxlParams}

case object JxlKey extends Field[Option[JxlParams]](None)
trait CanHaveJxl { this: BaseSubsystem =>
  val jpegxl = p(JxlKey).map { params =>
    val sbus = locateTLBusWrapper(SBUS)
    val encoder = LazyModule(new JxlTileLink(params))
    encoder.clockNode := sbus.fixedClockNode
    sbus.coupleTo("jpegxl-csr") {
      encoder.csrNode := TLBuffer() := TLFragmenter(4,sbus.blockBytes,holdFirstDeny=true) :=
        TLWidthWidget(sbus.beatBytes) := _
    }
    sbus.coupleFrom("jpegxl-dma") { _ := TLBuffer() := TLWidthWidget(64) := encoder.dmaNode }
    ibus.fromSync := encoder.intNode
    encoder
  }
}
