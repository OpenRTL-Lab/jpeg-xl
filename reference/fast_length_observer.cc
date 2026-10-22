// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
// Exact expected lengths come exclusively from frozen official PrefixCode.
#include "lib/jxl/enc_fast_lossless.cc"
#include <fstream>
#include <iomanip>
#include <iostream>
#include <sstream>
using namespace default_implementation;

template <class T> static std::string Hex(const T* p, size_t n, size_t digits) {
  std::ostringstream s;
  while (n--) s << std::hex << std::setfill('0') << std::setw(digits) << uint64_t(p[n]);
  return s.str();
}
int main(int argc, char** argv) {
  if (argc != 2) return 2;
  std::ofstream out(argv[1]);
  size_t index = 0;
  auto emit = [&](size_t n, size_t pattern, size_t maximum, bool sparse) {
    uint64_t freq[33] = {};
    uint8_t lo[33] = {}, hi[33] = {}, result[33] = {};
    for (size_t i = 0; i < n; ++i) {
      size_t k = sparse ? i * 2 : i;
      freq[k] = pattern ? 1 + ((i * 137 + n * 19) % 700) : 1;
      lo[k] = sparse && i & 1 ? 2 : 0;
      hi[k] = maximum;
    }
    PrefixCode::ComputeCodeLengths(freq, 33, lo, hi, result);
    uint64_t cost = 0;
    for (size_t i = 0; i < 33; ++i) cost += freq[i] * result[i];
    out << index++ << '\t' << Hex(freq, 33, 8) << '\t' << Hex(lo, 33, 2)
        << '\t' << Hex(hi, 33, 2) << '\t' << Hex(result, 33, 2) << '\t' << cost << '\n';
  };
  for (size_t n = 2; n <= 33; ++n) {
    emit(n, 0, 7, false); emit(n, 1, 7, false);
  }
  for (size_t n : {10, 11, 28, 33}) emit(n, 1, 14, false);
  for (size_t n = 5; n <= 8; ++n) emit(n, 1, 7, true);
  if (!out.good()) return 3;
  std::cout << "OFFICIAL_LENGTH_OBSERVER_PASS cases=" << index << '\n';
}
