// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl.fast

import chisel3._
import chisel3.util._

/** Explicit, unsigned, combinational scanner address multiply (12 x 12). */
class FastFpgaMultiply extends BlackBox with HasBlackBoxInline {
  override def desiredName: String = "JxlFpgaMultiply12"
  val io = IO(new Bundle {
    val a = Input(UInt(12.W)); val b = Input(UInt(12.W))
    val p = Output(UInt(24.W))
  })
  setInline("JxlFpgaMultiply12.sv", FastFpgaMultiply.source)
}
object FastFpgaMultiply {
  def apply(a: UInt, b: UInt): UInt = {
    require(a.getWidth <= 12 && b.getWidth <= 12)
    val m = Module(new FastFpgaMultiply)
    m.io.a := a; m.io.b := b; m.io.p
  }
  val source: String = """// SPDX-License-Identifier: MIT
module JxlFpgaMultiply12(input wire [11:0] a,b, output wire [23:0] p);
`ifdef JXL_XILINX_PRIMITIVES
  wire [47:0] result;
  assign p = result[23:0];
  DSP48E2 #(
    .A_INPUT("DIRECT"), .B_INPUT("DIRECT"), .AMULTSEL("A"), .BMULTSEL("B"),
    .USE_MULT("MULTIPLY"), .USE_SIMD("ONE48"), .USE_PATTERN_DETECT("NO_PATDET"),
    .AREG(0), .ACASCREG(0), .BREG(0), .BCASCREG(0),
    .CREG(0), .DREG(0), .ADREG(0), .MREG(0), .PREG(0),
    .ALUMODEREG(0), .CARRYINREG(0), .CARRYINSELREG(0), .INMODEREG(0), .OPMODEREG(0)
  ) primitive_multiply (
    .A({18'b0,a}), .B({6'b0,b}), .C(48'b0), .D(27'b0),
    .ACIN(30'b0), .BCIN(18'b0), .PCIN(48'b0),
    .ALUMODE(4'b0000), .OPMODE(9'b00_000_01_01), .INMODE(5'b0),
    .CARRYIN(1'b0), .CARRYINSEL(3'b0), .CARRYCASCIN(1'b0), .MULTSIGNIN(1'b0),
    .CLK(1'b0), .CEA1(1'b0), .CEA2(1'b0), .CEB1(1'b0), .CEB2(1'b0),
    .CEC(1'b0), .CED(1'b0), .CEAD(1'b0), .CEM(1'b0), .CEP(1'b0),
    .CEALUMODE(1'b0), .CECARRYIN(1'b0), .CECTRL(1'b0), .CEINMODE(1'b0),
    .RSTA(1'b0), .RSTB(1'b0), .RSTC(1'b0), .RSTD(1'b0), .RSTM(1'b0), .RSTP(1'b0),
    .RSTALUMODE(1'b0), .RSTALLCARRYIN(1'b0), .RSTCTRL(1'b0), .RSTINMODE(1'b0),
    .P(result), .CARRYOUT(), .ACOUT(), .BCOUT(), .PCOUT(), .CARRYCASCOUT(),
    .MULTSIGNOUT(), .OVERFLOW(), .UNDERFLOW(), .PATTERNDETECT(), .PATTERNBDETECT(), .XOROUT()
  );
`else
`ifdef SYNTHESIS
  JXL_ERROR_EXPLICIT_FPGA_DSP_DEFINE_REQUIRED missing_primitive_configuration();
`else
  assign p = a*b;
`endif
`endif
endmodule
"""
}
