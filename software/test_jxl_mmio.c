// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
/* SPDX-License-Identifier: MIT
 * Host-only driver contract test. NOT a software codec or a hardware test. */
#include "jxl_mmio.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
struct fake { uint32_t regs[64]; unsigned writes,fences,pops,submits; };
static uint32_t rd(void *p,unsigned off) { return ((struct fake *)p)->regs[off/4]; }
static void wr(void *p,unsigned off,uint32_t value) {
    struct fake *f=p; ++f->writes; f->regs[off/4]=value;
    if(off==JXL_SUBMIT) ++f->submits;
    if(off==JXL_RESULT_POP) { ++f->pops; f->regs[JXL_FLAGS/4]&=~2u; }
}
static void barrier(void *p) { ++((struct fake *)p)->fences; }
int main(void) {
    struct fake f; memset(&f,0,sizeof f);
    struct jxl_io io={&f,rd,wr,barrier};
    assert(jxl_probe(&io)==JXL_WRONG_DEVICE);
    f.regs[0]=JXL_MAGIC; f.regs[1]=JXL_ABI; assert(jxl_probe(&io)==0);
    struct jxl_job j={7,256,256,4,UINT64_C(0x100000000),UINT64_C(0x100100000),262144,1048576,1048576};
    assert(jxl_try_submit(&io,&j)==JXL_AGAIN && !f.writes);
    f.regs[JXL_FLAGS/4]=1; assert(jxl_try_submit(&io,&j)==0 && f.submits==1);
    assert(f.regs[JXL_SOURCE_HI/4]==1 && f.regs[JXL_WIDTH/4]==256);
    unsigned before=f.writes; j.source=0x10040000;
    assert(jxl_try_submit(&io,&j)==JXL_BAD_JOB && f.writes==before);
    j.source=UINT64_C(0x17fffffc0); assert(jxl_try_submit(&io,&j)==JXL_BAD_JOB);
    j.source=j.destination; assert(jxl_try_submit(&io,&j)==JXL_BAD_JOB);
    struct jxl_completion c; assert(jxl_try_complete(&io,&c)==JXL_AGAIN && !f.pops);
    f.regs[JXL_FLAGS/4]=2; f.regs[JXL_RESULT_TAG/4]=7; f.regs[JXL_BYTES/4]=123;
    f.regs[JXL_ACCEPTED_LO/4]=0xffffff00u; f.regs[(JXL_ACCEPTED_LO+4)/4]=1;
    f.regs[JXL_RETIRED_LO/4]=0x100; f.regs[(JXL_RETIRED_LO+4)/4]=2;
    assert(jxl_try_complete(&io,&c)==0 && c.tag==7 && c.output_bytes==123 && f.pops==1);
    assert(c.retired_cycle-c.accepted_cycle==512);
    jxl_cancel(&io,7); assert(f.regs[JXL_CANCEL_TAG/4]==7 && f.regs[JXL_CANCEL/4]==1);
    uint32_t tag=0,found=0; assert(jxl_try_cancel_ack(&io,&tag,&found)==JXL_AGAIN);
    f.regs[JXL_FLAGS/4]=4; f.regs[JXL_ACK_TAG/4]=7; f.regs[JXL_ACK_FOUND/4]=1;
    assert(jxl_try_cancel_ack(&io,&tag,&found)==0 && tag==7 && found==1);
    puts("JXL_MMIO_HOST_CONTRACT_PASS (no hardware, no codec model)");
}
