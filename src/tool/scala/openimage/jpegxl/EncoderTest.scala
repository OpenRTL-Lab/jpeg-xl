// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl
import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import java.nio.file.{Files, Paths}
import chisel3.util.experimental.loadMemoryFromFileInline

/** External full-port DUT, so firtool never folds test constants into product. */
class ExternalEncoder(decoder: Boolean = false) extends BlackBox {
  override def desiredName = if(decoder) "JxlDecoder" else "JxlEncoder"
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val io = new NativeIO
  })
}
case class EncoderVector(w: Int, h: Int, c: Int, predictor: Int, tag: Long,
                         input: Seq[Int], expected: Seq[Int], status: Int = 0,
                         inputMode: Int = 0, commandBytes: Long = -1, limit: Long = -1,
                         commandWidth: Int = -1)
class EncoderTest(vectors: Seq[EncoderVector], negative: Boolean, decoder: Boolean = false,
                  negativeKind: String = "tag", performance: Boolean = false) extends Module {
  val io = IO(new Bundle { val finished = Output(Bool()) })
  val dut = Module(new ExternalEncoder(decoder))
  val resetHold = RegInit(0.U(3.W))
  val cancelled = RegInit(false.B)
  when(resetHold =/= 0.U) { resetHold := resetHold-1.U }
  dut.io.clock := clock
  dut.io.reset := reset.asBool || resetHold =/= 0.U
  val cycle = RegInit(0.U(32.W)); cycle := cycle+1.U
  val commandCycle = Reg(UInt(32.W))
  val firstDataSeen = RegInit(false.B)
  val firstDataCycle = Reg(UInt(32.W))
  val inputStalls = RegInit(0.U(32.W))
  val sourceGaps = RegInit(0.U(32.W))
  val outputStalls = RegInit(0.U(32.W))
  assert(cycle < 200000000.U, "CODEC_TEST_TIMEOUT")
  val command :: transfer :: completed :: Nil = Enum(3)
  val state = RegInit(command)
  val task = RegInit(0.U(math.max(1, log2Ceil(vectors.size)).W))
  val inIndex = RegInit(0.U(32.W))
  val outIndex = RegInit(0.U(32.W))
  val sawDesc = RegInit(false.B)
  val seenDone = RegInit(false.B)
  val allInputs = vectors.flatMap(_.input)
  val allOutputs = vectors.flatMap(_.expected)
  // Test-only initialized memories keep maximum-size vectors out of an
  // enormous combinational mux. Product SV remains completely independent.
  def testRom(bytes: Seq[Int], name: String) = {
    val path = Paths.get(System.getProperty("jxl.test.rom.directory"),name+".hex")
    Files.write(path,(bytes.map(b => f"$b%02x").mkString("\n")+"\n").getBytes("US-ASCII"))
    val memory = Mem(bytes.size,UInt(8.W))
    loadMemoryFromFileInline(memory,path.toString)
    memory
  }
  val inputRom = testRom(allInputs ++ Seq.fill(8)(0),"input")
  val outputRom = testRom(allOutputs ++ Seq.fill(8)(0),"expected")
  val inOffsets = VecInit(vectors.indices.map(i => vectors.take(i).map(_.input.size).sum.U(32.W)))
  val outOffsets = VecInit(vectors.indices.map(i => vectors.take(i).map(_.expected.size).sum.U(32.W)))
  val ins = VecInit(vectors.map(_.input.size.U(32.W)))
  val outs = VecInit(vectors.map(_.expected.size.U(32.W)))
  val ws = VecInit(vectors.map(_.w.U(9.W)))
  val hs = VecInit(vectors.map(_.h.U(9.W)))
  val cs = VecInit(vectors.map(_.c.U(3.W)))
  val ps = VecInit(vectors.map(_.predictor.U(1.W)))
  val tags = VecInit(vectors.map(_.tag.U(32.W)))
  val statuses = VecInit(vectors.map(_.status.U(8.W)))
  val modes = VecInit(vectors.map(_.inputMode.U(3.W)))
  val commandSizes = VecInit(vectors.map(v => (if(v.commandBytes >= 0) v.commandBytes else v.input.size.toLong).U(32.W)))
  val limits = VecInit(vectors.map(v => (if(v.limit >= 0) v.limit else v.expected.size.toLong).U(32.W)))
  val commandWidths = VecInit(vectors.map(v => (if(v.commandWidth >= 0) v.commandWidth else if(decoder) 0 else v.w).U(9.W)))
  val port = dut.io.io
  port.cmd.valid := state === command && resetHold === 0.U
  port.cmd.bits.imageWidth := commandWidths(task)
  port.cmd.bits.imageHeight := (if(decoder) 0.U else hs(task))
  port.cmd.bits.channels := (if(decoder) 0.U else cs(task))
  port.cmd.bits.predictor := ps(task).asBool
  port.cmd.bits.inputBytes := commandSizes(task)
  port.cmd.bits.outputLimit := limits(task)
  port.cmd.bits.tag := tags(task)
  when(port.cmd.fire) {
    state := transfer; commandCycle := cycle; firstDataSeen := false.B
    inputStalls := 0.U; sourceGaps := 0.U; outputStalls := 0.U
  }
  when(state === transfer && port.output.fire && !firstDataSeen) {
    firstDataSeen := true.B; firstDataCycle := cycle
  }

  val heldInput = RegInit(false.B)
  val inputBeat = Reg(new Stream)
  port.input.valid := heldInput
  port.input.bits := inputBeat
  if(performance) {
    require(vectors.forall(v => v.status == 0 && v.inputMode == 0))
    port.input.valid := state === transfer && inIndex < ins(task)
    val bytes = Wire(Vec(8,UInt(8.W)))
    for(i <- 0 until 8) {
      val address = inOffsets(task)+inIndex+i.U
      bytes(i) := inputRom(address(log2Ceil(allInputs.size+8)-1,0))
    }
    port.input.bits.data := bytes.asUInt
    val remaining = ins(task)-inIndex
    port.input.bits.keep := Mux(remaining >= 8.U,255.U,((1.U(9.W) << remaining(2,0))-1.U)(7,0))
    port.input.bits.last := remaining <= 8.U
  } else {
  when(state === transfer && resetHold === 0.U && statuses(task) =/= 1.U && !heldInput && inIndex < ins(task) && cycle % 7.U =/= 0.U) {
    val bytes = Wire(Vec(8, UInt(8.W)))
    for (i <- 0 until 8) {
      val address = inOffsets(task)+inIndex+i.U
      bytes(i) := inputRom(address(log2Ceil(allInputs.size+8)-1, 0))
    }
    inputBeat.data := bytes.asUInt
    val remaining = ins(task)-inIndex
    inputBeat.keep := Mux(remaining >= 8.U, 255.U, ((1.U(9.W) << remaining(2, 0))-1.U)(7, 0))
    when(modes(task) === 1.U && inIndex === 0.U && remaining > 8.U) { inputBeat.keep := 254.U }
    inputBeat.last := remaining <= 8.U
    heldInput := true.B
  }
  }
  when(port.input.fire) { heldInput := false.B; inIndex := inIndex+PopCount(port.input.bits.keep) }
  port.desc.ready := cycle % 9.U =/= 0.U && !(modes(task) === 4.U && !cancelled) && resetHold === 0.U
  port.output.ready := cycle % 5.U =/= 0.U && !(modes(task) === 3.U && !cancelled) && resetHold === 0.U
  port.done.ready := cycle % 3.U =/= 0.U && !(modes(task) === 5.U && !cancelled) && resetHold === 0.U
  if(performance) {
    port.desc.ready := true.B; port.output.ready := true.B; port.done.ready := true.B
    when(state === transfer && port.input.valid && !port.input.ready) { inputStalls := inputStalls+1.U }
    when(state === transfer && inIndex < ins(task) && !port.input.valid) { sourceGaps := sourceGaps+1.U }
    when(state === transfer && port.output.valid && !port.output.ready) { outputStalls := outputStalls+1.U }
    stable(port.input,"PERF_INPUT")
  }
  def stable[T <: Data](p: DecoupledIO[T], name: String): Unit = {
    val stalled = RegNext(p.valid && !p.ready, false.B)
    val bits = RegNext(p.bits.asUInt)
    when(stalled && resetHold === 0.U) { assert(p.valid && p.bits.asUInt === bits, s"${name}_STABLE") }
  }
  stable(port.output, "OUTPUT"); stable(port.desc, "DESC"); stable(port.done, "DONE")
  when(port.desc.fire) {
    assert(statuses(task) === 0.U,"NO_DESCRIPTION_ON_ERROR")
    assert(!sawDesc, "DESC_ONCE")
    assert(port.desc.bits.imageWidth === ws(task) && port.desc.bits.imageHeight === hs(task), "DESC_DIMENSIONS")
    assert(port.desc.bits.channels === cs(task) && port.desc.bits.tag === tags(task), "DESC_FORMAT_TAG")
    assert(port.desc.bits.rawBytes === (if(decoder) outs(task) else ins(task)) &&
      port.desc.bits.fileBytes === (if(decoder) ins(task) else outs(task)), "DESC_COUNTS")
    sawDesc := true.B
  }
  when(port.output.fire) {
    assert(statuses(task) === 0.U,"NO_OUTPUT_ON_ERROR")
    assert(sawDesc, "OUTPUT_AFTER_DESCRIPTION")
    val remaining = outs(task)-outIndex
    val expectedKeep = Mux(remaining >= 8.U, 255.U, ((1.U(9.W) << remaining(2, 0))-1.U)(7, 0))
    assert(port.output.bits.keep === expectedKeep && port.output.bits.last === (remaining <= 8.U), "OUTPUT_KEEP_LAST")
    for (lane <- 0 until 8) {
      val address = outOffsets(task)+outIndex+lane.U
      when(port.output.bits.keep(lane)) {
        val wanted = outputRom(address(log2Ceil(allOutputs.size+8)-1, 0))
        if(negative && (negativeKind == "pixel" || negativeKind == "compare")) {
          assert(port.output.bits.data(8*lane+7,8*lane) === (wanted ^ 1.U),"NEGATIVE_PIXEL_EXPECTED")
        } else {
          assert(port.output.bits.data(8*lane+7, 8*lane) === wanted,
            if(decoder) "DECODED_BYTE" else "ENCODED_BYTE")
        }
      }
    }
    if(decoder) { printf(p"DOUT ${task} ${Hexadecimal(port.output.bits.data)} ${Hexadecimal(port.output.bits.keep)} ${port.output.bits.last}\n") }
    else { printf(p"EOUT ${task} ${Hexadecimal(port.output.bits.data)} ${Hexadecimal(port.output.bits.keep)} ${port.output.bits.last}\n") }
    outIndex := outIndex+PopCount(port.output.bits.keep)
  }
  when(port.done.fire) {
    if(performance) {
      assert(firstDataSeen && sourceGaps === 0.U && outputStalls === 0.U,"PERF_NO_HOST_STALLS")
      printf(p"PERF_TASK ${task} cmd=${commandCycle} first=${firstDataCycle} done=${cycle} in_stalls=${inputStalls} source_gaps=${sourceGaps} output_stalls=${outputStalls}\n")
    } else {
      printf(p"CODEC_METRIC ${task} complete_cycles=${cycle-commandCycle+1.U} first_data_cycles=${Mux(firstDataSeen,firstDataCycle-commandCycle+1.U,0.U)} with_input_gaps_and_backpressure=1\n")
    }
    if(decoder) { printf(p"DECODER_DONE ${task} status=${port.done.bits.status} input=${port.done.bits.inputCount} output=${port.done.bits.outputCount} desc=${sawDesc}\n") }
    assert(!seenDone && sawDesc === (statuses(task) === 0.U), "DONE_ORDER")
    val expectedIn = Mux(statuses(task) === 1.U,0.U,ins(task))
    val expectedOut = Mux(statuses(task) === 0.U,outs(task),0.U)
    if(negative && negativeKind == "status") {
      assert(port.done.bits.status === (statuses(task) ^ 1.U),"NEGATIVE_STATUS_EXPECTED")
    } else { assert(port.done.bits.status === statuses(task),"DONE_STATUS") }
    if(negative && negativeKind == "count") {
      assert(port.done.bits.inputCount === (expectedIn ^ 1.U),"NEGATIVE_COUNT_EXPECTED")
    } else { assert(port.done.bits.inputCount === expectedIn,"DONE_INPUT_COUNT") }
    assert(port.done.bits.outputCount === expectedOut && outIndex === expectedOut,"DONE_OUTPUT_COUNT")
    if (negative && negativeKind == "tag") {
      assert(port.done.bits.tag === (tags(task) ^ 1.U), "NEGATIVE_TAG_EXPECTED")
    } else { assert(port.done.bits.tag === tags(task), "DONE_TAG") }
    if(decoder) { printf(p"DECODER_TASK_PASS ${task} file=${ins(task)} raw=${outs(task)} cycle=${cycle}\n") }
    else { printf(p"ENCODER_TASK_PASS ${task} input=${ins(task)} file=${outs(task)} cycle=${cycle}\n") }
    when(task === (vectors.size-1).U) {
      state := completed; seenDone := true.B
      if(decoder) { printf("DECODER_SUITE_PASS\n") } else { printf("ENCODER_SUITE_PASS\n") }
    }.otherwise {
      task := task+1.U; state := command; inIndex := 0.U; outIndex := 0.U; sawDesc := false.B; cancelled := false.B
    }
  }
  val cancelNow = state === transfer && !cancelled && resetHold === 0.U && (
    (modes(task) === 2.U && inIndex >= 8.U) ||
    (modes(task) === 3.U && port.output.valid) ||
    (modes(task) === 4.U && port.desc.valid) ||
    (modes(task) === 5.U && port.done.valid))
  when(cancelNow) {
    resetHold := 3.U; cancelled := true.B; state := command
    heldInput := false.B; inIndex := 0.U; outIndex := 0.U; sawDesc := false.B
    printf(p"RESET_CANCEL ${task} mode=${modes(task)}\n")
  }
  when(resetHold > 0.U && resetHold < 3.U) {
    assert(!port.output.valid && !port.desc.valid && !port.done.valid,"RESET_CANCELS_GENERATION")
  }
  io.finished := state === completed
}
object EmitEncoderTest {
  def main(args: Array[String]): Unit = {
    require(args.length == 3)
    Files.createDirectories(Paths.get(args(0)))
    System.setProperty("jxl.test.rom.directory",Paths.get(args(0)).toAbsolutePath.toString)
    val rows = Files.readAllLines(Paths.get(args(1)))
    import scala.jdk.CollectionConverters._
    val vectors = rows.asScala.toSeq.filter(_.nonEmpty).map { row =>
      val a = row.split("\t")
      def bytes(path: String) = Files.readAllBytes(Paths.get(path)).toSeq.map(_ & 255)
      def optional(i: Int, default: Long) = if(a.length > i) a(i).toLong else default
      EncoderVector(a(0).toInt, a(1).toInt, a(2).toInt, a(3).toInt, a(4).toLong, bytes(a(5)), bytes(a(6)),
        optional(7,0).toInt,optional(8,0).toInt,optional(9,-1),optional(10,-1),optional(11,-1).toInt)
    }
    val mode = args(2)
    val negativeKind = if(mode.contains("negative-")) mode.split("negative-").last else "tag"
    ChiselStage.emitSystemVerilogFile(new EncoderTest(vectors, mode.contains("negative"), mode.startsWith("decode"),negativeKind,mode.contains("performance")),
      Array("--target-dir", args(0)), Array("--split-verilog", "--disable-all-randomization", "--strip-debug-info"))
  }
}
