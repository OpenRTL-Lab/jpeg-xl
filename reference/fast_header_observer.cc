// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
// Reuse the whole official PrepareFrame table observer, then call the original
// WriteTo/BitWriter. Parsing/extracting fields below does not implement a codec.
#define main OfficialPrefixObserverMain
#include "fast_prefix_observer.cc"
#undef main

template <class T> static void Parse(const std::string& text, T* data, size_t count, size_t digits) {
  assert(text.size() == count * digits);
  for (size_t i = 0; i < count; ++i)
    data[i] = T(std::stoull(text.substr(text.size() - (i + 1) * digits, digits), nullptr, 16));
}
int main(int argc, char** argv) {
  if (argc != 2) return 2;
  const std::string tables = std::string(argv[1]) + ".tables.tsv";
  char* arguments[] = {argv[0], const_cast<char*>(tables.c_str())};
  if (OfficialPrefixObserverMain(2, arguments)) return 3;
  std::ifstream in(tables); std::ofstream out(argv[1]);
  std::string line; size_t count = 0;
  while (std::getline(in, line)) {
    std::vector<std::string> fields; std::istringstream stream(line); std::string value;
    while (std::getline(stream, value, '\t')) fields.push_back(value);
    assert(fields.size() == 8 && std::stoull(fields[0]) == count);
    PrefixCode code;
    Parse(fields[4], code.raw_nbits, 19, 2); Parse(fields[5], code.raw_bits, 19, 2);
    Parse(fields[6], code.lz77_nbits, 33, 2); Parse(fields[7], code.lz77_bits, 33, 4);
    BitWriter writer; writer.Allocate(512); code.WriteTo(&writer);
    const size_t length = 8 * writer.bytes_written + writer.bits_in_buffer;
    assert(length <= 358);
    uint8_t bytes[64] = {};
    for (size_t bit = 0; bit < length; ++bit)
      bytes[bit / 8] |= ((writer.data.get()[bit / 8] >> (bit % 8)) & 1) << (bit % 8);
    out << fields[0] << '\t' << fields[1] << '\t' << fields[4] << '\t' << fields[6]
        << '\t' << Hex(bytes, 64, 2) << '\t' << length << '\n';
    ++count;
  }
  if (!out.good() || count != 64) return 4;
  std::cout << "OFFICIAL_HEADER_OBSERVER_PASS cases=" << count << '\n';
  return 0;
}
