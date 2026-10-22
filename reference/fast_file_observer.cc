// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
// Observe the frozen official whole encoder and its real channel BitWriters.
// No independent encoding, predictor, codebook, or file-header model.
#include "lib/jxl/enc_fast_lossless.cc"
#include <fstream>
#include <iostream>
#include <string>
#include <vector>

static void Save(const std::string& path, const uint8_t* data, size_t size) {
  std::ofstream out(path, std::ios::binary);
  if (size) out.write(reinterpret_cast<const char*>(data), size);
  assert(out.good());
}
int main(int argc, char** argv) {
  if (argc != 2) return 2;
  const std::string root = argv[1]; std::ofstream jobs(root+".jobs.tsv");
  const size_t widths[] = {1, 7, 8, 17, 256, 1, 255, 256};
  const size_t heights[] = {1, 3, 128, 255, 1, 256, 2, 256};
  size_t index = 0, total = 0;
  for (size_t ch = 1; ch <= 4; ++ch) for (size_t pattern = 0; pattern < 8; ++pattern) {
    const size_t w = widths[pattern], h = heights[pattern];
    std::vector<uint8_t> raw(w*h*ch); uint32_t rng = 0x47198265;
    for (size_t y = 0; y < h; ++y) for (size_t x = 0; x < w; ++x)
      for (size_t c = 0; c < ch; ++c) {
        rng ^= rng<<13; rng ^= rng>>17; rng ^= rng<<5;
        raw[(y*w+x)*ch+c] = pattern==0 ? 0 : pattern==1 ? 255 :
          pattern==2 ? uint8_t((x&1)*255) : pattern==7 ? uint8_t(rng) :
          uint8_t((x*131+y*29+c*53)^(x*y));
      }
    Save(root+".raw-"+std::to_string(index)+".bin",raw.data(),raw.size());
    FJxlFrameInput source(raw.data(), w*ch, ch, 8);
    auto* frame = JxlFastLosslessPrepareFrame(source.GetInputSource(),w,h,ch,8,false,1,true);
    assert(frame && frame->collided && frame->group_data.size()==1);
    assert(JxlFastLosslessProcessFrame(frame,true,nullptr,nullptr,nullptr));
    size_t lengths[4] = {};
    for (size_t c = 0; c < 4; ++c) {
      const auto& writer = frame->group_data[0][c];
      lengths[c] = writer.bytes_written*8+writer.bits_in_buffer;
      std::vector<uint8_t> bytes((lengths[c]+7)/8,0);
      if (writer.bytes_written) std::copy_n(writer.data.get(),writer.bytes_written,bytes.data());
      if (writer.bits_in_buffer) bytes[writer.bytes_written] = uint8_t(writer.buffer);
      Save(root+".channel-"+std::to_string(index)+"-"+std::to_string(c)+".bin",bytes.data(),bytes.size());
    }
    JxlFastLosslessPrepareHeader(frame,1,1);
    std::vector<uint8_t> output(JxlFastLosslessMaxRequiredOutput(frame));
    size_t written = 0, n;
    while ((n=JxlFastLosslessWriteOutput(frame,output.data()+written,output.size()-written))) written+=n;
    uint8_t* direct = nullptr;
    const size_t direct_size = JxlFastLosslessEncode(raw.data(),w,w*ch,h,ch,8,false,1,&direct,nullptr,nullptr);
    assert(direct_size==written && memcmp(direct,output.data(),written)==0);
    assert(written>=2 && output[0]==0xff && output[1]==0x0a);
    Save(root+".file-"+std::to_string(index)+".bin",output.data(),written);
    jobs<<index<<'\t'<<ch<<'\t'<<w<<'\t'<<h<<'\t'<<(0x7a100000+index)<<'\t'<<written;
    for (size_t c = 0; c < 4; ++c) jobs<<'\t'<<lengths[c];
    jobs<<'\n'; total+=written; ++index;
    free(direct); JxlFastLosslessFreeFrameState(frame);
  }
  assert(jobs.good() && index==32);
  std::cout<<"OFFICIAL_FILE_OBSERVER_PASS images="<<index<<" bytes="<<total<<'\n';
  return 0;
}
