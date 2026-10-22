// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
// Real generated SoC encoder domain. This environment implements a TL memory,
// not a codec. Expected bytes are the immutable OFFICIAL libjxl captures.
#include "VClockSinkDomain_4.h"
#include "verilated.h"
#include "jxl_mmio.h"
#include "jxl_official_fixtures.h"
#include <array>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <map>
#include <stdexcept>
#include <string>
#include <vector>

static void check(bool condition,const char *why) { if(!condition) throw std::runtime_error(why); }
using Beat=std::array<uint32_t,16>;
struct Response { Beat data{}; unsigned source,opcode; bool fault,corrupt; uint64_t due; };
struct Domain {
    VClockSinkDomain_4 dut;
    std::map<uint64_t,Beat> memory;
    std::map<unsigned,Response> pending;
    Response response{}; bool held=false,pressure=false,hold_dma=false;
    bool fault_used=false,negative=false; unsigned fault=0;
    bool a_stalled=false; std::array<uint64_t,6> a_previous{};Beat a_previous_data{};
    uint64_t cycle=0,read_count=0,write_count=0;
    Domain() {
        dut.auto_clock_in_clock=0; dut.auto_clock_in_reset=1;
        dut.auto_csr_in_a_valid=0; dut.auto_csr_in_d_ready=1;
        dut.auto_dma_out_a_ready=0; dut.auto_dma_out_d_valid=0;
        dut.auto_dma_out_d_bits_denied=0; dut.auto_dma_out_d_bits_corrupt=0;
        for(int i=0;i<5;++i) step(); dut.auto_clock_in_reset=0; step();
    }
    void step() {
        check(++cycle<8000000,"JXL_INTEGRATION_CYCLE_GUARD");
        if(!held && !hold_dma) {
            for(auto p=pending.rbegin();p!=pending.rend();++p) if(p->second.due<=cycle) {
                response=p->second;held=true;break;
            }
        }
        dut.auto_dma_out_a_ready=!pressure || cycle%13>=5;
        dut.auto_dma_out_d_valid=held;
        if(held) {
            dut.auto_dma_out_d_bits_opcode=response.opcode;
            dut.auto_dma_out_d_bits_size=6; dut.auto_dma_out_d_bits_source=response.source;
            dut.auto_dma_out_d_bits_denied=response.fault;
            dut.auto_dma_out_d_bits_corrupt=response.corrupt;
            for(unsigned i=0;i<16;++i) dut.auto_dma_out_d_bits_data[i]=response.data[i];
        }
        dut.auto_clock_in_clock=0; dut.eval();
        std::array<uint64_t,6> a_now{{dut.auto_dma_out_a_bits_opcode,dut.auto_dma_out_a_bits_size,
            dut.auto_dma_out_a_bits_source,dut.auto_dma_out_a_bits_address,
            dut.auto_dma_out_a_bits_mask,dut.auto_dma_out_a_bits_corrupt}};
        Beat a_data{};for(unsigned i=0;i<16;++i)a_data[i]=dut.auto_dma_out_a_bits_data[i];
        if(a_stalled)check(dut.auto_dma_out_a_valid && a_now==a_previous && a_data==a_previous_data,"TL_A_BACKPRESSURE_STABLE");
        a_stalled=!dut.auto_clock_in_reset && dut.auto_dma_out_a_valid && !dut.auto_dma_out_a_ready;
        a_previous=a_now;a_previous_data=a_data;
        bool a=dut.auto_dma_out_a_valid && dut.auto_dma_out_a_ready;
        bool d=dut.auto_dma_out_d_valid && dut.auto_dma_out_d_ready;
        if(a && !dut.auto_clock_in_reset) {
            unsigned id=dut.auto_dma_out_a_bits_source, op=dut.auto_dma_out_a_bits_opcode;
            uint64_t addr=dut.auto_dma_out_a_bits_address;
            check(id<32 && !pending.count(id),"TL_SOURCE_REUSE");
            check(dut.auto_dma_out_a_bits_size==6 && dut.auto_dma_out_a_bits_mask==UINT64_MAX &&
                  !(addr&63) && addr>=0x80000000ULL && addr<0x180000000ULL,"TL_REQUEST_REGION");
            check(op==0 || op==4,"TL_REQUEST_OPCODE");
            Response r{};r.source=id;r.opcode=op==4?1:0;
            r.due=cycle+(pressure?19+(31-id):2);
            bool inject=!fault_used && ((op==4 && (fault==1 || fault==3)) || (op==0 && fault==2));
            r.fault=inject && fault!=3;r.corrupt=inject && fault==3;
            if(inject) fault_used=true;
            if(op==4) {
                check(memory.count(addr),"TL_RAW_ADDRESS_NOT_INITIALIZED");r.data=memory.at(addr);++read_count;
            } else {
                check(id>=16,"TL_WRITE_SOURCE_MAPPING");
                if(!r.fault) for(unsigned i=0;i<16;++i) memory[addr][i]=dut.auto_dma_out_a_bits_data[i];
                ++write_count;
            }
            pending.emplace(id,r);
        }
        if(d) { check(pending.erase(response.source)==1,"TL_RESPONSE_UNKNOWN");held=false; }
        dut.auto_clock_in_clock=1; dut.eval();
        check(!Verilated::gotFinish(),"RTL_ASSERTION_FINISH");
    }
    uint32_t access(unsigned off,bool write=false,uint32_t data=0,bool denied=false,unsigned size=2,unsigned mask=15,unsigned delay=0) {
        dut.auto_csr_in_d_ready=delay?0:1;
        // This initiator has exactly one outstanding CSR request. Reuse source
        // zero only AFTER its response is consumed. Bundle width is not the
        // diplomatic source range: this SoC's monitor permits [0, 0x4e0).
        constexpr unsigned id=0;
        dut.auto_csr_in_a_bits_opcode=write?0:4;dut.auto_csr_in_a_bits_param=0;
        dut.auto_csr_in_a_bits_size=size;dut.auto_csr_in_a_bits_source=id;
        dut.auto_csr_in_a_bits_address=JXL_MMIO_BASE+off;
        dut.auto_csr_in_a_bits_mask=mask;dut.auto_csr_in_a_bits_data=data;dut.auto_csr_in_a_bits_corrupt=0;
        dut.auto_csr_in_a_valid=1;
        unsigned wait=0;
        do {
            dut.auto_clock_in_clock=0;dut.eval();
            bool fire=dut.auto_csr_in_a_ready;step();if(fire) break;
            check(++wait<10000,"CSR_A_STUCK");
        } while(true);
        dut.auto_csr_in_a_valid=0;
        while(true) {
            dut.auto_clock_in_clock=0;dut.eval();
            if(dut.auto_csr_in_d_valid) break;
            step();check(++wait<10000,"CSR_D_STUCK");
        }
        check(dut.auto_csr_in_d_bits_denied==denied,"CSR_DENIED_MISMATCH");
        check(dut.auto_csr_in_d_bits_source==id && dut.auto_csr_in_d_bits_size==size &&
            dut.auto_csr_in_d_bits_opcode==(write?0:1),"CSR_RESPONSE_METADATA");
        uint32_t value=dut.auto_csr_in_d_bits_data;
        for(unsigned i=0;i<delay;++i) {
            step();check(dut.auto_csr_in_d_valid && dut.auto_csr_in_d_bits_data==value &&
                dut.auto_csr_in_d_bits_source==id && dut.auto_csr_in_d_bits_denied==denied,"CSR_D_BACKPRESSURE_STABLE");
        }
        dut.auto_csr_in_d_ready=1;step();return value;
    }
    static uint32_t read(void *p,unsigned a) { return static_cast<Domain*>(p)->access(a); }
    static void write(void *p,unsigned a,uint32_t v) { static_cast<Domain*>(p)->access(a,true,v); }
    static void fence(void *p) { static_cast<Domain*>(p)->step(); }
    jxl_io io() { return {this,read,write,fence}; }
    uint64_t source(unsigned slot,bool high=false) { return (high?0x140000000ULL:0x90000000ULL)+slot*0x200000ULL; }
    uint64_t destination(unsigned slot,bool high=false) { return high?0x17ff00000ULL:source(slot)+0x80000; }
    jxl_job job(const jxl_fixture &f,unsigned slot,bool high=false) {
        jxl_job j{};j.tag=0xa0000000u|slot;j.width=f.width;j.height=f.height;j.channels=f.channels;
        j.source=source(slot,high);j.destination=destination(slot,high);
        j.source_span=(f.raw_bytes+63)&~63u;j.destination_span=0x100000;j.output_limit=f.file_bytes;return j;
    }
    void prepare(const jxl_fixture &f,unsigned slot,bool high=false) {
        for(unsigned b=0;b<((f.raw_bytes+63)&~63u);b+=64) {
            Beat v{};for(unsigned i=0;i<64 && b+i<f.raw_bytes;++i) v[i/4]|=uint32_t(f.raw[b+i])<<(8*(i%4));
            memory[source(slot,high)+b]=v;
        }
        for(unsigned b=0;b<((f.file_bytes+63)&~63u);b+=64) { Beat v;v.fill(0xa5a5a5a5);memory[destination(slot,high)+b]=v; }
    }
    void compare(const jxl_fixture &f,unsigned slot,bool high=false) {
        for(unsigned b=0;b<((f.file_bytes+63)&~63u);++b) {
            uint64_t a=destination(slot,high)+b;
            uint8_t value=memory.at(a&~63ULL)[(b%64)/4]>>(8*(b%4));
            uint8_t expected=b<f.file_bytes?f.file[b]:0;
            if(negative && b==0) expected^=1;
            check(value==expected,"JXL_INTEGRATION_OFFICIAL_BYTES");
        }
    }
    void verify(const jxl_fixture &f,unsigned slot,const jxl_completion &r,bool high=false) {
        check(!r.status && r.tag==(0xa0000000u|slot) && r.output_bytes==f.file_bytes,"NATIVE_RESULT_IDENTITY");
        check(r.reads==(f.raw_bytes+63)/64 && r.writes==(f.file_bytes+63)/64 &&
            r.retired_cycle>r.first_write_cycle && r.first_write_cycle>r.accepted_cycle,"NATIVE_RESULT_METRICS");
        compare(f,slot,high);
        std::printf("JXL_DOMAIN_RESULT fixture=%u slot=%u bytes=%u accepted=%llu first=%llu retired=%llu\n",
            f.id,slot,r.output_bytes,(unsigned long long)r.accepted_cycle,
            (unsigned long long)r.first_write_cycle,(unsigned long long)r.retired_cycle);
    }
    jxl_completion complete() {
        auto port=io(); jxl_completion r{};
        while(jxl_try_complete(&port,&r)) step();return r;
    }
    void normal(const std::vector<unsigned> &fixtures,bool high=false) {
        for(unsigned i=0;i<fixtures.size();++i) prepare(jxl_fixtures[fixtures[i]],i,high);
        auto port=io();unsigned sent=0,done=0;uint32_t seen=0;
        while(done<fixtures.size()) {
            if(sent<fixtures.size()) { auto j=job(jxl_fixtures[fixtures[sent]],sent,high);
                int rc=jxl_try_submit(&port,&j);check(rc>=0,"HOST_JOB_REJECTED");if(!rc)++sent; }
            jxl_completion r{};
            if(!jxl_try_complete(&port,&r)) {
                unsigned slot=r.tag&255;check(slot<sent && !(seen&(1u<<slot)),"RESULT_DUPLICATE");
                verify(jxl_fixtures[fixtures[slot]],slot,r,high);seen|=1u<<slot;++done;
            }
        }
        check(pending.empty() && !held && (access(JXL_FLAGS)&8),"NOT_QUIESCENT");
    }
    void csr() {
        access(JXL_ID,false,0,false,2,15,8);
        // A byte Get at +1 with mask=2 is legal TileLink, but unsupported by
        // our word-only CSR ABI. An unaligned *word* Get is illegal TileLink
        // and must never be injected past the unchanged protocol monitor.
        access(1,false,0,true,0,2);access(0x100,false,0,true);access(0,true,1,true);
        access(0x20,true,1,true,0,1);access(0x24,true,512,true);
        access(0x10,true,4,true);check(access(0x10)==0,"INVALID_WRITE_SIDE_EFFECT");
        access(0x60,false,0,true);access(0x58,true,1,true);
        access(JXL_IRQ_ENABLE,true,3);
        auto port=io();const auto &f=jxl_fixtures[0];
        for(unsigned i=0;i<4;++i) {prepare(f,i);auto j=job(f,i);check(!jxl_try_submit(&port,&j),"FOUR_SLOTS_SUBMIT");}
        check(!(access(JXL_FLAGS)&1) && access(JXL_OUTSTANDING)==4,"FOUR_SLOTS_FULL");
        while(!(access(JXL_FLAGS)&2)) step();
        unsigned tag=access(JXL_RESULT_TAG);uint64_t accepted=jxl_read64(&port,JXL_ACCEPTED_LO);
        for(unsigned i=0;i<100;++i) step();
        check(access(JXL_RESULT_TAG)==tag && jxl_read64(&port,JXL_ACCEPTED_LO)==accepted &&
            access(JXL_OUTSTANDING)==4 && dut.auto_int_out_0,"SNAPSHOT_IRQ_OWNERSHIP");
        uint32_t seen=0;
        for(unsigned i=0;i<4;++i) {auto r=complete();unsigned slot=r.tag&255;
            check(slot<4 && !(seen&(1u<<slot)),"CSR_LATE_RESULT");seen|=1u<<slot;verify(f,slot,r);}
        check(access(JXL_OUTSTANDING)==0 && !dut.auto_int_out_0,"POP_IRQ_CLEAR");
    }
    void error_case(unsigned kind) {
        fault=kind;auto port=io();const auto &f=jxl_fixtures[31];prepare(f,0);
        auto j=job(f,0);check(!jxl_try_submit(&port,&j),"ERROR_SUBMIT");auto r=complete();
        check(fault_used && r.status==(kind==2?5u:4u) && r.tag==j.tag,"BUS_FAULT_PROPAGATION");
        check(pending.empty() && !held && (access(JXL_FLAGS)&8),"ERROR_NOT_DRAINED");
        fault=0;normal({0});
    }
    void cancel(bool known) {
        auto port=io();uint32_t tag=0,found=0;
        if(known) {
            const auto &f=jxl_fixtures[31];prepare(f,0);hold_dma=true;auto j=job(f,0);
            check(!jxl_try_submit(&port,&j),"CANCEL_SUBMIT");while(pending.empty()) step();
        }
        jxl_cancel(&port,0xa0000000u);
        while(jxl_try_cancel_ack(&port,&tag,&found)) step();
        check(tag==0xa0000000u && found==unsigned(known),"CANCEL_ACK_IDENTITY");
        hold_dma=false;
        if(known) {auto r=complete();check(r.status==7 && r.tag==tag,"CANCEL_RESULT");}
        check(pending.empty() && !held && (access(JXL_FLAGS)&8),"CANCEL_NOT_DRAINED");normal({0});
    }
};
int main(int argc,char **argv) {
    try {
        Verilated::commandArgs(argc,argv);check(argc>=2,"mode required");Domain s;std::string mode=argv[1];
        auto io=s.io();check(!jxl_probe(&io),"MMIO_ABI");
        if(mode=="single" || mode=="negative") {check(argc==3,"fixture required");unsigned i=std::strtoul(argv[2],nullptr,10);
            check(i<32,"fixture bounds");s.negative=mode=="negative";s.normal({i});}
        else if(mode=="mixed" || mode=="pressure") {s.pressure=mode=="pressure";std::vector<unsigned> f;
            for(unsigned i=0;i<32;++i)f.push_back(i);s.normal(f);}
        else if(mode=="csr")s.csr();
        else if(mode=="read-fault")s.error_case(1);
        else if(mode=="write-fault")s.error_case(2);
        else if(mode=="read-corrupt")s.error_case(3);
        else if(mode=="cancel")s.cancel(true);
        else if(mode=="unknown-cancel")s.cancel(false);
        else if(mode=="high-address")s.normal({31},true);
        else throw std::runtime_error("unknown mode");
        s.dut.final();std::printf("JXL_DOMAIN_PASS mode=%s cycles=%llu reads=%llu writes=%llu\n",mode.c_str(),
            (unsigned long long)s.cycle,(unsigned long long)s.read_count,(unsigned long long)s.write_count);return 0;
    } catch(const std::exception &e) {std::fprintf(stderr,"JXL_DOMAIN_FAIL %s\n",e.what());return 1;}
}
