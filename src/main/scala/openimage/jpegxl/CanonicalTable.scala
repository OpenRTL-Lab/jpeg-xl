// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl
import chisel3._
import chisel3.util._

/** Build a canonical prefix table from streamed lengths, then decode serially.
  * The sorted-symbol and length stores are explicit synchronous RAMs. A
  * singleton consumes no bits. This component does not parse JXL tree syntax.
  */
class CanonicalTable extends Module {
  val io = IO(new Bundle {
    val begin = Flipped(Decoupled(UInt(10.W))) // alphabet size, 1..512
    val length = Flipped(Decoupled(UInt(4.W)))
    val decode = Flipped(Decoupled(Bool()))
    val bit = Flipped(Decoupled(Bool())) // MSB of canonical code arrives first
    val symbol = Decoupled(UInt(9.W))
    val built = Output(Bool())
    val failed = Output(Bool())
    val fast18 = Output(Bool())
  })
  val (idle :: load :: selectLevel :: starts :: readLength :: sortLength :: ready :: bits ::
    readSymbol :: captureSymbol :: offer :: bad :: Nil) = Enum(12)
  val state = RegInit(idle)
  val alphabet = Reg(UInt(10.W))
  val index = RegInit(0.U(10.W))
  val active = RegInit(0.U(10.W))
  val singleton = Reg(UInt(9.W))
  val levels = RegInit(VecInit(Seq.fill(16)(0.U(10.W))))
  val firstCode = Reg(Vec(16, UInt(17.W)))
  val firstSymbol = Reg(Vec(16, UInt(10.W)))
  val cursor = Reg(Vec(16, UInt(10.W)))
  val level = Reg(UInt(4.W))
  val code = Reg(UInt(17.W))
  val offset = Reg(UInt(10.W))
  val previousCount = Reg(UInt(10.W)); val levelCount = Reg(UInt(10.W))
  val levelLimit = Reg(UInt(18.W))
  val decodeFirst = Reg(UInt(17.W)); val decodeCount = Reg(UInt(10.W)); val decodeBase = Reg(UInt(10.W))
  val lengths = SyncReadMem(512, UInt(4.W))
  val sorted = SyncReadMem(512, UInt(9.W))
  val matchAddress = Reg(UInt(9.W))
  val result = Reg(UInt(9.W))
  val fast18 = RegInit(false.B)
  val readN = lengths.read(index(8, 0), state === readLength)
  val foundSymbol = sorted.read(matchAddress, state === readSymbol)
  io.begin.ready := state === idle || state === ready || state === bad
  io.length.ready := state === load
  io.decode.ready := state === ready && !io.begin.valid
  io.bit.ready := state === bits
  io.symbol.valid := state === offer
  io.symbol.bits := result
  io.built := state === ready || state === bits || state === readSymbol || state === captureSymbol || state === offer
  io.failed := state === bad
  io.fast18 := io.built && fast18

  when(io.begin.fire) {
    alphabet := io.begin.bits
    index := 0.U; active := 0.U
    fast18 := io.begin.bits === 18.U
    for (i <- 0 until 16) { levels(i) := 0.U }
    state := Mux(io.begin.bits === 0.U || io.begin.bits > 512.U, bad, load)
  }
  switch(state) {
    is(load) {
      when(io.length.fire) {
        when(io.length.bits =/= Mux(index < 14.U,4.U,5.U)) { fast18 := false.B }
        lengths.write(index(8, 0), io.length.bits)
        when(io.length.bits =/= 0.U) {
          levels(io.length.bits) := levels(io.length.bits)+1.U
          active := active+1.U; singleton := index(8, 0)
        }
        when(index === alphabet-1.U) {
          level := 1.U; code := 0.U; offset := 0.U; state := selectLevel
        }.otherwise { index := index+1.U }
      }
    }
    is(selectLevel) {
      previousCount := levels(level-1.U); levelCount := levels(level)
      levelLimit := 1.U(18.W) << level; state := starts
    }
    is(starts) {
      val nextCode = (code+previousCount) << 1
      val end = nextCode+&levelCount
      firstCode(level) := nextCode
      firstSymbol(level) := offset; cursor(level) := offset
      offset := offset+levelCount; code := nextCode
      when(active === 0.U || (active =/= 1.U && end > levelLimit)) {
        state := bad
      }.elsewhen(level === 15.U) {
        when(active =/= 1.U && end =/= 32768.U) { state := bad }
          .otherwise { index := 0.U; state := readLength }
      }.otherwise { level := level+1.U; state := selectLevel }
    }
    is(readLength) { state := sortLength }
    is(sortLength) {
      when(readN =/= 0.U) {
        sorted.write(cursor(readN)(8, 0), index(8, 0))
        cursor(readN) := cursor(readN)+1.U
      }
      when(index === alphabet-1.U) { state := ready }
        .otherwise { index := index+1.U; state := readLength }
    }
    is(ready) {
      when(io.decode.fire) {
        when(active === 1.U) { result := singleton; state := offer }
          .otherwise {
            level := 1.U; code := 0.U; state := bits
            decodeFirst := firstCode(1); decodeCount := levels(1); decodeBase := firstSymbol(1)
          }
      }
    }
    is(bits) {
      when(io.bit.fire) {
        val incoming = (code << 1) | io.bit.bits.asUInt
        val delta = incoming-decodeFirst
        when(incoming >= decodeFirst && delta < decodeCount) {
          matchAddress := (decodeBase+delta)(8, 0)
          state := readSymbol
        }.elsewhen(level === 15.U) { state := bad }
          .otherwise {
            val nextLevel = (level+1.U)(3,0)
            level := nextLevel; code := incoming
            decodeFirst := firstCode(nextLevel); decodeCount := levels(nextLevel); decodeBase := firstSymbol(nextLevel)
          }
      }
    }
    is(readSymbol) { state := captureSymbol }
    is(captureSymbol) { result := foundSymbol; state := offer }
    is(offer) {
      when(io.symbol.fire) { state := ready }
    }
  }
}
