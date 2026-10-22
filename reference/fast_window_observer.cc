// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
// Image fixtures and thin observation of original ProcessImageArea / processor.
// No replacement predictor, color conversion, entropy model or codec.
#include "lib/jxl/enc_fast_lossless.cc"
#include <array>
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
struct ObservedResidual {
  std::array<uint16_t, 8> values{};
  void Chunk(size_t, const uint16_t* r, size_t, size_t n) { std::copy_n(r, n, values.begin()); }
  void Finalize(size_t) {}
};
struct ObserveRows {
  std::vector<std::array<uint16_t, 8>> blocks;
  void ProcessRow(const int16_t* row, const int16_t* left, const int16_t* top,
                  const int16_t* tl, size_t n) {
    for (size_t x = 0; x < n; x += kChunkSize) {
      ObservedResidual result;
      ChannelRowProcessor<ObservedResidual, UpTo8Bits> original{};
      original.t = &result;
      original.ProcessChunk(row+x, left+x, top+x, tl+x, std::min(kChunkSize, n-x));
      blocks.push_back(result.values);
    }
  }
  void Finalize() {}
};
struct Block {
  size_t batch, ctx, tag, channels, n, flags, current, top;
  std::array<uint16_t, 32> residual{}, extra{};
  std::array<uint8_t, 32> token{}, nbits{};
  std::array<uint8_t, 4> prefix{};
};
int main(int argc, char** argv) {
  static_assert(kChunkSize == 8, "frozen scalar blocks");
  if (argc != 2) return 2;
  std::ofstream out(argv[1]), loads(std::string(argv[1])+".loads.tsv");
  std::ofstream commands(std::string(argv[1])+".commands.tsv");
  const size_t widths[] = {1,7,8,9,15,17,17,63,65,256,2,3,4,5,6};
  const size_t heights[] = {1,3,2,5,4,128,256,3,3,1,3,3,3,3,3};
  size_t batch = 0, index = 0, writes = 0;
  for (size_t ch=1; ch<=4; ++ch) for (size_t dim=0; dim<15; ++dim, ++batch) {
    size_t w=widths[dim], h=heights[dim];
    std::array<std::vector<uint8_t>,2> pixels;
    std::array<size_t,2> base{};
    for (size_t ctx=0; ctx<2; ++ctx) {
      pixels[ctx].resize(w*h*ch);
      uint32_t random=uint32_t(0x159af130+batch*37+ctx);
      for (size_t y=0; y<h; ++y) for (size_t x=0; x<w; ++x) for (size_t c=0; c<ch; ++c) {
        random ^= random<<13; random ^= random>>17; random ^= random<<5;
        uint8_t v=uint8_t(random);
        if (dim%4==0) v=ctx ? 255 : 0;
        if (dim%4==1) v=uint8_t(x*131+y*29+c*53+ctx*17);
        if (dim%4==2) v=uint8_t((x+c+y+ctx)%2*255);
        pixels[ctx][(y*w+x)*ch+c]=v;
      }
      size_t words=(pixels[ctx].size()+63)/64;
      base[ctx]=ctx ? 262144-words*64 : 0;
      for (size_t word=0; word<words; ++word) {
        std::array<uint8_t,64> raw{};
        for (size_t k=0; k<64; ++k) if (word*64+k<pixels[ctx].size()) raw[k]=pixels[ctx][word*64+k];
        loads << batch << '\t' << ctx << '\t' << base[ctx]/64+word << '\t' << Hex(raw.data(),64,2) << '\n';
        ++writes;
      }
    }
    // Mode 0 is full encoding scan. Mode 1 is the exact official sample area.
    for (size_t sampling=0; sampling<2; ++sampling) {
      size_t count=sampling ? 2*h/256 : h, y0=sampling ? (h-count)/2 : 0;
      size_t xs=sampling ? w/8*8 : w;
      const bool empty=count==0 || xs==0;
      const size_t endIndex=index+(empty ? 0 : 2*count*((xs+7)/8));
      for (size_t ctx=0; ctx<2; ++ctx) {
        commands << 2*batch+sampling << '\t' << ctx << '\t'
          << 0x78100000+batch*4+sampling*2+ctx << '\t' << ch << '\t' << w << '\t' << h
          << '\t' << base[ctx] << '\t' << sampling << '\t' << empty << '\t' << endIndex << '\n';
      }
      if (empty) continue;
      std::array<std::vector<Block>,2> blocks;
      for (size_t ctx=0; ctx<2; ++ctx) {
        ObserveRows captured[4];
        ProcessImageArea<ObserveRows,UpTo8Bits>(pixels[ctx].data(),0,y0,xs,sampling,
          sampling+count,w*ch,UpTo8Bits(8),ch,false,captured);
        size_t at=0;
        for (size_t row=0; row<count; ++row) for (size_t x=0; x<xs; x+=8,++at) {
          size_t y=y0+sampling+row, n=std::min(size_t(8),xs-x);
          Block b{}; b.batch=batch; b.ctx=ctx; b.tag=0x78100000+batch*4+sampling*2+ctx;
          b.channels=ch; b.n=n;
          b.flags=(x==0) | ((y==0)<<1) | ((row==0 && x==0)<<2) |
            ((row+1==count && x+n==xs)<<3) | (sampling<<4);
          b.current=base[ctx]+(y*w+x)*ch;
          b.top=base[ctx]+((y ? y-1 : 0)*w+x)*ch;
          for (size_t c=0; c<ch; ++c) {
            if (captured[c].blocks.size()!=count*((xs+7)/8)) return 3;
            size_t leading=0;
            for (size_t lane=0; lane<n; ++lane) {
              auto value=captured[c].blocks[at][lane]; size_t k=c*8+lane;
              b.residual[k]=value;
              uint32_t token,nbits,bits;
              EncodeHybridUint000(value,&token,&nbits,&bits);
              b.token[k]=token; b.nbits[k]=nbits; b.extra[k]=bits;
              if (lane==leading && value==0) ++leading;
            }
            b.prefix[c]=leading;
          }
          blocks[ctx].push_back(b);
        }
      }
      if (blocks[0].size()!=blocks[1].size()) return 4;
      for (size_t b=0; b<blocks[0].size(); ++b) for (size_t ctx=0; ctx<2; ++ctx) {
        const auto& v=blocks[ctx][b];
        out << index++ << '\t' << v.batch << '\t' << v.ctx << '\t' << v.tag << '\t'
            << v.channels << '\t' << v.n << '\t' << v.flags << '\t' << v.current << '\t' << v.top
            << '\t' << Hex(v.residual.data(),32,4) << '\t' << Hex(v.token.data(),32,2)
            << '\t' << Hex(v.nbits.data(),32,2) << '\t' << Hex(v.extra.data(),32,4)
            << '\t' << Hex(v.prefix.data(),4,2) << '\n';
      }
    }
  }
  if (!out.good() || !loads.good() || !commands.good()) return 5;
  std::cout << "OFFICIAL_WINDOW_OBSERVER_PASS batches=" << batch << " images=" << batch*2
            << " blocks=" << index << " writes=" << writes << '\n';
  return 0;
}
