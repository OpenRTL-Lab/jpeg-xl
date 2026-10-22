// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl.fast

import chisel3._
import chisel3.util._

/** Explicit one-cycle, common-clock, collision-free 1R1W FPGA memory.
  * The behavioral branch is only a RAM model, never a codec oracle.
  * Synthesis without the explicit primitive define is intentionally rejected.
  */
class FastFpgaMemory(val width: Int, val depth: Int, val ultra: Boolean)
    extends BlackBox(Map("WIDTH" -> width, "DEPTH" -> depth, "ULTRA" -> (if (ultra) 1 else 0)))
    with HasBlackBoxInline {
  require(width > 0 && depth >= 2)
  override def desiredName: String = "JxlFpgaSyncRam"
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val ren = Input(Bool()); val raddr = Input(UInt(log2Ceil(depth).W))
    val rdata = Output(UInt(width.W))
    val wen = Input(Bool()); val waddr = Input(UInt(log2Ceil(depth).W))
    val wdata = Input(UInt(width.W))
  })
  setInline("JxlFpgaSyncRam.sv", FastFpgaMemory.source)
}

object FastFpgaMemory {
  def apply(width: Int, depth: Int, ultra: Boolean, clock: Clock): FastFpgaMemory = {
    val memory = Module(new FastFpgaMemory(width, depth, ultra))
    memory.io.clk := clock
    memory.io.ren := false.B; memory.io.raddr := 0.U
    memory.io.wen := false.B; memory.io.waddr := 0.U; memory.io.wdata := 0.U
    memory
  }

  // Explicit 64-data-bit URAM slicing, adapted to a 1R1W interface.
  // The BRAM branch uses a 1024x32 data configuration; parity is unused.
  val source: String = """// SPDX-License-Identifier: MIT
module JxlFpgaSyncRam #(
  parameter integer WIDTH=24, DEPTH=514, ULTRA=1,
  parameter integer AW=$clog2(DEPTH)
) (
  input wire clk, ren, input wire [AW-1:0] raddr,
  output wire [WIDTH-1:0] rdata,
  input wire wen, input wire [AW-1:0] waddr,
  input wire [WIDTH-1:0] wdata
);
`ifndef SYNTHESIS
  always @(posedge clk) begin
    if (ren && 32'(raddr) >= DEPTH) $fatal(1,"JXL_RAM_READ_BOUNDS");
    if (wen && 32'(waddr) >= DEPTH) $fatal(1,"JXL_RAM_WRITE_BOUNDS");
    if (ren && wen && raddr == waddr) $fatal(1,"JXL_RAM_READ_WRITE_COLLISION");
  end
`endif
`ifdef JXL_XILINX_PRIMITIVES
  localparam integer TILE_DEPTH = ULTRA ? 4096 : 1024;
  localparam integer TILE_WIDTH = ULTRA ? 64 : 32;
  localparam integer BANKS = (DEPTH+TILE_DEPTH-1)/TILE_DEPTH;
  localparam integer SLICES = (WIDTH+TILE_WIDTH-1)/TILE_WIDTH;
  localparam integer BW = BANKS > 1 ? $clog2(BANKS) : 1;
  reg [BW-1:0] read_bank;
  wire [SLICES*TILE_WIDTH-1:0] padded_wdata = {{(SLICES*TILE_WIDTH-WIDTH){1'b0}},wdata};
  wire [SLICES*TILE_WIDTH-1:0] bank_data [0:BANKS-1];
  always @(posedge clk) if (ren) read_bank <= BW'(raddr / TILE_DEPTH);
  assign rdata = bank_data[read_bank][WIDTH-1:0];
  for (genvar b=0;b<BANKS;b=b+1) begin: banks
    wire rd = ren && raddr / TILE_DEPTH == b;
    wire wr = wen && waddr / TILE_DEPTH == b;
    for (genvar s=0;s<SLICES;s=s+1) begin: slices
      if (ULTRA) begin: u
        wire [22:0] ra = 23'(raddr % TILE_DEPTH);
        wire [22:0] wa = 23'(waddr % TILE_DEPTH);
        wire [71:0] dout;
        assign bank_data[b][s*64+:64] = dout[63:0];
        URAM288_BASE #(
          .BWE_MODE_A("PARITY_INTERLEAVED"), .BWE_MODE_B("PARITY_INTERLEAVED"),
          .EN_AUTO_SLEEP_MODE("FALSE"),
          .EN_ECC_RD_A("FALSE"), .EN_ECC_RD_B("FALSE"),
          .EN_ECC_WR_A("FALSE"), .EN_ECC_WR_B("FALSE"),
          .IREG_PRE_A("FALSE"), .IREG_PRE_B("FALSE"),
          .OREG_A("FALSE"), .OREG_B("FALSE"),
          .OREG_ECC_A("FALSE"), .OREG_ECC_B("FALSE"),
          .RST_MODE_A("SYNC"), .RST_MODE_B("SYNC")
        ) primitive_ram (
          .CLK(clk), .ADDR_A(ra), .ADDR_B(wa), .EN_A(rd), .EN_B(wr),
          .RDB_WR_A(1'b0), .RDB_WR_B(1'b1), .BWE_A(9'b0), .BWE_B(9'h1ff),
          .DIN_A(72'b0), .DIN_B({8'b0,padded_wdata[s*64+:64]}),
          .DOUT_A(dout), .DOUT_B(),
          .INJECT_DBITERR_A(1'b0), .INJECT_DBITERR_B(1'b0),
          .INJECT_SBITERR_A(1'b0), .INJECT_SBITERR_B(1'b0),
          .OREG_CE_A(1'b1), .OREG_CE_B(1'b1),
          .OREG_ECC_CE_A(1'b1), .OREG_ECC_CE_B(1'b1),
          .RST_A(1'b0), .RST_B(1'b0), .SLEEP(1'b0),
          .DBITERR_A(), .DBITERR_B(), .SBITERR_A(), .SBITERR_B()
        );
      end else begin: r
        wire [14:0] ra = {10'(raddr % TILE_DEPTH),5'b0};
        wire [14:0] wa = {10'(waddr % TILE_DEPTH),5'b0};
        RAMB36E2 #(
          .READ_WIDTH_A(36), .WRITE_WIDTH_A(0),
          .READ_WIDTH_B(0), .WRITE_WIDTH_B(36),
          .DOA_REG(0), .DOB_REG(0), .EN_ECC_READ("FALSE"), .EN_ECC_WRITE("FALSE"),
          .WRITE_MODE_A("NO_CHANGE"), .WRITE_MODE_B("NO_CHANGE"),
          .CASCADE_ORDER_A("NONE"), .CASCADE_ORDER_B("NONE")
        ) primitive_ram (
          .CLKARDCLK(clk), .CLKBWRCLK(clk),
          .ADDRARDADDR(ra), .ADDRBWRADDR(wa), .ADDRENA(1'b1), .ADDRENB(1'b1),
          .ENARDEN(rd), .ENBWREN(wr), .WEA(4'b0), .WEBWE(8'h0f),
          .DINADIN(32'b0), .DINPADINP(4'b0),
          .DINBDIN(padded_wdata[s*32+:32]), .DINPBDINP(4'b0),
          .DOUTADOUT(bank_data[b][s*32+:32]), .DOUTPADOUTP(), .DOUTBDOUT(), .DOUTPBDOUTP(),
          .REGCEAREGCE(1'b1), .REGCEB(1'b1),
          .RSTRAMARSTRAM(1'b0), .RSTRAMB(1'b0), .RSTREGARSTREG(1'b0), .RSTREGB(1'b0),
          .SLEEP(1'b0), .ECCPIPECE(1'b0), .INJECTDBITERR(1'b0), .INJECTSBITERR(1'b0),
          .CASDIMUXA(1'b0), .CASDIMUXB(1'b0), .CASDOMUXA(1'b0), .CASDOMUXB(1'b0),
          .CASDOMUXEN_A(1'b0), .CASDOMUXEN_B(1'b0),
          .CASOREGIMUXA(1'b0), .CASOREGIMUXB(1'b0),
          .CASOREGIMUXEN_A(1'b0), .CASOREGIMUXEN_B(1'b0),
          .CASDINA(32'b0), .CASDINB(32'b0), .CASDINPA(4'b0), .CASDINPB(4'b0),
          .CASINDBITERR(1'b0), .CASINSBITERR(1'b0),
          .CASDOUTA(), .CASDOUTB(), .CASDOUTPA(), .CASDOUTPB(),
          .CASOUTDBITERR(), .CASOUTSBITERR(),
          .DBITERR(), .SBITERR(), .ECCPARITY(), .RDADDRECC()
        );
      end
    end
  end
`else
`ifdef SYNTHESIS
  JXL_ERROR_EXPLICIT_FPGA_MEMORY_DEFINE_REQUIRED missing_primitive_configuration();
`else
  reg [WIDTH-1:0] memory [0:DEPTH-1];
  reg [WIDTH-1:0] data;
  assign rdata = data;
  always @(posedge clk) begin
    if (wen) memory[waddr] <= wdata;
    if (ren) data <= memory[raddr];
  end
`endif
`endif
endmodule
"""
}
