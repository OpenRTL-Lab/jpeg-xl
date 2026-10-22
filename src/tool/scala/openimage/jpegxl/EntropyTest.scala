// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl
import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import scala.io.Source

case class EntropyVector(bytes: Seq[Int], contexts: Int, requests: Seq[Int], values: Seq[BigInt], status: Int, bits: Int)
class EntropyTest(vectors: Seq[EntropyVector], negative: Boolean) extends Module {
  val io = IO(new Bundle { val finished = Output(Bool()) })
  val entropy = Module(new ModularEntropy)
  val reader = Module(new BitReader)
  entropy.io.request <> reader.io.request
  entropy.io.response <> reader.io.response
  val cycle = RegInit(0.U(32.W)); cycle := cycle+1.U
  assert(cycle < 250000.U, "ENTROPY_TIMEOUT")
  val clear :: begin :: running :: advance :: complete :: Nil = Enum(5)
  val state = RegInit(clear); val task = RegInit(0.U(log2Ceil(vectors.size).W))
  val byteN = RegInit(0.U(16.W)); val requestN = RegInit(0.U(16.W)); val valueN = RegInit(0.U(16.W))
  val bytes = vectors.flatMap(_.bytes)
  val requests = vectors.flatMap(v => if(v.requests.isEmpty) Seq(0) else v.requests)
  val values = vectors.flatMap(v => if(v.values.isEmpty) Seq(BigInt(0)) else v.values)
  val byteRom = VecInit(bytes.map(_.U(8.W)))
  val reqRom = VecInit(requests.map(_.U(4.W)))
  val valRom = VecInit(values.map(_.U(32.W)))
  val byteBase = VecInit(vectors.indices.map(i => vectors.take(i).map(_.bytes.size).sum.U(16.W)))
  val reqBase = VecInit(vectors.indices.map(i => vectors.take(i).map(v => v.requests.size.max(1)).sum.U(16.W)))
  val valBase = VecInit(vectors.indices.map(i => vectors.take(i).map(v => v.values.size.max(1)).sum.U(16.W)))
  val byteSize = VecInit(vectors.map(_.bytes.size.U(16.W)))
  val reqSize = VecInit(vectors.map(_.requests.size.U(16.W)))
  val valSize = VecInit(vectors.map(_.values.size.U(16.W)))
  val bitSize = VecInit(vectors.map(_.bits.U(32.W)))
  val statuses = VecInit(vectors.map(_.status.U(3.W)))
  reader.io.clear := state === clear
  reader.io.input.valid := state =/= clear && state =/= complete && byteN < byteSize(task) && cycle % 7.U =/= 0.U
  reader.io.input.bits.data := byteRom((byteBase(task)+byteN)(log2Ceil(bytes.size)-1,0))
  reader.io.input.bits.last := byteN === byteSize(task)-1.U
  when(reader.io.input.fire) { byteN := byteN+1.U }
  entropy.io.begin.valid := state === begin
  entropy.io.begin.bits := VecInit(vectors.map(_.contexts.U(4.W)))(task)
  entropy.io.context.valid := state === running && requestN < reqSize(task) && cycle % 3.U =/= 0.U
  entropy.io.context.bits := reqRom((reqBase(task)+requestN)(log2Ceil(requests.size)-1,0))
  entropy.io.value.ready := state === running && cycle % 5.U =/= 0.U
  when(entropy.io.context.fire) { requestN := requestN+1.U }
  when(entropy.io.value.fire) {
    // An error case may deliver a literal before the designated failing token.
    when(statuses(task) === 0.U) {
      assert(valueN < valSize(task), "ENTROPY_EXCESS_VALUE")
      val wanted = valRom((valBase(task)+valueN)(log2Ceil(values.size)-1,0))
      if(negative) { assert(entropy.io.value.bits === (wanted ^ 1.U), "NEGATIVE_ENTROPY_EXPECTED") }
        else { assert(entropy.io.value.bits === wanted, "ENTROPY_VALUE") }
    }
    valueN := valueN+1.U
  }
  val held = RegNext(entropy.io.value.bits)
  val stalled = RegNext(entropy.io.value.valid && !entropy.io.value.ready, false.B)
  when(stalled) { assert(entropy.io.value.valid && entropy.io.value.bits === held, "ENTROPY_STABLE") }
  when(state === clear) { byteN := 0.U; requestN := 0.U; valueN := 0.U; state := begin }
  when(entropy.io.begin.fire) { state := running }
  when(state === running) {
    assert(!reader.io.failed, "ENTROPY_UNEXPECTED_EOF")
    when(entropy.io.failed) {
      assert(statuses(task) =/= 0.U && entropy.io.status === statuses(task), "ENTROPY_BAD_STATUS")
      state := advance
    }.elsewhen(entropy.io.ready && valueN === valSize(task) && statuses(task) === 0.U) {
      assert(requestN === reqSize(task), "ENTROPY_COUNTS")
      assert(reader.io.consumed === bitSize(task) && entropy.io.runRemaining === 0.U, "ENTROPY_BITS_RUN")
      state := advance
    }
  }
  when(state === advance) {
    printf(p"ENTROPY_TASK_PASS ${task} status=${statuses(task)} values=${valueN}\n")
    when(task === (vectors.size-1).U) { state := complete }
      .otherwise { task := task+1.U; state := clear }
  }
  when(state === complete && !RegNext(state === complete,false.B)) { printf("ENTROPY_SUITE_PASS\n") }
  io.finished := RegNext(state === complete,false.B)
}
object EmitEntropyTest {
  def main(args: Array[String]): Unit = {
    require(args.length == 3)
    val source = Source.fromFile(args(1))
    val vectors = try source.getLines().map { line =>
      val p = line.split("\t")
      def list(s: String): Seq[String] = if(s == "-") Seq.empty else s.split(",").toSeq
      EntropyVector(p(0).grouped(2).map(Integer.parseInt(_,16)).toSeq,p(1).toInt,
        list(p(2)).map(_.toInt),list(p(3)).map(BigInt(_)),p(4).toInt,p(5).toInt)
    }.toSeq finally source.close()
    ChiselStage.emitSystemVerilogFile(new EntropyTest(vectors,args(2) == "negative"),
      Array("--target-dir",args(0)),Array("--split-verilog","--disable-all-randomization","--strip-debug-info"))
  }
}
