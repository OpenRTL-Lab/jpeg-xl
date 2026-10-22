// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
// TEST ENVIRONMENT ONLY. Same synchronous address / masked-write ports as the
// generated 4GiB Harness DDR array. Never compiled into the FPGA ChipTop.
module mem_67108864x512(
  input [25:0] R0_addr,
  input R0_en, R0_clk,
  output [511:0] R0_data,
  input [25:0] W0_addr,
  input W0_en, W0_clk,
  input [511:0] W0_data,
  input [63:0] W0_mask
);
  import "DPI-C" function void jxl_sparse_write(
    input int unsigned address, input bit [63:0] mask,
    input bit [511:0] data);
  import "DPI-C" function void jxl_sparse_read(
    input int unsigned address, input int unsigned revision,
    output bit [511:0] data);
  reg read_enabled;
  reg [25:0] read_address;
  int unsigned revision = 0;
  bit [511:0] stored_data;
  always @(posedge R0_clk) begin
    read_enabled <= R0_en;
    read_address <= R0_addr;
  end
  always @(posedge W0_clk) begin
    if (W0_en) begin
      jxl_sparse_write({6'b0,W0_addr}, W0_mask, W0_data);
      // Force the asynchronous array read to observe masked writes even if
      // its registered read address did not change, as in the original RAM.
      revision <= revision + 1;
    end
  end
  always_comb jxl_sparse_read({6'b0,read_address}, revision, stored_data);
  assign R0_data = read_enabled ? stored_data : 512'bx;
endmodule
