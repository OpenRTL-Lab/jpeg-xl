// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl
import chisel3._
import chisel3.util._

class Stream extends Bundle {
  val data = UInt(64.W)
  val keep = UInt(8.W)
  val last = Bool()
}
class Command extends Bundle {
  val imageWidth = UInt(9.W)
  val imageHeight = UInt(9.W)
  val channels = UInt(3.W)
  val predictor = Bool()
  val inputBytes = UInt(32.W)
  val outputLimit = UInt(32.W)
  val tag = UInt(32.W)
}
class Description extends Bundle {
  val imageWidth = UInt(9.W)
  val imageHeight = UInt(9.W)
  val channels = UInt(3.W)
  val rawBytes = UInt(32.W)
  val fileBytes = UInt(32.W)
  val tag = UInt(32.W)
}
class Completion extends Bundle {
  val tag = UInt(32.W)
  val status = UInt(8.W)
  val inputCount = UInt(32.W)
  val outputCount = UInt(32.W)
}
class NativeIO extends Bundle {
  val cmd = Flipped(Decoupled(new Command))
  val input = Flipped(Decoupled(new Stream))
  val desc = Decoupled(new Description)
  val output = Decoupled(new Stream)
  val done = Decoupled(new Completion)
}
