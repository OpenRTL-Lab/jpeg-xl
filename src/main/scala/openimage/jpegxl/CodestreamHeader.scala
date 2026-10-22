// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl
import chisel3._
import chisel3.util._

class ImageHeader extends Bundle {
  val imageWidth = UInt(9.W)
  val imageHeight = UInt(9.W)
  val channels = UInt(3.W)
  val sectionBytes = UInt(32.W)
}

/** Complete single-frame header and one-section TOC parser for the explicit
  * sRGB/gray profile. Equivalent size/extra-channel integer selectors are
  * decoded, not compared against a pre-computed header byte string.
  */
class CodestreamHeader extends Module {
  val io = IO(new Bundle {
    val begin = Flipped(Decoupled(UInt(32.W))) // actual complete file byte count
    val request = Decoupled(UInt(6.W))
    val response = Flipped(Decoupled(UInt(32.W)))
    val consumed = Input(UInt(32.W))
    val header = Decoupled(new ImageHeader)
    val failed = Output(Bool())
    val status = Output(UInt(3.W))
  })
  object S extends ChiselEnum {
    val idle, signature, smallFlag, heightSelector, heightValue, ratioValue, ratioCheck,
      widthSelector, widthValue, metadata, metadataNext, alphaSelector,
      alphaValue, alphaDefault, xyb, color, gray, grayNext, extensions,
      transformDefault, imagePad, imagePadCheck, frame, frameNext,
      framePad, framePadCheck, sectionSelector, sectionValue,
      sectionPad, sectionPadCheck, offer, bad, readRequest, readResponse = Value
  }
  val state = RegInit(S.idle); val next = Reg(S())
  val requestN = Reg(UInt(6.W)); val value = Reg(UInt(32.W))
  val fileBytes = Reg(UInt(32.W)); val small = Reg(Bool())
  val imageWidth = Reg(UInt(9.W)); val imageHeight = Reg(UInt(9.W))
  val ratioWidth = Reg(UInt(13.W))
  val channels = Reg(UInt(3.W)); val alpha = Reg(Bool())
  val index = Reg(UInt(5.W)); val base = Reg(UInt(32.W)); val section = Reg(UInt(32.W))
  val status = RegInit(0.U(3.W))
  io.begin.ready := state === S.idle || state === S.bad
  io.request.valid := state === S.readRequest
  io.request.bits := requestN
  io.response.ready := state === S.readResponse
  io.header.valid := state === S.offer
  io.header.bits.imageWidth := imageWidth
  io.header.bits.imageHeight := imageHeight
  io.header.bits.channels := channels
  io.header.bits.sectionBytes := section
  io.failed := state === S.bad; io.status := status
  def read(n: UInt, ret: S.Type): Unit = { requestN := n; next := ret; state := S.readRequest }
  def fail(code: Int): Unit = { status := code.U; state := S.bad }
  def padding(ret: S.Type): Unit = { read((0.U(3.W)-io.consumed(2,0))(2,0), ret) }
  val sizes = VecInit(Seq(9,13,18,30).map(_.U(6.W)))
  val metadataFields = Seq((1,0),(1,0),(1,0),(2,0),(1,0))
  val grayFields = Seq((1,0),(2,1),(2,1),(1,0),(2,2),(4,11),(2,1))
  // Negative expected value means the field is decoded separately.
  val frameWidths = Seq(1,2,1,2,1,2,2,2,2,1,2,2,1,2,1,1,2,2,2,1)
  val frameValues = Seq(0,0,1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,0,0,0)
  val frameN = WireDefault(VecInit(frameWidths.map(_.U(6.W)))(index))
  when((index === 6.U || index === 11.U) && !alpha) { frameN := 0.U }
  when(state === S.readRequest && io.request.fire) { state := S.readResponse }
  when(state === S.readResponse && io.response.fire) { value := io.response.bits; state := next }
  when(io.begin.fire) { fileBytes := io.begin.bits; status := 0.U; read(16.U,S.signature) }
  switch(state) {
    is(S.signature) {
      when(value =/= "h0aff".U) { fail(3) }.otherwise { read(1.U,S.smallFlag) }
    }
    is(S.smallFlag) {
      small := value(0)
      when(value(0)) { read(5.U,S.heightValue) }.otherwise { read(2.U,S.heightSelector) }
    }
    is(S.heightSelector) { read(sizes(value(1,0)),S.heightValue) }
    is(S.heightValue) {
      val h = Mux(small,(value+&1.U) << 3,value+&1.U)
      when(h === 0.U || h > 256.U) { fail(4) }
        .otherwise { imageHeight := h; read(3.U,S.ratioValue) }
    }
    is(S.ratioValue) {
      when(value === 0.U) {
        when(small) { read(5.U,S.widthValue) }.otherwise { read(2.U,S.widthSelector) }
      }.otherwise {
        val h = imageHeight
        val w = MuxLookup(value(2,0),h)(Seq(1.U -> h,2.U -> (h*12.U/10.U),3.U -> (h*4.U/3.U),
          4.U -> (h*3.U/2.U),5.U -> (h*16.U/9.U),6.U -> (h*5.U/4.U),7.U -> (h*2.U)))
        ratioWidth := w; state := S.ratioCheck
      }
    }
    is(S.ratioCheck) {
      when(ratioWidth === 0.U || ratioWidth > 256.U) { fail(4) }
        .otherwise { imageWidth := ratioWidth; index := 0.U; state := S.metadata }
    }
    is(S.widthSelector) { read(sizes(value(1,0)),S.widthValue) }
    is(S.widthValue) {
      val w = Mux(small,(value+&1.U) << 3,value+&1.U)
      when(w === 0.U || w > 256.U) { fail(4) }
        .otherwise { imageWidth := w; index := 0.U; state := S.metadata }
    }
    is(S.metadata) { read(VecInit(metadataFields.map(_._1.U(6.W)))(index(2,0)),S.metadataNext) }
    is(S.metadataNext) {
      when(index =/= 4.U && value =/= 0.U) { fail(4) }
      .elsewhen(index === 4.U) { read(2.U,S.alphaSelector) }
      .otherwise { index := index+1.U; state := S.metadata }
    }
    is(S.alphaSelector) {
      base := VecInit(Seq(0,1,2,1).map(_.U(32.W)))(value(1,0))
      read(VecInit(Seq(0,0,4,12).map(_.U(6.W)))(value(1,0)),S.alphaValue)
    }
    is(S.alphaValue) {
      val count = value+&base
      alpha := count === 1.U
      when(count > 1.U) { fail(4) }
      .elsewhen(count === 1.U) { read(1.U,S.alphaDefault) }
      .otherwise { read(1.U,S.xyb) }
    }
    is(S.alphaDefault) {
      when(value =/= 1.U) { fail(4) }.otherwise { read(1.U,S.xyb) }
    }
    is(S.xyb) { when(value =/= 0.U) { fail(4) }.otherwise { read(1.U,S.color) } }
    is(S.color) {
      channels := Mux(value(0),3.U,1.U)+&alpha.asUInt
      when(value(0)) { read(2.U,S.extensions) }
        .otherwise { index := 0.U; state := S.gray }
    }
    is(S.gray) { read(VecInit(grayFields.map(_._1.U(6.W)))(index(2,0)),S.grayNext) }
    is(S.grayNext) {
      when(value =/= VecInit(grayFields.map(_._2.U(32.W)))(index(2,0))) { fail(4) }
      .elsewhen(index === (grayFields.size-1).U) { read(2.U,S.extensions) }
      .otherwise { index := index+1.U; state := S.gray }
    }
    is(S.extensions) { when(value =/= 0.U) { fail(4) }.otherwise { read(1.U,S.transformDefault) } }
    is(S.transformDefault) { when(value =/= 1.U) { fail(4) }.otherwise { state := S.imagePad } }
    is(S.imagePad) { padding(S.imagePadCheck) }
    is(S.imagePadCheck) {
      when(value =/= 0.U) { fail(3) }.otherwise { index := 0.U; state := S.frame }
    }
    is(S.frame) { read(frameN,S.frameNext) }
    is(S.frameNext) {
      when(index === 7.U) {
        when(imageWidth > (128.U(12.W) << value(1,0)) || imageHeight > (128.U(12.W) << value(1,0))) { fail(4) }
          .otherwise { index := index+1.U; state := S.frame }
      }.elsewhen(value =/= VecInit(frameValues.map(_.U(32.W)))(index)) { fail(4) }
      .elsewhen(index === (frameWidths.size-1).U) { state := S.framePad }
      .otherwise { index := index+1.U; state := S.frame }
    }
    is(S.framePad) { padding(S.framePadCheck) }
    is(S.framePadCheck) { when(value =/= 0.U) { fail(3) }.otherwise { read(2.U,S.sectionSelector) } }
    is(S.sectionSelector) {
      base := VecInit(Seq(0,1024,17408,4211712).map(_.U(32.W)))(value(1,0))
      read(VecInit(Seq(10,14,22,30).map(_.U(6.W)))(value(1,0)),S.sectionValue)
    }
    is(S.sectionValue) {
      val size = value+&base
      when(size > 524288.U) { fail(3) }.otherwise { section := size; state := S.sectionPad }
    }
    is(S.sectionPad) { padding(S.sectionPadCheck) }
    is(S.sectionPadCheck) {
      when(value =/= 0.U || (io.consumed >> 3)+&section =/= fileBytes) { fail(3) }
        .otherwise { state := S.offer }
    }
    is(S.offer) { when(io.header.fire) { state := S.idle } }
  }
}
