// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
// Clock/reset only. All data generation, checks and logging are in Chisel.
#ifdef JXL_ENTROPY_TEST
#include "VEntropyTest.h"
using TestTop = VEntropyTest;
#elif defined(JXL_COMPONENT_TEST)
#include "VDecoderComponentTest.h"
using TestTop = VDecoderComponentTest;
#else
#include "VEncoderTest.h"
using TestTop = VEncoderTest;
#endif
#include "verilated.h"
int main(int argc, char** argv) {
  Verilated::commandArgs(argc, argv);
  TestTop top;
  top.reset = 1;
  for (int i = 0; i < 6; ++i) { top.clock = i & 1; top.eval(); }
  top.reset = 0;
  for (unsigned long cycle = 0; cycle < 200000010UL && !Verilated::gotFinish(); ++cycle) {
    top.clock = 0; top.eval();
    top.clock = 1; top.eval();
    if (top.io_finished) { top.final(); return 0; }
  }
  top.final();
  return 3;
}
