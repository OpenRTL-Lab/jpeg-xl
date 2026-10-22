// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
// Official whole preparation tables, original WriteTo/PrepareDCGlobal and
// PrepareHeader. Thin observation, no project encoding algorithm.
#define main OfficialPrefixObserverMain
#include "fast_prefix_observer.cc"
#undef main

template <class T> static void ReadHex(const std::string& text, T* data, size_t n, size_t digits) {
  assert(text.size() == n*digits);
  for (size_t i = 0; i < n; ++i)
    data[i] = T(std::stoull(text.substr(text.size()-(i+1)*digits, digits), nullptr, 16));
}
static std::vector<uint8_t> Capture(const BitWriter& writer, size_t bytes) {
  const size_t n = writer.bytes_written*8+writer.bits_in_buffer;
  assert(n <= bytes*8);
  std::vector<uint8_t> data(bytes, 0);
  for (size_t i = 0; i < n; ++i)
    data[i/8] |= ((writer.data.get()[i/8] >> (i%8)) & 1) << (i%8);
  return data;
}
int main(int argc, char** argv) {
  if (argc != 2) return 2;
  const std::string table_file = std::string(argv[1])+".tables.tsv";
  char* arguments[] = {argv[0], const_cast<char*>(table_file.c_str())};
  if (OfficialPrefixObserverMain(2, arguments)) return 3;
  std::ifstream tables(table_file); std::ofstream out(argv[1]);
  const size_t widths[] = {1, 8, 17, 256}, heights[] = {64, 128, 255, 256};
  const size_t sizes[] = {1, 1023, 1024, 17407, 17408, 1048575};
  std::string line; size_t index = 0;
  for (size_t group = 0; group < 16; ++group) {
    PrefixCode code[4]; std::vector<uint8_t> descriptors; uint16_t lengths[4] = {};
    const size_t ch = group/4+1, width = widths[group%4], height = heights[group%4];
    for (size_t c = 0; c < 4; ++c) {
      if (!std::getline(tables, line)) return 4;
      std::istringstream stream(line); std::vector<std::string> fields; std::string value;
      while (std::getline(stream, value, '\t')) fields.push_back(value);
      assert(fields.size() == 8 && std::stoull(fields[0]) == 4*group+c && std::stoull(fields[1]) == ch);
      ReadHex(fields[4], code[c].raw_nbits, 19, 2); ReadHex(fields[5], code[c].raw_bits, 19, 2);
      ReadHex(fields[6], code[c].lz77_nbits, 33, 2); ReadHex(fields[7], code[c].lz77_bits, 33, 4);
      BitWriter writer; writer.Allocate(512); code[c].WriteTo(&writer);
      lengths[c] = writer.bytes_written*8+writer.bits_in_buffer;
      auto bytes = Capture(writer, 64); descriptors.insert(descriptors.end(), bytes.begin(), bytes.end());
    }
    BitWriter global; PrepareDCGlobal(true, width, height, ch, code, &global);
    const auto global_bytes = Capture(global, 256);
    const size_t global_length = global.bytes_written*8+global.bits_in_buffer;
    for (const auto& dims : {std::pair<size_t, size_t>{width, height}, {1, 1}, {256, 1}, {1, 256}})
    for (size_t size : sizes) {
      JxlFastLosslessFrameState frame;
      frame.width = dims.first; frame.height = dims.second; frame.nb_chans = ch; frame.bitdepth = 8;
      frame.group_sizes = {size};
      JxlFastLosslessPrepareHeader(&frame, 1, 1);
      const auto file_bytes = Capture(frame.header, 32);
      assert(frame.header.bits_in_buffer == 0);
      out << index++ << '\t' << ch << '\t' << frame.width << '\t' << frame.height << '\t' << size
          << '\t' << Hex(descriptors.data(), descriptors.size(), 2) << '\t' << Hex(lengths, 4, 4)
          << '\t' << Hex(global_bytes.data(), global_bytes.size(), 2) << '\t' << global_length
          << '\t' << Hex(file_bytes.data(), file_bytes.size(), 2) << '\t' << frame.header.bytes_written*8 << '\n';
    }
  }
  if (!out.good() || index != 384 || std::getline(tables, line)) return 5;
  std::cout << "OFFICIAL_ENVELOPE_OBSERVER_PASS cases=" << index << '\n';
  return 0;
}
