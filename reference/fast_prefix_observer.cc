// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
// Golden tables from official full PrepareFrame, cross-checked to official
// collector/constructor adapters. No independent codec implementation.
#include "lib/jxl/enc_fast_lossless.cc"
#include <fstream>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <vector>
using namespace default_implementation;

template <class T> static std::string Hex(const T* p, size_t n, size_t digits) {
  std::ostringstream s;
  while (n--) s << std::hex << std::setfill('0') << std::setw(digits) << uint64_t(p[n]);
  return s.str();
}
int main(int argc, char** argv) {
  if (argc != 2) return 2;
  std::ofstream out(argv[1]); size_t index = 0;
  const size_t widths[] = {1, 8, 17, 256}, heights[] = {64, 128, 255, 256};
  for (size_t ch = 1; ch <= 4; ++ch) for (size_t pattern = 0; pattern < 4; ++pattern) {
    const size_t w = widths[pattern], h = heights[pattern];
    std::vector<uint8_t> pixels(w * h * ch);
    for (size_t y = 0; y < h; ++y) for (size_t x = 0; x < w; ++x)
      for (size_t c = 0; c < ch; ++c) {
        uint8_t value = 0;
        if (pattern == 1) value = uint8_t((x & 1) * 255);
        if (pattern == 2) value = uint8_t((x * 131 + y * 29 + c * 53) ^ (x * y));
        if (pattern == 3) value = uint8_t(x + y * (c + 1));
        pixels[(y * w + x) * ch + c] = value;
      }
    FJxlFrameInput source(pixels.data(), w * ch, ch, 8);
    auto* official = JxlFastLosslessPrepareFrame(source.GetInputSource(), w, h, ch, 8, false, 1, true);
    if (!official) return 3;
    uint64_t raw[4][19] = {}, lz[4][33] = {};
    const size_t count = 2 * h / 256;
    CollectSamples(pixels.data(), 0, (h - count) / 2, w / 8 * 8, w * ch, count,
      raw, lz, true, false, UpTo8Bits(8), ch, false, nullptr);
    constexpr uint64_t rawBase[19] = {3843, 852, 1270, 1214, 1014, 727, 481, 300, 159, 51,
      5, 1, 1, 1, 1, 1, 1, 1, 1};
    constexpr uint64_t lzBase[33] = {29, 27, 25, 23, 21, 21, 19, 18, 21, 17, 16, 15, 15, 14,
      13, 13, 137, 98, 61, 34, 1, 1, 1, 1, 1, 1, 1, 1};
    for (size_t c = 0; c < 4; ++c) {
      for (size_t s = 0; s < 19; ++s) raw[c][s] = (raw[c][s] << 8) +
        (s < UpTo8Bits(8).NumSymbols(ch >= 3) ? rawBase[s] : 0);
      for (size_t s = 0; s < 33; ++s) lz[c][s] = (lz[c][s] << 8) + lzBase[s];
      PrefixCode adapted(UpTo8Bits(8), raw[c], lz[c]);
      const auto& code = official->hcode[c];
      // The whole official preparation path independently validates the
      // sampling/bias adapter, including inactive channel default tables.
      if (memcmp(code.raw_nbits, adapted.raw_nbits, sizeof(code.raw_nbits)) ||
          memcmp(code.raw_bits, adapted.raw_bits, sizeof(code.raw_bits)) ||
          memcmp(code.lz77_nbits, adapted.lz77_nbits, sizeof(code.lz77_nbits)) ||
          memcmp(code.lz77_bits, adapted.lz77_bits, sizeof(code.lz77_bits))) return 4;
      out << index++ << '\t' << ch << '\t' << Hex(raw[c], 19, 8) << '\t' << Hex(lz[c], 33, 8)
          << '\t' << Hex(code.raw_nbits, 19, 2) << '\t' << Hex(code.raw_bits, 19, 2)
          << '\t' << Hex(code.lz77_nbits, 33, 2) << '\t' << Hex(code.lz77_bits, 33, 4) << '\n';
    }
    JxlFastLosslessFreeFrameState(official);
  }
  if (!out.good()) return 5;
  std::cout << "OFFICIAL_PREFIX_OBSERVER_PASS cases=" << index << '\n';
  return 0;
}
