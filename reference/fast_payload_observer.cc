// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
// Read actual official writer boundaries, never parse or reproduce its codec.
#include "lib/jxl/enc_fast_lossless.cc"
#include <fstream>
#include <iostream>
#include <iterator>
#include <sstream>
#include <string>
#include <vector>

static std::vector<uint8_t> Read(const std::string& path) {
  std::ifstream in(path, std::ios::binary); assert(in.good());
  return {std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>()};
}
static size_t Bits(const BitWriter& writer) {
  return writer.bytes_written * 8 + writer.bits_in_buffer;
}
int main(int argc, char** argv) {
  if (argc != 3) return 2;
  const std::string prefix = argv[1]; std::ifstream jobs(prefix + ".jobs.tsv");
  std::ofstream meta(argv[2]); std::string line; size_t count = 0;
  while (std::getline(jobs, line)) {
    std::istringstream row(line); size_t index, ch, w, h, tag, size;
    row >> index >> ch >> w >> h >> tag >> size; assert(row.good());
    const auto raw = Read(prefix + ".raw-" + std::to_string(index) + ".bin");
    const auto expected = Read(prefix + ".file-" + std::to_string(index) + ".bin");
    assert(raw.size() == w * h * ch && expected.size() == size);
    FJxlFrameInput input(raw.data(), w * ch, ch, 8);
    auto* frame = JxlFastLosslessPrepareFrame(input.GetInputSource(), w, h, ch, 8, false, 1, true);
    assert(frame && frame->collided && frame->group_data.size() == 1);
    size_t before[4], after[4];
    for (size_t c = 0; c < 4; ++c) before[c] = Bits(frame->group_data[0][c]);
    assert(JxlFastLosslessProcessFrame(frame, true, nullptr, nullptr, nullptr));
    for (size_t c = 0; c < 4; ++c) {
      after[c] = Bits(frame->group_data[0][c]); assert(after[c] >= before[c]);
    }
    JxlFastLosslessPrepareHeader(frame, 1, 1);
    const size_t header_bits = Bits(frame->header); assert(header_bits % 8 == 0);
    size_t offset = header_bits, payload_plus_one = 0, payload_bits = 0;
    for (size_t c = 0; c < ch; ++c) {
      if (!payload_plus_one && after[c] > before[c]) payload_plus_one = offset + before[c] + 1;
      payload_bits += after[c] - before[c]; offset += after[c];
    }
    std::vector<uint8_t> output(JxlFastLosslessMaxRequiredOutput(frame)); size_t written = 0, n;
    while ((n = JxlFastLosslessWriteOutput(frame, output.data() + written, output.size() - written))) written += n;
    assert(written == size && memcmp(output.data(), expected.data(), written) == 0);
    assert((payload_plus_one == 0) == (payload_bits == 0));
    assert(!payload_plus_one || payload_plus_one <= written * 8);
    meta << index << '\t' << header_bits << '\t' << before[0] << '\t'
         << payload_plus_one << '\t' << payload_bits << '\t' << written << '\n';
    JxlFastLosslessFreeFrameState(frame); ++count;
  }
  assert(jobs.eof() && meta.good() && count == 32);
  std::cout << "OFFICIAL_PAYLOAD_OBSERVER_PASS images=" << count << '\n';
}
