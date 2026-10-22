// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
// Frozen official processors/collector provide ALL residual and count answers.
#include "lib/jxl/enc_fast_lossless.cc"
#include <array>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <vector>
using namespace default_implementation;

static std::string Hex(const std::vector<uint32_t>& words) {
  std::ostringstream s;
  for (auto i = words.rbegin(); i != words.rend(); ++i)
    s << std::hex << std::setfill('0') << std::setw(8) << *i;
  return s.str();
}
struct Capture {
  ChunkSampleCollector<UpTo8Bits>* collector = nullptr;
  bool emitted = false;
  size_t run = 0, skip = 0;
  std::array<uint16_t, 8> residual{};
  void Chunk(size_t r, const uint16_t* v, size_t s, size_t n) {
    emitted = true; run = r; skip = s;
    std::copy_n(v, n, residual.begin());
    if (collector) collector->Chunk(r, residual.data(), s, n);
  }
  void Finalize(size_t r) { if (collector) collector->Finalize(r); }
};
struct Context {
  uint64_t raw[4][19] = {}, lz[4][33] = {};
  ChunkSampleCollector<UpTo8Bits> collector[4];
  Capture sink[4];
  ChannelRowProcessor<Capture, UpTo8Bits> processor[4]{};
};
int main(int argc, char** argv) {
  static_assert(kChunkSize == 8, "Frozen scalar implementation only");
  if (argc != 3) return 2;
  std::ofstream stim(argv[1]), answers(argv[2]);
  size_t index = 0, output = 0;
  auto result = [&](const Context& s, size_t ctx, size_t ch, size_t tag, size_t samples) {
    std::vector<uint32_t> raw, lz;
    for (size_t c = 0; c < 4; ++c) {
      for (size_t k = 0; k < 19; ++k) raw.push_back(s.raw[c][k]);
      for (size_t k = 0; k < 33; ++k) lz.push_back(s.lz[c][k]);
    }
    answers << output++ << '\t' << ctx << '\t' << ch << '\t' << tag << '\t'
      << samples << '\t' << Hex(raw) << '\t' << Hex(lz) << '\n';
  };
  auto command = [&](size_t ctx, size_t ch, size_t tag, bool empty) {
    stim << index++ << '\t' << (empty ? 0 : 1) << '\t' << ctx << '\t' << ch << '\t'
      << tag << "\t0\t0\t0\t0\t0\t0\t0\n";
  };
  const size_t lengths[] = {1, 2, 7, 16, 32, 64};
  for (size_t ch = 1; ch <= 4; ++ch) {
    Context empty;
    command(0, ch, 0x58300000 + index, true);
    result(empty, 0, ch, 0x58300000 + index - 1, 0);
    for (size_t blocks : lengths) for (size_t pattern = 0; pattern < 5; ++pattern) {
      Context ctx[2];
      const size_t tags[2] = {0x58310000 + index, 0x58320000 + index};
      for (size_t x = 0; x < 2; ++x) {
        command(x, ch, tags[x], false);
        for (size_t c = 0; c < 4; ++c) {
          ctx[x].collector[c] = {ctx[x].raw[c], ctx[x].lz[c]};
          ctx[x].sink[c].collector = &ctx[x].collector[c];
          ctx[x].processor[c].t = &ctx[x].sink[c];
        }
      }
      for (size_t b = 0; b < blocks; ++b) for (size_t x = 0; x < 2; ++x) {
        std::vector<uint32_t> tokens, emissions, runs, skips;
        for (size_t c = 0; c < 4; ++c) {
          int16_t row[8] = {}, neighbors[8] = {};
          for (size_t lane = 0; lane < 8; ++lane) {
            // Inputs only: zero runs, collision-heavy and bounded signed data.
            if (pattern == 1) row[lane] = b % 3 ? 0 : int16_t(lane + c + x + 1);
            if (pattern == 2) row[lane] = int16_t((b + lane + c + x) % 251 - 125);
            if (pattern == 3) row[lane] = lane < c ? 0 : int16_t(1 + (b & 1));
            if (pattern == 4 && b + 1 == blocks) row[lane] = int16_t(lane + 1);
          }
          Capture independent;
          ChannelRowProcessor<Capture, UpTo8Bits> plain{};
          plain.t = &independent;
          if (c < ch) plain.ProcessChunk(row, neighbors, neighbors, neighbors, 8);
          for (size_t lane = 0; lane < 8; ++lane) {
            unsigned t, n, v;
            EncodeHybridUint000(independent.residual[lane], &t, &n, &v);
            tokens.push_back(t);
          }
          auto& sink = ctx[x].sink[c];
          sink.emitted = false; sink.run = 0; sink.skip = 0;
          if (c < ch) {
            ctx[x].processor[c].ProcessChunk(row, neighbors, neighbors, neighbors, 8);
            if (b + 1 == blocks) ctx[x].processor[c].Finalize();
          }
          emissions.push_back(sink.emitted); runs.push_back(sink.run); skips.push_back(sink.skip);
        }
        stim << index++ << "\t2\t" << x << '\t' << ch << '\t' << tags[x] << '\t'
          << (b == 0) << '\t' << (b + 1 == blocks) << "\t8\t" << Hex(tokens)
          << '\t' << Hex(emissions) << '\t' << Hex(runs) << '\t' << Hex(skips) << '\n';
        if (b + 1 == blocks) result(ctx[x], x, ch, tags[x], blocks * 8);
      }
    }
  }
  if (!stim.good() || !answers.good()) return 3;
  std::cout << "OFFICIAL_STATS_OBSERVER_PASS records=" << index << " results=" << output << '\n';
}
