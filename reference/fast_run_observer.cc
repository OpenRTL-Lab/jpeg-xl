// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
// Calls the frozen official processor; no replacement RLE or codec model.
#include "lib/jxl/enc_fast_lossless.cc"
#include <array>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <vector>
using namespace default_implementation;

static std::string Hex(const std::vector<uint8_t>& data) {
  std::ostringstream text;
  for (auto i = data.rbegin(); i != data.rend(); ++i)
    text << std::hex << std::setfill('0') << std::setw(2) << unsigned(*i);
  return text.str();
}

struct Capture {
  bool emit = false;
  size_t run = 0, skip = 0, count = 0;
  bool sampling = false;
  std::array<uint16_t, 8> residual{};
  void Chunk(size_t r, const uint16_t* values, size_t s, size_t n) {
    emit = true; run = r; skip = s;
    std::copy_n(values, n, residual.begin());
  }
  void Finalize(size_t r) {
    if (!sampling && r) { emit = true; run = r; skip = count; }
  }
};

int main(int argc, char** argv) {
  static_assert(kChunkSize == 8, "Requires frozen scalar build");
  if (argc != 2) return 2;
  std::ofstream out(argv[1]);
  Capture sink[2][4];
  ChannelRowProcessor<Capture, UpTo8Bits> processor[2][4]{};
  size_t index = 0;
  auto step = [&](size_t ctx, size_t channels, bool sampling, bool first,
                  bool last, size_t n, size_t pattern, size_t tag,
                  size_t repeat = 1) {
    std::vector<uint8_t> residual, prefixes, emissions, runs, skips;
    for (size_t c = 0; c < 4; ++c) {
      int16_t row[8] = {}, neighbor[8] = {};
      for (size_t lane = 0; lane < 8; ++lane) {
        if (pattern == 1) row[lane] = lane < (c + 2) ? 0 : int16_t(1 + lane * 13 + c);
        if (pattern == 2) row[lane] = int16_t(1 + lane * 13 + c);
        if (pattern == 3) row[lane] = lane + 1 == n ? 1 : 0;
        if (pattern == 4) row[lane] = c & 1 ? 0 : int16_t(lane + 1);
      }
      Capture observed;
      ChannelRowProcessor<Capture, UpTo8Bits> independent{};
      independent.t = &observed;
      if (c < channels) independent.ProcessChunk(row, neighbor, neighbor, neighbor, n);
      size_t prefix = 0;
      for (size_t lane = 0; lane < 8; ++lane) {
        auto r = observed.residual[lane];
        residual.push_back(r & 255); residual.push_back(r >> 8);
        if (lane < n && lane == prefix && r == 0) ++prefix;
      }
      prefixes.push_back(c < channels ? prefix : 0);
      sink[ctx][c] = Capture{};
      sink[ctx][c].sampling = sampling; sink[ctx][c].count = n;
      if (first) processor[ctx][c] = {};
      processor[ctx][c].t = &sink[ctx][c];
      if (c < channels) {
        for (size_t rep = 0; rep < repeat; ++rep)
          processor[ctx][c].ProcessChunk(row, neighbor, neighbor, neighbor, n);
        if (last) processor[ctx][c].Finalize();
      }
      const auto& expected = sink[ctx][c];
      emissions.push_back(expected.emit);
      for (size_t b = 0; b < 4; ++b) runs.push_back((expected.run >> (8 * b)) & 255);
      skips.push_back(expected.skip);
    }
    out << index++ << '\t' << ctx << '\t' << channels << '\t' << first << '\t'
        << last << '\t' << sampling << '\t' << n << '\t' << tag << '\t' << repeat
        << '\t' << Hex(residual) << '\t' << Hex(prefixes) << '\t' << Hex(emissions)
        << '\t' << Hex(runs) << '\t' << Hex(skips) << '\n';
  };
  for (size_t channels = 1; channels <= 4; ++channels)
    for (size_t sampling = 0; sampling <= 1; ++sampling)
      for (size_t count = 1; count <= 8; ++count) {
        size_t tag = 0x58200000 + index;
        // Both contexts active, interleaved; one is encoding, one sampling.
        step(0, channels, sampling, true, false, 8, 0, tag);
        step(1, channels, !sampling, true, false, count, 2, tag + 1);
        step(0, channels, sampling, false, false, count, 0, tag);
        step(1, channels, !sampling, false, false, 8, 0, tag + 1);
        step(0, channels, sampling, false, false, count, 1, tag);
        step(1, channels, !sampling, false, false, count, 3, tag + 1);
        step(0, channels, sampling, false, true, count, 4, tag);
        step(1, channels, !sampling, false, true, count, 0, tag + 1);
      }
  // Compress repeated observations, not the codec: each of 8190 identical
  // no-event records has the same official observation. DUT still runs 8192.
  for (size_t sampling = 0; sampling <= 1; ++sampling) {
    const auto tag = 0x58210000 + sampling;
    step(0, 4, sampling, true, false, 8, 0, tag);
    step(0, 4, sampling, false, false, 8, 0, tag, 8190);
    step(0, 4, sampling, false, true, 8, 0, tag);
  }
  if (!out.good()) return 3;
  std::cout << "OFFICIAL_RUN_OBSERVER_PASS records=" << index << '\n';
}
