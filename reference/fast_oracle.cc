// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// Original API adapter only. The encoder linked here remains the BSD reference.
#include <fstream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <vector>
#include "lib/jxl/enc_fast_lossless.h"

int main(int argc, char** argv) {
  if (argc != 7) return 2;
  try {
    const size_t w = std::stoul(argv[1]), h = std::stoul(argv[2]);
    const size_t channels = std::stoul(argv[3]);
    const int effort = std::stoi(argv[4]);
    if (!w || !h || w > 256 || h > 256 || channels < 1 || channels > 4) return 3;
    std::ifstream input(argv[5], std::ios::binary);
    std::vector<unsigned char> pixels((std::istreambuf_iterator<char>(input)), {});
    if (pixels.size() != w*h*channels) return 4;
    unsigned char* coded = nullptr;
    const size_t size = JxlFastLosslessEncode(pixels.data(), w, w*channels, h,
        channels, 8, false, effort, &coded, nullptr, nullptr);
    if (!size || !coded) return 5;
    std::ofstream output(argv[6], std::ios::binary);
    output.write(reinterpret_cast<char*>(coded), size);
    free(coded);
    return output.good() ? 0 : 6;
  } catch (const std::exception&) { return 7; }
}
