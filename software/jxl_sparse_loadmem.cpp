// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
// Compiles only the optional simulation storage adapter, not any RTL model.
#define JXL_SPARSE_LOADMEM
#include "jxl_sparse_ddr.cpp"

#ifdef JXL_LOADMEM_HOST_TEST
// Independent ELF64 PT_LOAD oracle: official FESVR performs the load; this
// parser only checks every loaded byte, BSS zero and untouched addresses.
#include <fstream>
#include <iterator>
#include <vector>
int main(int argc,char **argv) {
    assert(argc==2);initialize_storage();load_official_elf(argv[1]);
    std::ifstream stream(argv[1],std::ios::binary);
    std::vector<uint8_t> file((std::istreambuf_iterator<char>(stream)),{});
    assert(file.size()>=sizeof(Elf64_Ehdr));Elf64_Ehdr elf{};
    std::memcpy(&elf,file.data(),sizeof(elf));
    assert(IS_ELF64(elf) && IS_ELFLE(elf));
    assert(elf.e_phentsize==sizeof(Elf64_Phdr));SparseElfMemory memory;size_t checked=0,segments=0;
    for(unsigned i=0;i<elf.e_phnum;++i) {
        size_t offset=elf.e_phoff+size_t(i)*sizeof(Elf64_Phdr);
        assert(offset+sizeof(Elf64_Phdr)<=file.size());Elf64_Phdr ph{};
        std::memcpy(&ph,file.data()+offset,sizeof(ph));if(ph.p_type!=PT_LOAD)continue;
        assert(ph.p_filesz<=ph.p_memsz && ph.p_offset+ph.p_filesz<=file.size());
        std::vector<uint8_t> actual(ph.p_memsz);memory.read(ph.p_paddr,actual.size(),actual.data());
        for(size_t n=0;n<actual.size();++n)assert(actual[n]==(n<ph.p_filesz?file[ph.p_offset+n]:0));
        // Check via the separate actual RTL/DPI index convention as well.
        // A self-consistent but wrong base subtraction must not pass.
        for(size_t n=0;n<ph.p_memsz;n+=64) {
            assert((ph.p_paddr&63)==0);Line raw{};
            unsigned index=unsigned(((ph.p_paddr+n)&0xffffffffULL)>>6);
            jxl_sparse_read(index,0,raw.data());
            for(size_t b=0;b<64 && n+b<ph.p_memsz;++b)
                assert(uint8_t(raw[b/4]>>((b%4)*8))==actual[n+b]);
        }
        checked+=actual.size();++segments;
    }
    assert(segments && checked>1000000);uint8_t data[64]{};
    memory.read(ddr_base+ddr_size-64,sizeof(data),data);for(auto b:data)assert(b==0);
    unsigned rejected=0;
    for(auto address:{ddr_base-1,ddr_base+ddr_size,UINT64_MAX}) {
        try {memory.write(address,1,data);}catch(const std::out_of_range &){++rejected;}
    }
    try {memory.read(ddr_base+ddr_size-1,2,data);}catch(const std::out_of_range &){++rejected;}
    assert(rejected==4);
    // Masked writes must still work after loader initialization, including
    // preserving the other 63 bytes of an actually loaded ELF cache line.
    Line before{},after{},payload{};svBitVecVal mask[2]={8,0};payload[0]=0x5a000000;
    constexpr unsigned first_index=unsigned(ddr_base>>6);
    jxl_sparse_read(first_index,0,before.data());jxl_sparse_write(first_index,mask,payload.data());
    jxl_sparse_read(first_index,1,after.data());before[0]=(before[0]&0x00ffffff)|0x5a000000;
    assert(before==after);
    // Explicit physical address wrap, as in the generated addr[31:6] ports.
    const uint8_t crossing[4]={0x31,0x42,0x53,0x64};
    memory.write(0xfffffffeULL,4,crossing);Line lower{},upper{};
    jxl_sparse_read((1u<<26)-1,0,lower.data());jxl_sparse_read(0,0,upper.data());
    assert((lower[15]>>16)==0x4231 && (upper[0]&0xffff)==0x6453);
    std::printf("JXL_OFFICIAL_ELF_STORAGE_PASS bytes=%zu segments=%zu rejects=%u\n",checked,segments,rejected);
}
#endif
