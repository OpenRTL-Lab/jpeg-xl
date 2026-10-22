// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
// TEST STORAGE ONLY: no image processing or bypass of CPU/cache/AXI accesses.
// The explicit optional loader follows upstream SimDRAM's official ELF path.
#include "svdpi.h"
#include <array>
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <unordered_map>
using Line=std::array<uint32_t,16>;
static std::unordered_map<uint32_t,Line> lines;
#ifdef JXL_SPARSE_LOADMEM
#include "jxl_sparse_loadmem.h"
#else
static void initialize_storage() {}
#endif
extern "C" void jxl_sparse_write(unsigned address,const svBitVecVal *mask,const svBitVecVal *data) {
    initialize_storage();
    assert(address<(1u<<26));
    const uint64_t enables=uint64_t(mask[0])|(uint64_t(mask[1])<<32);
    if(!enables)return;
    auto &line=lines[address];
    for(unsigned b=0;b<64;++b) if((enables>>b)&1) {
        unsigned shift=(b%4)*8,word=b/4;
        line[word]=(line[word]&~(0xffu<<shift))|(data[word]&(0xffu<<shift));
    }
}
extern "C" void jxl_sparse_read(unsigned address,unsigned revision,svBitVecVal *data) {
    initialize_storage();
    (void)revision;assert(address<(1u<<26));auto found=lines.find(address);
    for(unsigned i=0;i<16;++i)data[i]=found==lines.end()?0:found->second[i];
}
#ifdef JXL_SPARSE_HOST_TEST
#include <map>
int main() {
    // Independent byte-addressed storage oracle, including every individual
    // strobe, zero masks, all 26 address bits and widely separated aliases.
    std::map<uint32_t,std::array<uint8_t,64>> expected;
    uint32_t rng=0x6135ab7;auto next=[&](){rng^=rng<<13;rng^=rng>>17;rng^=rng<<5;return rng;};
    for(unsigned n=0;n<20000;++n) {
        unsigned address=n<26?(1u<<n):(next()&((1u<<26)-1));
        if(n>=64 && n%4==0)address=17;
        uint64_t mask=n<64?uint64_t(1)<<n:(uint64_t(next())<<32)|next();
        if(n>=64 && n%11==0)mask=0;
        Line data{},actual{};for(auto &v:data)v=next();
        svBitVecVal strobe[2]={uint32_t(mask),uint32_t(mask>>32)};
        jxl_sparse_write(address,strobe,data.data());
        auto &bytes=expected[address];
        for(unsigned b=0;b<64;++b)if(mask&(uint64_t(1)<<b))bytes[b]=data[b/4]>>(8*(b%4));
        jxl_sparse_read(address,n,actual.data());
        for(unsigned b=0;b<64;++b)assert(uint8_t(actual[b/4]>>(8*(b%4)))==bytes[b]);
    }
    for(const auto &p:expected) {
        Line actual{};jxl_sparse_read(p.first,0,actual.data());
        for(unsigned b=0;b<64;++b)assert(uint8_t(actual[b/4]>>(8*(b%4)))==p.second[b]);
    }
    std::printf("JXL_SPARSE_RAM_SELFTEST_PASS writes=20000 address_bits=26 bytes=64\n");
}
#endif
