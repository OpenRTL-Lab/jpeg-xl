// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
// Included after the sparse storage declaration. Test-only FESVR ELF transport,
// equivalent to the fixed upstream SimDRAM.cc load_elf/memif_t adapter.
#include <fesvr/elfloader.h>
#include <vpi_user.h>
#include <cstring>
#include <stdexcept>
#include <string>
static constexpr uint64_t ddr_base=0x80000000ULL,ddr_size=0x100000000ULL;
static void check_physical(uint64_t address,size_t length) {
    if(address<ddr_base || address-ddr_base>ddr_size || length>ddr_size-(address-ddr_base))
        throw std::out_of_range("ELF load outside fixed DDR");
}
class SparseElfMemory : public memif_t {
public:
    size_t written=0;
    SparseElfMemory():memif_t(nullptr) {}
    endianness_t get_target_endianness() const override {return endianness_little;}
    void write(addr_t address,size_t length,const void *source) override {
        check_physical(address,length);
        auto bytes=static_cast<const uint8_t *>(source);
        for(size_t i=0;i<length;++i) {
            // Actual generated AXI4RAM uses AW/AR addr[31:6], NOT a
            // base-relative index. The fixed 4GiB region wraps at 4GiB.
            uint64_t offset=(address+i)&0xffffffffULL;
            auto &line=lines[uint32_t(offset>>6)];
            unsigned word=(offset&63)/4,shift=(offset&3)*8;
            line[word]=(line[word]&~(0xffu<<shift))|(uint32_t(bytes[i])<<shift);
        }
        written+=length;
    }
    void read(addr_t address,size_t length,void *destination) override {
        check_physical(address,length);auto bytes=static_cast<uint8_t *>(destination);
        for(size_t i=0;i<length;++i) {
            uint64_t offset=(address+i)&0xffffffffULL;auto it=lines.find(uint32_t(offset>>6));
            bytes[i]=it==lines.end()?0:uint8_t(it->second[(offset&63)/4]>>((offset&3)*8));
        }
    }
};
static bool storage_initialized=false;
static void load_official_elf(const std::string &filename) {
    if(filename.empty())throw std::invalid_argument("Empty +loadmem ELF");
    SparseElfMemory memory;reg_t entry=0;
    load_elf(filename.c_str(),&memory,&entry,0,64);
    check_physical(entry,1);
    std::printf("JXL_TEST_ELF_LOAD entry=0x%llx bytes=%zu lines=%zu official_fesvr=1\n",
        static_cast<unsigned long long>(entry),memory.written,lines.size());
    std::fflush(stdout);
}
static void initialize_storage() {
    if(storage_initialized)return;
    storage_initialized=true;
#ifndef JXL_LOADMEM_HOST_TEST
    s_vpi_vlog_info info;if(!vpi_get_vlog_info(&info))std::abort();
    std::string filename;
    for(int i=1;i<info.argc;++i) {
        std::string arg(info.argv[i]);
        if(arg.rfind("+loadmem=",0)==0) {
            if(!filename.empty())throw std::invalid_argument("Duplicate +loadmem");
            filename=arg.substr(std::strlen("+loadmem="));
            if(filename.empty())throw std::invalid_argument("Empty +loadmem ELF");
        }
    }
    if(!filename.empty())load_official_elf(filename);
#endif
}
