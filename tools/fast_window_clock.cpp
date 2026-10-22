// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
#include "VFastWindowTest.h"
#include "verilated.h"
int main(int argc, char** argv) {
  Verilated::commandArgs(argc, argv);
  VFastWindowTest dut;
  dut.reset=1;
  for (unsigned cycle=0; cycle<500010; ++cycle) {
    dut.clock=0; dut.eval(); dut.clock=1; dut.eval();
    if (cycle==3) dut.reset=0;
    if (!dut.reset && dut.io_finished) { dut.final(); return 0; }
    if (Verilated::gotFinish()) return 1;
  }
  return 2;
}
