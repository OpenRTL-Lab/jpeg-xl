// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
#include "VJxlDmaBridge.h"
#include "verilated.h"
#include <array>
#include <cstdio>
#include <map>
#include <stdexcept>

static void check(bool b,const char *m) { if(!b) throw std::runtime_error(m); }
struct Test {
    VJxlDmaBridge d;unsigned cycle=0;bool stall=false;
    std::map<unsigned,uint64_t> requests;
    std::map<unsigned,bool> reads,writes;
    Test() {
        d.reset=1;d.io_native_read_valid=0;d.io_native_write_valid=0;
        d.io_native_readResponse_ready=1;d.io_native_writeResponse_ready=1;
        d.io_tl_d_valid=0;for(unsigned i=0;i<5;++i)tick();d.reset=0;tick();
    }
    void tick() {
        check(++cycle<10000,"DMA_CYCLE_GUARD");d.io_tl_a_ready=!stall || cycle%11>5;
        d.clock=0;d.eval();
        if(!d.reset) {
            if(d.io_tl_a_valid && d.io_tl_a_ready) {
                unsigned source=d.io_tl_a_bits_source;
                check(!requests.count(source) && d.io_tl_a_bits_size==6 && d.io_tl_a_bits_mask==UINT64_MAX,"DMA_A_METADATA");
                requests[source]=d.io_tl_a_bits_address;
                if(source>=16)check(d.io_tl_a_bits_data[0]==(1u<<(source-16)),"DMA_WRITE_DATA");
            }
            if(d.io_native_readResponse_valid && d.io_native_readResponse_ready) {
                unsigned id=d.io_native_readResponse_bits_id;
                check(!reads.count(id),"DMA_READ_DUPLICATE");reads[id]=d.io_native_readResponse_bits_fault;
                if(requests.count(id)) check(d.io_native_readResponse_bits_data[0]==100+id,"DMA_READ_DATA");
            }
            if(d.io_native_writeResponse_valid && d.io_native_writeResponse_ready) {
                unsigned id=d.io_native_writeResponse_bits_id;
                check(!writes.count(id),"DMA_WRITE_DUPLICATE");writes[id]=d.io_native_writeResponse_bits_fault;
            }
        }
        d.clock=1;d.eval();check(!Verilated::gotFinish(),"DMA_RTL_ASSERTION");
    }
    void request(unsigned id,bool write,uint64_t address) {
        if(write) {
            d.io_native_write_bits_id=id;d.io_native_write_bits_address=address;
            for(unsigned i=0;i<16;++i)d.io_native_write_bits_data[i]=i?0:1u<<id;
            d.io_native_write_valid=1;
        } else {d.io_native_read_bits_id=id;d.io_native_read_bits_address=address;d.io_native_read_valid=1;}
        do {d.clock=0;d.eval();bool ready=write?d.io_native_write_ready:d.io_native_read_ready;tick();if(ready)break;}while(true);
        d.io_native_read_valid=0;d.io_native_write_valid=0;
    }
    void run() {
        stall=true;
        for(unsigned i=0;i<16;++i) {request(i,false,0x80000000ULL+64*i);request(i,true,0x100000000ULL+64*i);}
        while(requests.size()<32)tick();
        for(unsigned i=0;i<16;++i) {
            check(requests.at(i)==0x80000000ULL+64*i && requests.at(16+i)==0x100000000ULL+64*i,"DMA_33BIT_SOURCE_MAP");
        }
        // Keep a recycled native ID blocked until its original response drains.
        d.io_native_read_valid=1;d.io_native_read_bits_id=0;d.clock=0;d.eval();
        check(!d.io_native_read_ready,"DMA_PREMATURE_ID_REUSE");tick();d.io_native_read_valid=0;
        for(int source=31;source>=0;--source) {
            d.io_tl_d_bits_source=source;d.io_tl_d_bits_opcode=source>=16?0:1;d.io_tl_d_bits_size=6;
            d.io_tl_d_bits_denied=source%4==0;d.io_tl_d_bits_corrupt=source<16 && source%7==0;
            for(unsigned i=0;i<16;++i)d.io_tl_d_bits_data[i]=i?0:100+source;
            d.io_tl_d_valid=1;
            do {d.clock=0;d.eval();bool ready=d.io_tl_d_ready;tick();if(ready)break;}while(true);
            d.io_tl_d_valid=0;tick();
        }
        while(reads.size()!=16 || writes.size()!=16)tick();
        for(unsigned i=0;i<16;++i) {
            check(reads.at(i)==(i%4==0 || i%7==0),"DMA_DENIED_CORRUPT_READ");
            check(writes.at(i)==((16+i)%4==0),"DMA_DENIED_WRITE");
        }
        const uint64_t invalid[]={0,0x10040000,0x7fffffc0,0x180000000,0x80000001};
        unsigned total=0;
        for(uint64_t address:invalid)for(bool write:{false,true}) {
            reads.clear();writes.clear();size_t before=requests.size();
            // Remove source0 history only; illegal requests must emit NO A.
            auto previous=requests;requests.clear();request(0,write,address);
            while((write?writes:reads).empty())tick();
            check((write?writes:reads).at(0) && requests.empty(),"DMA_ILLEGAL_REGION_LOCAL_ONLY");
            requests=previous;check(requests.size()==before,"DMA_ACCOUNTING");++total;
        }
        std::printf("JXL_DMA_PASS real_sources=32 local_faults=%u cycles=%u\n",total,cycle);d.final();
    }
};
int main(int argc,char **argv) {
    try {Verilated::commandArgs(argc,argv);Test t;t.run();return 0;}
    catch(const std::exception &e) {std::fprintf(stderr,"JXL_DMA_FAIL %s\n",e.what());return 1;}
}
