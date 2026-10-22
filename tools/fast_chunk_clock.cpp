// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
// Clock/reset/trigger only: all product inputs and comparisons live in Chisel.
#include "VFastChunkTest.h"
#include "verilated.h"
int main(int argc, char** argv) {
  VerilatedContext context;
  context.commandArgs(argc, argv);
  VFastChunkTest top{&context};
  top.clock = 0; top.reset = 1;
  for (unsigned cycle = 0; cycle < 21000 && !context.gotFinish(); ++cycle) {
    top.reset = cycle < 4;
    top.clock = 0; top.eval(); context.timeInc(1);
    top.clock = 1; top.eval(); context.timeInc(1);
    if (!top.reset && top.io_finished) { top.final(); return 0; }
  }
  top.final();
  return 2;
}
