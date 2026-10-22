// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl
import chisel3._
import chisel3.util._

/** Full-file commit, byte-identical grammar, concurrent append/drain and no
  * group-to-file copy. */
class JxlEncoder extends Module {
  val io = IO(new NativeIO)
  val (idle :: validate :: validateRaw :: validateCount :: receive :: prepare :: prefix :: samples :: pad :: drain :: header ::
    headerPad :: headerDrain :: describe :: output :: finish :: Nil) = Enum(16)
  val state = RegInit(idle)
  val cmd = Reg(new Command)
  val status = RegInit(0.U(8.W))
  val inputCount = RegInit(0.U(32.W)); val outputCount = RegInit(0.U(32.W))
  val rawBytes = Reg(UInt(19.W)); val pixels = Reg(UInt(17.W))
  val pixelProduct = Reg(UInt(18.W)); val rawProduct = Reg(UInt(21.W))
  val invalidDimensions = Reg(Bool())
  val image = Module(new BankedBytes(262144)); val group = Module(new BankedBytes(524288))
  val headerData = Reg(Vec(32,UInt(8.W)))
  val headerBytes = Reg(UInt(6.W)); val groupBytes = Reg(UInt(20.W)); val fileBytes = Reg(UInt(20.W))
  val pool = RegInit(0.U(128.W)); val count = RegInit(0.U(8.W))
  val ptr = RegInit(0.U(20.W)); val field = RegInit(0.U(7.W))
  val channel = RegInit(0.U(3.W)); val pixel = RegInit(0.U(17.W)); val x = RegInit(0.U(9.W))
  val issued = RegInit(0.U(19.W)); val previous = Reg(UInt(8.W)); val previousRowFirst = Reg(UInt(8.W))
  val fetching = state === samples && issued < rawBytes
  val sampleValid = RegNext(fetching,false.B)
  val rowStart = RegEnable(x === 0.U,fetching); val planeStart = RegEnable(pixel === 0.U,fetching)
  val sampleLast = RegEnable(issued === rawBytes-1.U,fetching)
  val sampleAddress = RegInit(0.U(18.W))
  image.io.read := fetching; image.io.readAddress := sampleAddress
  image.io.write := io.input.fire && status === 0.U
  image.io.writeAddress := inputCount(17,0); image.io.writeData := io.input.bits.data
  image.io.writeKeep := io.input.bits.keep
  val groupPhase = state === prefix || state === samples || state === pad || state === drain
  val headPhase = state === header || state === headerPad || state === headerDrain
  val drainBytes = Mux(count >= 64.U,8.U(4.W),(count >> 3)(3,0))
  val drainBits = drainBytes << 3; val remaining = count-drainBits
  val appendN = WireDefault(0.U(6.W)); val appendV = WireDefault(0.U(32.W))
  group.io.write := groupPhase && drainBytes =/= 0.U
  group.io.writeAddress := ptr(18,0); group.io.writeData := pool(63,0)
  group.io.writeKeep := ((1.U(9.W) << drainBytes)-1.U)(7,0)
  when(groupPhase || headPhase) {
    val appendMask = ((1.U(33.W) << appendN)-1.U)(31,0)
    pool := (pool >> drainBits) | ((appendV & appendMask).pad(128) << remaining)
    count := remaining+appendN; ptr := ptr+drainBytes
    when(headPhase && drainBytes =/= 0.U) {
      for(i <- 0 until 8) {
        when(i.U < drainBytes) { headerData((ptr+i.U)(4,0)) := pool(8*i+7,8*i) }
      }
    }
  }
  val fields = FieldGrammar.group(cmd.predictor); val heads = FieldGrammar.header(cmd,groupBytes,count)
  val widths = VecInit(fields.map(_._1)); val values = VecInit(fields.map(_._2))
  val headWidths = VecInit(heads.map(_._1)); val headValues = VecInit(heads.map(_._2))
  val sample = image.io.data(7,0)
  val prediction = Mux(!cmd.predictor || planeStart,0.U,Mux(rowStart,previousRowFirst,previous))
  val delta = sample.zext-prediction.zext
  val u = Mux(delta < 0.S,((-delta).asUInt << 1)-1.U,delta.asUInt << 1)(8,0)
  val size = WireDefault(0.U(4.W))
  for(i <- 0 until 9) { when(u(i)) { size := (i+1).U } }
  val symbol = Mux(u < 4.U,u(4,0),4.U(5.W)+((size-3.U) << 1)+u(0))
  val short = symbol < 14.U; val codeN = Mux(short,4.U(4.W),5.U(4.W))
  val code = Mux(short,Reverse(symbol(3,0)).pad(5),Reverse((symbol+14.U)(4,0)))
  val extraN = Mux(u < 4.U,0.U(4.W),size-2.U)
  val extra = (u >> 1) & ((1.U(10.W) << extraN)-1.U)
  val stream = Module(new ReadStream)
  stream.io.begin.valid := io.desc.fire; stream.io.begin.bits := fileBytes
  val groupReadBase = RegInit(0.U(20.W))
  val responseBase = RegEnable(groupReadBase,stream.io.read)
  when(io.desc.fire) { groupReadBase := 0.U }
  when(stream.io.read) {
    val nextOffset = stream.io.address+8.U
    groupReadBase := Mux(nextOffset >= headerBytes,nextOffset-headerBytes,0.U)
  }
  group.io.read := stream.io.read; group.io.readAddress := groupReadBase(18,0)
  val outputBytes = Wire(Vec(8,UInt(8.W)))
  for(i <- 0 until 8) {
    val pos = stream.io.responseAddress+i.U; val groupLane = (pos-headerBytes-responseBase)(2,0)
    outputBytes(i) := Mux(pos >= fileBytes,0.U,Mux(pos < headerBytes,
      headerData(pos(4,0)),(group.io.data >> (groupLane << 3))(7,0)))
  }
  io.cmd.ready := state === idle; io.input.ready := state === receive
  io.desc.valid := state === describe
  io.desc.bits.imageWidth := cmd.imageWidth; io.desc.bits.imageHeight := cmd.imageHeight
  io.desc.bits.channels := cmd.channels; io.desc.bits.rawBytes := rawBytes
  io.desc.bits.fileBytes := fileBytes; io.desc.bits.tag := cmd.tag
  stream.io.data := outputBytes.asUInt
  io.output.valid := state === output && stream.io.output.valid
  io.output.bits := stream.io.output.bits
  stream.io.output.ready := state === output && io.output.ready
  io.done.valid := state === finish; io.done.bits.tag := cmd.tag; io.done.bits.status := status
  io.done.bits.inputCount := inputCount; io.done.bits.outputCount := outputCount
  switch(state) {
    is(idle) {
      when(io.cmd.fire) {
        cmd := io.cmd.bits; status := 0.U; inputCount := 0.U; outputCount := 0.U
        state := validate
      }
    }
    is(validate) {
      pixelProduct := cmd.imageWidth*cmd.imageHeight
      invalidDimensions := cmd.imageWidth === 0.U || cmd.imageWidth > 256.U ||
        cmd.imageHeight === 0.U || cmd.imageHeight > 256.U || cmd.channels === 0.U || cmd.channels > 4.U
      state := validateRaw
    }
    is(validateRaw) {
      rawProduct := pixelProduct*cmd.channels; pixels := pixelProduct; state := validateCount
    }
    is(validateCount) {
      rawBytes := rawProduct
      when(invalidDimensions || cmd.inputBytes =/= rawProduct) { status := 1.U; state := finish }
        .otherwise { state := receive }
    }
    is(receive) {
      when(io.input.fire) {
        val n = PopCount(io.input.bits.keep)
        val contiguous = io.input.bits.keep =/= 0.U && (io.input.bits.keep & (io.input.bits.keep+1.U)) === 0.U
        val bad = (!io.input.bits.last && io.input.bits.keep =/= 255.U) ||
          (io.input.bits.last && !contiguous) || inputCount+n > cmd.inputBytes ||
          (io.input.bits.last && inputCount+n =/= cmd.inputBytes)
        inputCount := inputCount+n
        when(bad) { status := 2.U }
        when(io.input.bits.last) {
          when(bad || status =/= 0.U) { state := finish }
          .otherwise { state := prepare }
        }
      }
    }
    is(prepare) {
      field := 0.U; count := 0.U; pool := 0.U; ptr := 0.U
      channel := 0.U; pixel := 0.U; x := 0.U; issued := 0.U; sampleAddress := 0.U
      previous := 0.U; previousRowFirst := 0.U; state := prefix
    }
    is(prefix) {
      appendN := widths(field); appendV := values(field)
      when(field === (fields.size-1).U) { state := samples }.otherwise { field := field+1.U }
    }
    is(samples) {
      when(fetching) {
        issued := issued+1.U
        when(pixel === pixels-1.U) {
          channel := channel+1.U; pixel := 0.U; x := 0.U; sampleAddress := channel+1.U
        }.otherwise {
          pixel := pixel+1.U; x := Mux(x === cmd.imageWidth-1.U,0.U,x+1.U)
          sampleAddress := sampleAddress+cmd.channels
        }
      }
      when(sampleValid) {
        appendN := codeN+extraN; appendV := code | (extra << codeN)
        previous := sample
        when(rowStart) { previousRowFirst := sample }
        when(sampleLast) { state := pad }
      }
    }
    is(pad) { appendN := (0.U(3.W)-count(2,0))(2,0); state := drain }
    is(drain) {
      when(count === 0.U) { groupBytes := ptr; ptr := 0.U; pool := 0.U; field := 0.U; state := header }
    }
    is(header) {
      appendN := headWidths(field); appendV := headValues(field)
      when(field === (heads.size-1).U) { state := headerPad }.otherwise { field := field+1.U }
    }
    is(headerPad) { appendN := (0.U(3.W)-count(2,0))(2,0); state := headerDrain }
    is(headerDrain) {
      when(count === 0.U) {
        fileBytes := ptr+groupBytes; headerBytes := ptr
        when(ptr+groupBytes > cmd.outputLimit || ptr+groupBytes > 524288.U) { status := 5.U; state := finish }
          .otherwise { state := describe }
      }
    }
    is(describe) { when(io.desc.fire) { state := output } }
    is(output) {
      when(io.output.fire) {
        outputCount := outputCount+PopCount(io.output.bits.keep)
        when(io.output.bits.last) { state := finish }
      }
    }
    is(finish) { when(io.done.fire) { state := idle } }
  }
}
