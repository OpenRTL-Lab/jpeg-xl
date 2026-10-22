// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
/* CPU memory helper test only. Official captured bytes, no codec algorithm. */
#define _GNU_SOURCE
#include "jxl_cpu_buffers.h"
#include "jxl_official_fixtures.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

static uint8_t source[1048576+128] __attribute__((aligned(64)));
static uint8_t dest[1048576+128] __attribute__((aligned(64)));
static uint8_t raw[512] __attribute__((aligned(64)));
static uint8_t reference[512] __attribute__((aligned(64)));

static void check(const uint8_t *r,unsigned n,const uint8_t *f,unsigned m,int exhaustive) {
    unsigned pn=(n+63)&~63u,pm=(m+63)&~63u;
    assert(pn<=1048576 && pm<=1048576);
    assert(!((uintptr_t)r&63) && !((uintptr_t)f&63));
    memset(source,0x3c,pn+128);memset(dest,0x3c,pm+128);
    uint8_t *s=source+64,*d=dest+64;
    jxl_cpu_prepare(s,d,r,n,m);
    for(unsigned i=0;i<n;++i)assert(s[i]==r[i]);
    for(unsigned i=n;i<pn;++i)assert(s[i]==0);
    for(unsigned i=0;i<pm;++i)assert(d[i]==0xa5);
    for(unsigned i=0;i<64;++i) {
        assert(source[i]==0x3c && s[pn+i]==0x3c);
        assert(dest[i]==0x3c && d[pm+i]==0x3c);
    }
    memcpy(d,f,m);memset(d+m,0,pm-m);
    assert(jxl_cpu_verify(d,f,m)==0);
    for(unsigned i=0;i<m;++i)if(exhaustive || i<128 || i+128>=m || i%4096<8) {
        d[i]^=1;assert(jxl_cpu_verify(d,f,m)==1);d[i]^=1;
    }
    for(unsigned i=m;i<pm;++i) {
        d[i]=1;assert(jxl_cpu_verify(d,f,m)==2);d[i]=0;
    }
    assert(jxl_cpu_verify(d,f,m)==0);
}

int main(void) {
    for(unsigned i=0;i<512;++i){raw[i]=(uint8_t)(i*37+3);reference[i]=(uint8_t)(i*71+9);}
    for(unsigned n=1;n<=257;++n)check(raw,n,reference,258-n,1);
    for(unsigned i=0;i<32;++i) {
        const struct jxl_fixture *f=&jxl_fixtures[i];
        check(f->raw,f->raw_bytes,f->file,f->file_bytes,0);
    }
    long page=sysconf(_SC_PAGESIZE);assert(page>=4096);
    uint8_t *guard=mmap(0,(size_t)page*2,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(guard!=MAP_FAILED && mprotect(guard+page,(size_t)page,PROT_NONE)==0);
    uint8_t *last=guard+page-64;memcpy(last,reference,64);
    check(last,64,last,64,1); /* A widened read beyond the last cache line faults. */
    assert(munmap(guard,(size_t)page*2)==0);
    puts("JXL_CPU_BUFFERS_PASS official=32 small_lengths=257 guarded_page=1 corruption_rejection=PASS");
    return 0;
}
