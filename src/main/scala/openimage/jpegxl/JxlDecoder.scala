// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl
import chisel3._
import chisel3.util._

/** Functional-first bounded full-file decoder. Input, signed planes and output
  * are separate synchronous memories. Output is committed only after parsing,
  * entropy tail, arithmetic, transform and capacity checks all succeed.
  */
class JxlDecoder extends Module {
  val io = IO(new NativeIO)
  object S extends ChiselEnum {
    val idle, validate, receive, ingest, parseStart, headerBegin, headerWait,
      dcQuant, globalFlag, treeBegin, treeWait, entropyBegin, entropyWait,
      useGlobal, wpDefault, transformCount, transformExtra, transformType,
      rctStart, rctStartExtra, rctType, rctTypeExtra, chooseTree,
      planeBegin, leafWait, northRead, northWait, sampleBegin, sampleWait,
      tailPad, tailCheck, colorRead, colorWait, colorCompute, colorWrite,
      describe, outputRead, outputWait, outputOffer, finish, readRequest, readResponse,
      fastBegin, fastSamples, fastTail = Value
  }
  val state = RegInit(S.idle); val returnState = Reg(S())
  val cmd = Reg(new Command); val status = RegInit(0.U(8.W))
  val inputCount = RegInit(0.U(32.W)); val outputCount = RegInit(0.U(32.W))
  val file = Module(new BankedBytes(524288))
  val planes = SyncReadMem(262144,SInt(11.W))
  val image = Module(new BankedBytes(262144))
  val beat = Reg(new Stream); val lane = Reg(UInt(3.W)); val ingestPtr = RegInit(0.U(20.W))
  val feeder = RegInit(0.U(2.W)); val feedPtr = RegInit(0.U(20.W)); val feedByte = Reg(UInt(8.W))
  val parsing = RegInit(false.B)
  val childReset = reset.asBool || state === S.parseStart
  val reader = withReset(childReset) { Module(new BitReader) }
  val header = withReset(childReset) { Module(new CodestreamHeader) }
  val entropy = withReset(childReset) { Module(new ModularEntropy) }
  val tree = withReset(childReset) { Module(new MATree) }
  val dimensions = Reg(new ImageHeader)
  val pixels = Reg(UInt(17.W)); val rawBytes = Reg(UInt(19.W))
  val globalTree = Reg(Bool()); val useGlobal = Reg(Bool()); val afterGlobal = Reg(Bool())
  val rct = RegInit(false.B); val integerBase = Reg(UInt(32.W))
  val requestN = Reg(UInt(6.W)); val value = Reg(UInt(32.W))
  val channel = Reg(UInt(3.W)); val pixel = Reg(UInt(17.W))
  val x = Reg(UInt(9.W)); val y = Reg(UInt(9.W))
  val west = Reg(SInt(11.W)); val previousNorth = Reg(SInt(11.W))
  val prediction = Reg(SInt(14.W)); val leaf = Reg(new LeafPrediction)
  val predictWest = Reg(SInt(11.W)); val predictNorth = Reg(SInt(11.W)); val predictNorthWest = Reg(SInt(11.W))
  val color = Reg(Vec(4,SInt(11.W))); val converted = Reg(Vec(4,UInt(8.W)))
  val outAddress = RegInit(0.U(19.W)); val outLane = Reg(UInt(3.W))
  val outBeat = Reg(UInt(64.W)); val outKeep = Reg(UInt(8.W)); val outLast = Reg(Bool())
  val headerActive = state === S.headerBegin || state === S.headerWait
  val treeActive = state === S.treeBegin || state === S.treeWait
  val entropyActive = treeActive || state === S.entropyBegin || state === S.entropyWait ||
    state === S.planeBegin || state === S.leafWait || state === S.northRead || state === S.northWait ||
    state === S.sampleBegin || state === S.sampleWait
  val fastPool = RegInit(0.U(128.W)); val fastAvailable = RegInit(0.U(8.W))
  val fastPtr = Reg(UInt(20.W)); val fastConsumed = Reg(UInt(32.W))
  val fastSkip = Reg(UInt(3.W)); val fastSkipPending = Reg(UInt(3.W))
  val fastBytesPending = Reg(UInt(4.W)); val fastPending = RegInit(false.B)
  val fastReadQueued = RegInit(false.B)
  val fastWest = Reg(UInt(8.W)); val fastRowFirst = Reg(UInt(8.W))
  val fastImageAddress = RegInit(0.U(18.W))
  val fastPredictor = Reg(Bool())
  val fastActive = state === S.fastSamples || state === S.fastTail
  val fastIssue = fastActive && !fastPending && !fastReadQueued && fastAvailable <= 64.U && fastPtr < inputCount
  val bytesToFetch = Mux(inputCount-fastPtr >= 8.U,8.U(4.W),(inputCount-fastPtr)(3,0))
  val fileByte = file.io.data(7,0)
  val fileRequest = fastIssue || (parsing && feeder === 0.U && feedPtr < cmd.inputBytes)
  file.io.read := RegNext(fileRequest,false.B)
  file.io.readAddress := RegEnable(Mux(fastActive,fastPtr(18,0),feedPtr(18,0)),fileRequest)
  val inputWrite = io.input.fire && status === 0.U
  file.io.write := RegNext(inputWrite,false.B)
  file.io.writeAddress := RegEnable(inputCount(18,0),inputWrite)
  file.io.writeData := RegEnable(io.input.bits.data,inputWrite)
  file.io.writeKeep := RegEnable(io.input.bits.keep,inputWrite)
  fastReadQueued := fastIssue; fastPending := fastReadQueued
  when(fastIssue) {
    fastPtr := fastPtr+bytesToFetch; fastSkipPending := fastSkip
    fastSkip := 0.U; fastBytesPending := bytesToFetch
  }
  val fastTake = WireDefault(0.U(5.W))
  val incomingBits = (fastBytesPending << 3)-fastSkipPending
  val incomingMask = ((1.U(129.W) << incomingBits)-1.U)(127,0)
  when(fastActive) {
    fastPool := (fastPool >> fastTake) | Mux(fastPending,
      (((file.io.data.pad(128) >> fastSkipPending) & incomingMask) << (fastAvailable-fastTake)),0.U)
    fastAvailable := fastAvailable-fastTake+Mux(fastPending,incomingBits,0.U)
  }
  reader.io.clear := state === S.parseStart
  reader.io.input.valid := parsing && feeder === 3.U
  reader.io.input.bits.data := feedByte
  reader.io.input.bits.last := feedPtr === cmd.inputBytes-1.U
  when(parsing) {
    when(feeder === 0.U && feedPtr < cmd.inputBytes) { feeder := 1.U }
    when(feeder === 1.U) { feeder := 2.U }
    when(feeder === 2.U) { feedByte := fileByte; feeder := 3.U }
    when(reader.io.input.fire) { feedPtr := feedPtr+1.U; feeder := 0.U }
  }
  reader.io.request.valid := Mux(headerActive,header.io.request.valid,
    Mux(entropyActive,entropy.io.request.valid,state === S.readRequest))
  reader.io.request.bits := Mux(headerActive,header.io.request.bits,
    Mux(entropyActive,entropy.io.request.bits,requestN))
  reader.io.response.ready := Mux(headerActive,header.io.response.ready,
    Mux(entropyActive,entropy.io.response.ready,state === S.readResponse))
  header.io.request.ready := headerActive && reader.io.request.ready
  header.io.response.valid := headerActive && reader.io.response.valid
  header.io.response.bits := reader.io.response.bits
  entropy.io.request.ready := entropyActive && reader.io.request.ready
  entropy.io.response.valid := entropyActive && reader.io.response.valid
  entropy.io.response.bits := reader.io.response.bits
  header.io.begin.valid := state === S.headerBegin
  header.io.begin.bits := inputCount
  header.io.consumed := reader.io.consumed
  header.io.header.ready := state === S.headerWait
  tree.io.begin.valid := state === S.treeBegin
  tree.io.begin.bits := true.B
  tree.io.codeBegin.ready := treeActive && entropy.io.begin.ready
  tree.io.codeContext.ready := treeActive && entropy.io.context.ready
  tree.io.codeValue.valid := treeActive && entropy.io.value.valid
  tree.io.codeValue.bits := entropy.io.value.bits
  tree.io.codeReady := entropy.io.ready
  tree.io.codeRunRemaining := entropy.io.runRemaining
  tree.io.codeFailed := entropy.io.failed
  tree.io.codeStatus := entropy.io.status
  tree.io.lookup.valid := state === S.planeBegin
  tree.io.lookup.bits := channel
  tree.io.leaf.ready := state === S.leafWait
  entropy.io.begin.valid := Mux(treeActive,tree.io.codeBegin.valid,state === S.entropyBegin)
  entropy.io.begin.bits := Mux(treeActive,tree.io.codeBegin.bits,tree.io.leaves)
  entropy.io.context.valid := Mux(treeActive,tree.io.codeContext.valid,state === S.sampleBegin)
  entropy.io.context.bits := Mux(treeActive,tree.io.codeContext.bits,leaf.context)
  entropy.io.value.ready := Mux(treeActive,tree.io.codeValue.ready,state === S.sampleWait)
  val samplePlaneAddress = RegInit(0.U(18.W)); val colorPlaneAddress = RegInit(0.U(18.W))
  val planeAddress = Mux(state === S.colorRead,colorPlaneAddress,samplePlaneAddress)
  val planeReadAddress = Mux(state === S.northRead,planeAddress-dimensions.imageWidth,planeAddress)
  val planeRead = state === S.northRead || state === S.colorRead
  val planeWrite = WireDefault(false.B); val planeData = WireDefault(0.S(11.W))
  val planeWriteValid = RegNext(planeWrite,false.B)
  val planeWriteAddress = RegEnable(samplePlaneAddress,planeWrite)
  val planeWriteData = RegEnable(planeData,planeWrite)
  when(planeWriteValid) { planes.write(planeWriteAddress,planeWriteData) }
  val planeMemoryData = planes.read(planeReadAddress(17,0),planeRead)
  // Preserve the previous logical write-before-next-read ordering after staging
  // the macro write command; width=1 can read that write on the same edge.
  val planeForward = RegNext(planeRead && planeWriteValid && planeReadAddress(17,0) === planeWriteAddress,false.B)
  val planeForwardData = RegEnable(planeWriteData,planeRead && planeWriteValid)
  val planeByte = Mux(planeForward,planeForwardData,planeMemoryData)
  val stream = Module(new ReadStream)
  stream.io.begin.valid := io.desc.fire; stream.io.begin.bits := rawBytes
  stream.io.data := image.io.data
  image.io.read := stream.io.read; image.io.readAddress := stream.io.address(17,0)
  val imageWrite = WireDefault(false.B); val imageAddress = WireDefault(0.U(18.W))
  val imageData = WireDefault(0.U(64.W)); val imageKeep = WireDefault(0.U(8.W))
  image.io.write := RegNext(imageWrite,false.B)
  image.io.writeAddress := RegEnable(imageAddress,imageWrite)
  image.io.writeData := RegEnable(imageData,imageWrite)
  image.io.writeKeep := RegEnable(imageKeep,imageWrite)

  io.cmd.ready := state === S.idle
  io.input.ready := state === S.receive
  io.desc.valid := state === S.describe
  io.desc.bits.imageWidth := dimensions.imageWidth
  io.desc.bits.imageHeight := dimensions.imageHeight
  io.desc.bits.channels := dimensions.channels
  io.desc.bits.rawBytes := rawBytes; io.desc.bits.fileBytes := inputCount; io.desc.bits.tag := cmd.tag
  io.output.valid := state === S.outputOffer && stream.io.output.valid
  io.output.bits := stream.io.output.bits
  stream.io.output.ready := state === S.outputOffer && io.output.ready
  io.done.valid := state === S.finish
  io.done.bits.tag := cmd.tag; io.done.bits.status := status
  io.done.bits.inputCount := inputCount; io.done.bits.outputCount := outputCount
  def read(n: UInt, ret: S.Type): Unit = { requestN := n; returnState := ret; state := S.readRequest }
  def fail(code: UInt): Unit = { status := code; state := S.finish; parsing := false.B }
  when(state === S.readRequest && reader.io.request.fire) { state := S.readResponse }
  when(state === S.readResponse && reader.io.response.fire) { value := reader.io.response.bits; state := returnState }
  switch(state) {
    is(S.idle) {
      when(io.cmd.fire) {
        cmd := io.cmd.bits; status := 0.U; inputCount := 0.U; outputCount := 0.U
        ingestPtr := 0.U; parsing := false.B; state := S.validate
        fastPending := false.B; fastReadQueued := false.B
      }
    }
    is(S.validate) {
      when(cmd.inputBytes === 0.U || cmd.inputBytes > 524288.U) { fail(1.U) }
        .otherwise { state := S.receive }
    }
    is(S.receive) {
      when(io.input.fire) {
        val n = PopCount(io.input.bits.keep)
        inputCount := inputCount+n
        val contiguous = io.input.bits.keep =/= 0.U && (io.input.bits.keep & (io.input.bits.keep+1.U)) === 0.U
        val bad = (!io.input.bits.last && io.input.bits.keep =/= 255.U) ||
          (io.input.bits.last && !contiguous) || inputCount+n > cmd.inputBytes ||
          (io.input.bits.last && inputCount+n =/= cmd.inputBytes)
        when(bad) { status := 2.U }
        when(io.input.bits.last) { state := Mux(status === 0.U && !bad,S.ingest,S.finish) }
      }
    }
    is(S.ingest) { state := S.parseStart }
    is(S.parseStart) {
      parsing := true.B; feeder := 0.U; feedPtr := 0.U; rct := false.B; state := S.headerBegin
      samplePlaneAddress := 0.U
    }
    is(S.headerBegin) { when(header.io.begin.fire) { state := S.headerWait } }
    is(S.headerWait) {
      when(header.io.header.fire) {
        dimensions := header.io.header.bits
        val count = header.io.header.bits.imageWidth*header.io.header.bits.imageHeight
        val raw = count*header.io.header.bits.channels
        pixels := count; rawBytes := raw
        when(raw > cmd.outputLimit) { fail(5.U) }.otherwise { read(1.U,S.dcQuant) }
      }
      when(header.io.failed) { fail(header.io.status) }
    }
    is(S.dcQuant) { when(value =/= 1.U) { fail(4.U) }.otherwise { read(1.U,S.globalFlag) } }
    is(S.globalFlag) {
      globalTree := value(0)
      when(value(0)) { afterGlobal := true.B; state := S.treeBegin }
        .otherwise { read(1.U,S.useGlobal) }
    }
    is(S.treeBegin) { when(tree.io.begin.fire) { state := S.treeWait } }
    is(S.treeWait) {
      when(tree.io.failed) { fail(tree.io.status) }
      .elsewhen(tree.io.done) { state := S.entropyBegin }
    }
    is(S.entropyBegin) { when(entropy.io.begin.fire) { state := S.entropyWait } }
    is(S.entropyWait) {
      when(entropy.io.ready) {
        when(afterGlobal) { read(1.U,S.useGlobal) }
          .otherwise { channel := 0.U; state := S.planeBegin }
      }
    }
    is(S.useGlobal) { useGlobal := value(0); read(1.U,S.wpDefault) }
    is(S.wpDefault) { when(value =/= 1.U) { fail(4.U) }.otherwise { read(2.U,S.transformCount) } }
    is(S.transformCount) {
      integerBase := VecInit(Seq(0,1,2,18).map(_.U(32.W)))(value(1,0))
      read(VecInit(Seq(0,0,4,8).map(_.U(6.W)))(value(1,0)),S.transformExtra)
    }
    is(S.transformExtra) {
      val n = value+&integerBase
      when(n === 0.U) { state := S.chooseTree }
        .elsewhen(n === 1.U && dimensions.channels >= 3.U) { read(2.U,S.transformType) }
        .otherwise { fail(4.U) }
    }
    is(S.transformType) { when(value =/= 0.U) { fail(4.U) }.otherwise { read(2.U,S.rctStart) } }
    is(S.rctStart) {
      integerBase := VecInit(Seq(0,8,72,1096).map(_.U(32.W)))(value(1,0))
      read(VecInit(Seq(3,6,10,13).map(_.U(6.W)))(value(1,0)),S.rctStartExtra)
    }
    is(S.rctStartExtra) {
      when(value+&integerBase =/= 0.U) { fail(4.U) }.otherwise { read(2.U,S.rctType) }
    }
    is(S.rctType) {
      integerBase := VecInit(Seq(6,0,2,10).map(_.U(32.W)))(value(1,0))
      read(VecInit(Seq(0,2,4,6).map(_.U(6.W)))(value(1,0)),S.rctTypeExtra)
    }
    is(S.rctTypeExtra) {
      val kind = value+&integerBase
      when(kind =/= 0.U && kind =/= 6.U) { fail(4.U) }
        .otherwise { rct := kind === 6.U; state := S.chooseTree }
    }
    is(S.chooseTree) {
      when(useGlobal) {
        when(!globalTree) { fail(3.U) }.otherwise { channel := 0.U; state := S.planeBegin }
      }.otherwise { afterGlobal := false.B; state := S.treeBegin }
    }
    is(S.planeBegin) { when(tree.io.lookup.fire) { state := S.leafWait } }
    is(S.leafWait) {
      when(tree.io.leaf.fire) {
        leaf := tree.io.leaf.bits; pixel := 0.U; x := 0.U; y := 0.U
        west := 0.S; previousNorth := 0.S
        when(channel === 0.U && tree.io.leaves === 1.U && !rct && entropy.io.fast18 &&
          tree.io.leaf.bits.predictor <= 1.U) {
          fastPredictor := tree.io.leaf.bits.predictor(0); parsing := false.B; state := S.fastBegin
        }.otherwise { state := S.northRead }
      }
    }
    is(S.northRead) { state := S.northWait }
    is(S.northWait) {
      val w = Mux(x === 0.U,Mux(y === 0.U,0.S,planeByte),west)
      val n = Mux(y === 0.U,w,planeByte)
      val nw = Mux(x =/= 0.U && y =/= 0.U,previousNorth,Mux(y =/= 0.U,n,w))
      predictWest := w; predictNorth := n; predictNorthWest := nw
      previousNorth := n; state := S.sampleBegin
    }
    is(S.sampleBegin) {
      val w = predictWest; val n = predictNorth; val nw = predictNorthWest
      val lo = Mux(w < n,w,n); val hi = Mux(w > n,w,n)
      val gradient = (w +& n)-&nw
      val clamped = Mux(gradient < lo,lo,Mux(gradient > hi,hi,gradient))
      prediction := MuxLookup(leaf.predictor,0.S(14.W))(Seq(1.U -> w,2.U -> n,5.U -> clamped))
      when(entropy.io.context.fire) { state := S.sampleWait }
    }
    is(S.sampleWait) {
      when(entropy.io.value.fire) {
        val u = entropy.io.value.bits
        val magnitude = Cat(0.U(1.W),u >> 1).asSInt
        val residual = Mux(u(0),-magnitude-1.S,magnitude)
        val sample = prediction+&residual
        when(sample < (-1024).S || sample > 1023.S) { fail(3.U) }
        .otherwise {
          planeWrite := true.B; planeData := sample.asUInt(10,0).asSInt
          samplePlaneAddress := samplePlaneAddress+1.U
          west := sample.asUInt(10,0).asSInt
          when(pixel === pixels-1.U) {
            when(channel === dimensions.channels-1.U) { state := S.tailPad }
              .otherwise { channel := channel+1.U; state := S.planeBegin }
          }.otherwise {
            pixel := pixel+1.U
            when(x === dimensions.imageWidth-1.U) { x := 0.U; y := y+1.U }.otherwise { x := x+1.U }
            state := S.northRead
          }
        }
      }
    }
    is(S.tailPad) {
      when(entropy.io.runRemaining =/= 0.U) { fail(3.U) }
        .otherwise { read((0.U(3.W)-reader.io.consumed(2,0))(2,0),S.tailCheck) }
    }
    is(S.tailCheck) {
      when(value =/= 0.U || reader.io.consumed =/= (inputCount << 3)) { fail(3.U) }
        .otherwise {
          parsing := false.B; pixel := 0.U; channel := 0.U; colorPlaneAddress := 0.U; state := S.colorRead
        }
    }
    is(S.fastBegin) {
      fastPtr := reader.io.consumed >> 3; fastSkip := reader.io.consumed(2,0)
      fastConsumed := reader.io.consumed; fastPool := 0.U; fastAvailable := 0.U
      fastPending := false.B; fastReadQueued := false.B; fastWest := 0.U; fastRowFirst := 0.U
      fastImageAddress := 0.U
      state := S.fastSamples
    }
    is(S.fastSamples) {
      val nibble = Reverse(fastPool(3,0))
      val symbol = Mux(nibble < 14.U,nibble.pad(5),Reverse(fastPool(4,0))-14.U)
      val codeN = Mux(nibble < 14.U,4.U(4.W),5.U(4.W))
      val extraN = Mux(symbol < 4.U,0.U(4.W),1.U+((symbol-4.U) >> 1))
      val needed = codeN+&extraN
      val extra = (fastPool >> codeN)(7,0) & ((1.U(9.W) << extraN)-1.U)(7,0)
      val u = Mux(symbol < 4.U,symbol.pad(10),
        ((((1.U(10.W) << extraN) | extra) << 1) | (symbol-4.U)(0)))
      val magnitude = Cat(0.U(1.W),u >> 1).asSInt
      val residual = Mux(u(0),-magnitude-1.S,magnitude)
      val prediction = Mux(!fastPredictor || pixel === 0.U,0.U,Mux(x === 0.U,fastRowFirst,fastWest))
      val sample = prediction.zext+&residual
      when(fastAvailable >= needed) {
        fastTake := needed(4,0); fastConsumed := fastConsumed+needed
        when(sample < 0.S || sample > 255.S) { fail(3.U) }
        .otherwise {
          imageWrite := true.B; imageAddress := fastImageAddress
          imageData := sample.asUInt(7,0); imageKeep := 1.U
          fastWest := sample.asUInt(7,0)
          when(x === 0.U) { fastRowFirst := sample.asUInt(7,0) }
          when(pixel === pixels-1.U) {
            when(channel === dimensions.channels-1.U) { state := S.fastTail }
            .otherwise {
              channel := channel+1.U; pixel := 0.U; x := 0.U; fastWest := 0.U; fastRowFirst := 0.U
              fastImageAddress := channel+1.U
            }
          }.otherwise {
            pixel := pixel+1.U; x := Mux(x === dimensions.imageWidth-1.U,0.U,x+1.U)
            fastImageAddress := fastImageAddress+dimensions.channels
          }
        }
      }.elsewhen(!fastPending && !fastReadQueued && !fastIssue && fastPtr >= inputCount) { fail(3.U) }
    }
    is(S.fastTail) {
      val n = (0.U(3.W)-fastConsumed(2,0))(2,0)
      when(fastConsumed+n =/= (inputCount << 3)) { fail(3.U) }
      .elsewhen(fastAvailable >= n) {
        when((fastPool & ((1.U(128.W) << n)-1.U)) =/= 0.U) { fail(3.U) }
          .otherwise { state := S.describe }
      }.elsewhen(!fastPending && !fastReadQueued && !fastIssue && fastPtr >= inputCount) { fail(3.U) }
    }
    is(S.colorRead) { state := S.colorWait }
    is(S.colorWait) {
      color(channel(1,0)) := planeByte
      when(channel === dimensions.channels-1.U) { state := S.colorCompute }
        .otherwise { channel := channel+1.U; colorPlaneAddress := colorPlaneAddress+pixels; state := S.colorRead }
    }
    is(S.colorCompute) {
      val t = color(0) -& (color(2) >> 1)
      val blue = t -& (color(1) >> 1)
      val results = Wire(Vec(4,SInt(14.W)))
      results(0) := Mux(rct,blue +& color(1),color(0))
      results(1) := Mux(rct,t +& color(2),color(1))
      results(2) := Mux(rct,blue,color(2)); results(3) := color(3)
      val invalid = (0 until 4).map(i => i.U < dimensions.channels && (results(i) < 0.S || results(i) > 255.S)).reduce(_ || _)
      when(invalid) { fail(3.U) }.otherwise {
        for(i <- 0 until 4) { converted(i) := results(i).asUInt(7,0) }
        channel := 0.U; state := S.colorWrite
      }
    }
    is(S.colorWrite) {
      val address = pixel*dimensions.channels+channel
      imageWrite := true.B; imageAddress := address(17,0)
      imageData := converted(channel(1,0)); imageKeep := 1.U
      when(channel === dimensions.channels-1.U) {
        channel := 0.U
        when(pixel === pixels-1.U) {
          state := S.describe; outAddress := 0.U; outLane := 0.U; outBeat := 0.U; outKeep := 0.U
        }.otherwise { pixel := pixel+1.U; colorPlaneAddress := pixel+1.U; state := S.colorRead }
      }.otherwise { channel := channel+1.U }
    }
    is(S.describe) { when(io.desc.fire) { state := S.outputOffer } }
    is(S.outputOffer) {
      when(io.output.fire) {
        outputCount := outputCount+PopCount(io.output.bits.keep)
        when(io.output.bits.last) { state := S.finish }
      }
    }
    is(S.finish) { when(io.done.fire) { state := S.idle } }
  }
  when(parsing && reader.io.failed) { fail(3.U) }
  when(entropyActive && entropy.io.failed && !treeActive) { fail(entropy.io.status) }
}
