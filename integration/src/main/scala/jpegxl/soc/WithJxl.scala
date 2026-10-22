// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package jpegxl.soc

import org.chipsalliance.cde.config.Config
import jpegxl.platform.JxlParams

/** Enable the encoder on a subsystem that mixes in CanHaveJxl.
  * Does not choose a physical platform or construct the subsystem.
  */
class WithJxl extends Config((site, here, up) => {
  case JxlKey => Some(JxlParams())
})
