// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// This is a thin test observer of the frozen, unmodified official implementation.
// It is NOT a substitute codec or an independently reimplemented predictor.
// The included upstream translation unit retains its BSD license and identity.
#include "lib/jxl/enc_fast_lossless.cc"
#include <array>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <vector>

// Scalar upstream internals live in this target namespace. Keep the upstream
// translation unit unchanged; this is only observer-side name lookup.
using namespace default_implementation;

struct ObservedChunk {
  std::array<uint16_t, 8> values{};
  void Chunk(size_t, const uint16_t* residual, size_t, size_t n) {
    std::copy_n(residual, n, values.begin());
  }
  void Finalize(size_t) {}
};

static std::string hex(const std::vector<uint8_t>& data) {
  std::ostringstream out;
  for (auto i = data.rbegin(); i != data.rend(); ++i)
    out << std::hex << std::setfill('0') << std::setw(2) << unsigned(*i);
  return out.str();
}

int main(int argc, char** argv) {
  static_assert(kChunkSize == 8, "Frozen scalar oracle requires 8-sample chunks");
  if (argc != 2) return 2;
  std::ofstream out(argv[1]);
  uint32_t random = 0x731bed42;
  size_t index = 0;
  for (size_t channels = 1; channels <= 4; ++channels)
    for (size_t count = 1; count <= 8; ++count)
      for (size_t pattern = 0; pattern < 12; ++pattern) {
        uint8_t raw[4][8][4] = {};
        int16_t plane[4][4][8] = {};
        std::vector<uint8_t> input, residual, token, nbits, extra, prefix;
        for (size_t p = 0; p < 4; ++p) {
          uint8_t packed[32] = {};
          for (size_t lane = 0; lane < 8; ++lane)
            for (size_t c = 0; c < 4; ++c) {
              random ^= random << 13; random ^= random >> 17; random ^= random << 5;
              uint8_t value = uint8_t(random);
              if (pattern == 0) value = 0;
              if (pattern == 1) value = 255;
              if (pattern == 2) value = uint8_t((lane * 37 + c * 89) & 255);
              if (pattern == 3) value = p == 0 ? 0 : 255;
              if (pattern == 4) value = p == 0 ? 255 : 0;
              if (pattern == 5) value = (p == 1 || p == 2) ? 0 : 255;
              if (pattern == 6) value = (p == 1 || p == 2) ? 255 : 0;
              if (pattern == 7) value = c == 3 ? 0 : uint8_t(random);
              if (pattern == 8) value = c == 3 ? 255 : uint8_t(random);
              if (pattern == 9) value = ((lane + c + p) & 1) ? 255 : 0;
              if (pattern == 10) value = uint8_t(p * 61 + lane + c * 3);
              raw[p][lane][c] = value;
              input.push_back(value);
              if (c < channels) packed[lane * channels + c] = value;
            }
          if (channels == 1) FillRowG8(packed, 8, plane[p][0]);
          if (channels == 2) FillRowGA8(packed, 8, plane[p][0], plane[p][1]);
          if (channels == 3) FillRowRGB8(packed, 8, plane[p][0], plane[p][1], plane[p][2]);
          if (channels == 4) FillRowRGBA8(packed, 8, plane[p][0], plane[p][1], plane[p][2], plane[p][3]);
        }
        for (size_t c = 0; c < 4; ++c) {
          ObservedChunk observed;
          if (c < channels) {
            ChannelRowProcessor<ObservedChunk, UpTo8Bits> processor{};
            processor.t = &observed;
            processor.ProcessChunk(plane[0][c], plane[1][c], plane[2][c], plane[3][c], count);
          }
          size_t leading = 0;
          for (size_t lane = 0; lane < 8; ++lane) {
            const auto r = observed.values[lane];
            unsigned t = 0, n = 0, b = 0;
            EncodeHybridUint000(r, &t, &n, &b);
            residual.push_back(r & 255); residual.push_back(r >> 8);
            token.push_back(t); nbits.push_back(n);
            extra.push_back(b & 255); extra.push_back(b >> 8);
            if (lane < count && lane == leading && r == 0) ++leading;
          }
          prefix.push_back(c < channels ? leading : 0);
        }
        out << (0x73100000 + index) << '\t' << channels << '\t' << count << '\t'
            << hex(input) << '\t' << hex(residual) << '\t' << hex(token) << '\t'
            << hex(nbits) << '\t' << hex(extra) << '\t' << hex(prefix) << '\n';
        ++index;
      }
  if (!out.good()) return 3;
  std::cout << "OFFICIAL_CHUNK_OBSERVER_PASS cases=" << index << " chunk=" << kChunkSize << '\n';
}
