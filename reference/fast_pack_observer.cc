// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
// Only official prediction, RLE, hybrid fields, PrefixCode, ChunkEncoder and
// BitWriter. Extracting added bits below is a transport operation, not a codec.
#include "lib/jxl/enc_fast_lossless.cc"
#include <array>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <vector>
using namespace default_implementation;

template <class T> static std::string Hex(const std::vector<T>& values, size_t digits) {
  std::ostringstream s;
  for (auto i = values.rbegin(); i != values.rend(); ++i)
    s << std::hex << std::setfill('0') << std::setw(digits) << uint64_t(*i);
  return s.str();
}
struct ResidualCapture {
  std::array<uint16_t, 8> values{};
  void Chunk(size_t, uint16_t* p, size_t, size_t n) { std::copy_n(p, n, values.begin()); }
  void Finalize(size_t) {}
};
struct EncodeCapture {
  const PrefixCode* code = nullptr;
  BitWriter* writer = nullptr;
  size_t run = 0, skip = 0, count = 0;
  bool emitted = false;
  void Chunk(size_t r, uint16_t* p, size_t s, size_t n) {
    assert(!emitted); emitted = true; run = r; skip = s;
    ChunkEncoder<UpTo8Bits> encoder;
    encoder.code = code; encoder.output = writer;
    encoder.Chunk(r, p, s, n);
  }
  void Finalize(size_t r) {
    if (r) {
      assert(!emitted); emitted = true; run = r; skip = count;
      ChunkEncoder<UpTo8Bits>::EncodeRle(r, *code, *writer);
    }
  }
};
static size_t Position(const BitWriter& w) { return 8 * w.bytes_written + w.bits_in_buffer; }
static std::vector<uint8_t> Extract(const BitWriter& w, size_t begin, size_t end, size_t bytes) {
  assert(end >= begin && end - begin <= 8 * bytes && end <= Position(w));
  std::vector<uint8_t> data(bytes, 0);
  for (size_t bit = begin; bit < end; ++bit)
    data[(bit - begin) / 8] |= ((w.data.get()[bit / 8] >> (bit % 8)) & 1) << ((bit - begin) % 8);
  return data;
}
int main(int argc, char** argv) {
  static_assert(kChunkSize == 8, "Frozen scalar build required");
  if (argc != 2) return 2;
  std::ofstream out(argv[1]), streams(std::string(argv[1]) + ".streams.tsv");
  size_t index = 0;
  for (size_t ch = 1; ch <= 4; ++ch) for (size_t mode = 0; mode < 2; ++mode) {
    const size_t w = mode ? 256 : 8, h = mode ? 256 : 128;
    std::vector<uint8_t> pixels(w * h * ch);
    for (size_t y = 0; y < h; ++y) for (size_t x = 0; x < w; ++x)
      for (size_t c = 0; c < ch; ++c)
        pixels[(y * w + x) * ch + c] = mode ? uint8_t((x * 131 + y * 29 + c * 53) ^ (x * y)) : 0;
    FJxlFrameInput input(pixels.data(), w * ch, ch, 8);
    auto* official = JxlFastLosslessPrepareFrame(input.GetInputSource(), w, h, ch, 8, false, 1, true);
    if (!official) return 3;
    for (size_t tail = 1; tail <= 9; ++tail) {
      BitWriter writer[4]; EncodeCapture capture[4];
      ChannelRowProcessor<EncodeCapture, UpTo8Bits> processor[4]{};
      for (size_t c = 0; c < 4; ++c) {
        writer[c].Allocate(1 << 20);
        capture[c].code = &official->hcode[c]; capture[c].writer = &writer[c];
        processor[c].t = &capture[c];
      }
      const size_t steps = tail == 9 ? 8192 : 6;
      const size_t tag = 0x58300000 + index;
      for (size_t step = 0; step < steps; ++step) {
        const bool first = step == 0, last = step + 1 == steps;
        const size_t n = last && tail < 9 ? tail : 8;
        const bool record = tail != 9 || last;
        std::vector<uint8_t> tokens, extraLengths, emits, skips, rawLengths, rawBits, lzLengths, bits;
        std::vector<uint16_t> extras, lzBits, lengths;
        std::vector<uint32_t> runs;
        for (size_t c = 0; c < 4; ++c) {
          int16_t row[8] = {}, neighbor[8] = {};
          if (tail < 9) for (size_t lane = 0; lane < 8; ++lane) {
            if (step == 2) row[lane] = lane < c + 2 ? 0 : int16_t(1 + lane * 13 + c);
            if (step == 3) row[lane] = int16_t((lane & 1 ? -1 : 1) * int(1 + lane * 29 + c));
          }
          ResidualCapture residual;
          ChannelRowProcessor<ResidualCapture, UpTo8Bits> independent{};
          independent.t = &residual;
          if (record && c < ch) independent.ProcessChunk(row, neighbor, neighbor, neighbor, n);
          const size_t before = Position(writer[c]);
          capture[c].emitted = false; capture[c].run = 0; capture[c].skip = 0; capture[c].count = n;
          if (c < ch) {
            processor[c].ProcessChunk(row, neighbor, neighbor, neighbor, n);
            if (last) processor[c].Finalize();
          }
          if (!record) continue;
          const size_t after = Position(writer[c]);
          assert(after - before <= 256);
          for (size_t lane = 0; lane < 8; ++lane) {
            uint32_t token, nb, extra;
            EncodeHybridUint000(residual.values[lane], &token, &nb, &extra);
            tokens.push_back(token); extraLengths.push_back(nb); extras.push_back(extra);
          }
          emits.push_back(capture[c].emitted); runs.push_back(capture[c].run); skips.push_back(capture[c].skip);
          const auto& code = official->hcode[c];
          rawLengths.insert(rawLengths.end(), code.raw_nbits, code.raw_nbits + 19);
          rawBits.insert(rawBits.end(), code.raw_bits, code.raw_bits + 19);
          lzLengths.insert(lzLengths.end(), code.lz77_nbits, code.lz77_nbits + 33);
          lzBits.insert(lzBits.end(), code.lz77_bits, code.lz77_bits + 33);
          auto added = Extract(writer[c], before, after, 32);
          bits.insert(bits.end(), added.begin(), added.end()); lengths.push_back(after - before);
          streams << index << '\t' << c << '\t' << before << '\t' << after << '\t'
                  << Hex(Extract(writer[c], 0, after, (after + 7) / 8), 2) << '\n';
        }
        if (!record) continue;
        out << index++ << '\t' << ((tag >> 1) & 1) << '\t' << ch << '\t' << first << '\t' << last
            << '\t' << n << '\t' << tag << '\t' << Hex(tokens, 2) << '\t' << Hex(extraLengths, 2)
            << '\t' << Hex(extras, 4) << '\t' << Hex(emits, 2) << '\t' << Hex(runs, 8)
            << '\t' << Hex(skips, 2) << '\t' << Hex(rawLengths, 2) << '\t' << Hex(rawBits, 2)
            << '\t' << Hex(lzLengths, 2) << '\t' << Hex(lzBits, 4) << '\t' << Hex(bits, 2)
            << '\t' << Hex(lengths, 4) << '\n';
      }
    }
    JxlFastLosslessFreeFrameState(official);
  }
  if (!out.good() || !streams.good()) return 4;
  std::cout << "OFFICIAL_PACK_OBSERVER_PASS records=" << index << '\n';
}
