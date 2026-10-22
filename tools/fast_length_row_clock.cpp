// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
// Clock/reset driver only. Assertions and old/new expressions are actual RTL.
#include "VFastLengthRowMiter.h"
#include "verilated.h"
int main(int argc, char** argv) {
  Verilated::commandArgs(argc, argv);
  VFastLengthRowMiter top;
  top.reset = 1;
  for (unsigned cycle = 0; cycle < 14410; ++cycle) {
    top.clock = 0; top.eval();
    if (cycle == 4) top.reset = 0;
    const bool finished = !top.reset && top.io_finished;
    top.clock = 1; top.eval();
    if (finished) { top.final(); return 0; }
  }
  top.final();
  return 2;
}
