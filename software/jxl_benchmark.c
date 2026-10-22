// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
/* SPDX-License-Identifier: MIT
 * Bare-metal CPU -> coherent DMA -> actual encoder -> DDR full-file test.
 * Building this program is NOT permission to load or execute it on a board.
 * All raw and expected bytes come from the official captured fixture header.
 */
#include "jxl_mmio.h"
#include "jxl_cpu_buffers.h"
#include "jxl_official_fixtures.h"

#ifndef JXL_FIXTURE
#define JXL_FIXTURE (-1)
#endif
#ifdef JXL_SOC_SIM
#define JXL_PREFIX "JXL_SOC_"
volatile uint64_t tohost __attribute__((section(".tohost"), aligned(64)));
volatile uint64_t fromhost __attribute__((section(".tohost"), aligned(64)));
/* Test transport only: official FESVR --signature reads this after HTIF exit.
 * No UART bit-time waits in offline codec/cache tests. Board branch unchanged.
 * The buffer is ordinary dirty CPU memory, never a host-populated answer. */
volatile uint8_t begin_signature[8192] __attribute__((section(".bss.jxl_signature"),aligned(16)));
__asm__(".globl end_signature\n.set end_signature,begin_signature+8192");
static unsigned signature_used;
#else
#define JXL_PREFIX "JXL_BOARD_"
#endif

static volatile uint32_t *const uart=(volatile uint32_t *)(uintptr_t)0x10020000;
static void putc_uart(char ch) {
#ifdef JXL_SOC_SIM
    if(signature_used>=sizeof(begin_signature)-1) {
        __asm__ volatile("fence iorw, iorw" ::: "memory");tohost=3;
        for(;;)__asm__ volatile("wfi");
    }
    begin_signature[signature_used++]=(uint8_t)ch;
#else
    while(uart[0]>>31) {} uart[0]=(uint32_t)(unsigned char)ch;
#endif
}
static void text(const char *p) { while(*p) putc_uart(*p++); }
static void decimal(uint64_t value) {
    char buf[21]; unsigned n=0;
    do { buf[n++]=(char)('0'+value%10); value/=10; } while(value);
    while(n) putc_uart(buf[--n]);
}
static void field(const char *name,uint64_t value) { text(name); decimal(value); }
static uint64_t cycles(void) { uint64_t value; __asm__ volatile("rdcycle %0":"=r"(value)); return value; }
static void fence_cpu(void) { __asm__ volatile("fence iorw, iorw" ::: "memory"); }
static void finish(unsigned code) {
#ifdef JXL_SOC_SIM
    /* The simulation signature is memory-backed; there is no UART to drain. */
    fence_cpu();tohost=((uint64_t)code<<1)|1;
#else
    (void)code;
#endif
    for(;;) __asm__ volatile("wfi");
}
static void stop(const char *why) { text(JXL_PREFIX "FAIL ");text(why);text("\n");finish(1); }
void __attribute__((aligned(4))) jxl_trap(void) { text(JXL_PREFIX "TRAP\n");finish(2); }

/* GDB may choose one fixture before boot, or -1 for all32. This is a data field,
 * not a host agent/device access script. No automatic reset between fixtures;
 * first-pass measurements are not falsely called cold-memo measurements. */
volatile int32_t jxl_fixture_select=JXL_FIXTURE;
volatile uint32_t jxl_platform_hz=50000000u;
static struct jxl_completion completions[32];

static uint64_t source_at(unsigned slot) { return UINT64_C(0x90000000)+(uint64_t)slot*0x200000; }
static uint64_t destination_at(unsigned slot) { return source_at(slot)+0x80000; }
static void prepare_buffers(const struct jxl_fixture *f,unsigned count) {
    for(unsigned slot=0;slot<count;++slot) {
        volatile uint8_t *src=(volatile uint8_t *)(uintptr_t)source_at(slot);
        volatile uint8_t *dst=(volatile uint8_t *)(uintptr_t)destination_at(slot);
        jxl_cpu_prepare(src,dst,f->raw,f->raw_bytes,f->file_bytes);
    }
    /* Deliberately leave CPU cache lines dirty: coherent SBUS must see them.
     * FENCE orders accesses; it does not pretend to perform a cache flush. */
    fence_cpu();
}
static void measure(const struct jxl_io *io,const struct jxl_fixture *f,unsigned count) {
    prepare_buffers(f,count);
    unsigned submitted=0,completed=0; uint32_t seen=0;
#ifdef JXL_SOC_SIM
    unsigned irq_checked=0;
#endif
    uint64_t first=cycles();
    while(completed<count) {
        if(cycles()-first>UINT64_C(500000000)) stop("cycle watchdog; result/DMA not fabricated");
        if(submitted<count) {
            struct jxl_job job={0};job.tag=(f->id<<8)|submitted;
            job.width=f->width;job.height=f->height;job.channels=f->channels;
            job.source=source_at(submitted);job.destination=destination_at(submitted);
            job.source_span=(f->raw_bytes+63u)&~63u;job.destination_span=0x100000;job.output_limit=0x100000;
            int rc=jxl_try_submit(io,&job);if(rc<0) stop("driver rejected fixture");if(!rc) ++submitted;
        }
        struct jxl_completion done;
#ifdef JXL_SOC_SIM
        if(!irq_checked) {
            /* Do not POP a result which appears between this read and the
             * helper's second FLAGS read without first checking its IRQ.
             * The result remains stable until our explicit POP below. */
            if(!(jxl_phys_read(io->opaque,JXL_FLAGS)&2u)) continue;
            volatile uint32_t *pending=(volatile uint32_t *)(uintptr_t)0x0c001000;
            volatile uint32_t *claim=(volatile uint32_t *)(uintptr_t)0x0c200004;
            unsigned n=0;while(!(*pending&(1u<<5)) && ++n<20000) {}
            if(n==20000 || *claim!=5) stop("PLIC IRQ5 route");
            *claim=5;irq_checked=1;
        }
#endif
        if(jxl_try_complete(io,&done)==0) {
            unsigned slot=done.tag&255u;
            if((done.tag>>8)!=f->id || slot>=submitted || (seen&(UINT32_C(1)<<slot))) stop("tag ownership");
            if(done.status || done.output_bytes!=f->file_bytes || done.retired_cycle<done.accepted_cycle) stop("result");
            completions[slot]=done;seen|=UINT32_C(1)<<slot;++completed;
        }
    }
    uint64_t elapsed=cycles()-first;
    fence_cpu();
    /* Validate AFTER the timed batch, not in its submission/drain critical path.
     * CPU cached a5 output lines before DMA; this also checks output coherence. */
    for(unsigned slot=0;slot<count;++slot) {
        volatile const uint8_t *dst=(volatile const uint8_t *)(uintptr_t)destination_at(slot);
        int rc=jxl_cpu_verify(dst,f->file,f->file_bytes);
        if(rc==1) stop("official file byte mismatch");
        if(rc==2) stop("tail padding");
    }
#ifdef JXL_SOC_SIM
    if(!irq_checked) stop("PLIC unexercised");
#endif
    field(JXL_PREFIX "BATCH fixture=",f->id);field(" tasks=",count);field(" cpu_cycles=",elapsed);
    field(" platform_hz=",jxl_platform_hz);text(" full_bytes=PASS memo_reset=0\n");
    for(unsigned slot=0;slot<count;++slot) {
        const struct jxl_completion *c=&completions[slot];
        field(JXL_PREFIX "TASK fixture=",f->id);field(" slot=",slot);field(" accepted=",c->accepted_cycle);
        field(" first_write=",c->first_write_cycle);field(" retired=",c->retired_cycle);
        field(" bytes=",c->output_bytes);field(" reads=",c->reads);field(" writes=",c->writes);text("\n");
    }
}
int main(void) {
    uart[2]=1;uart[6]=jxl_platform_hz/115200u-1;
    struct jxl_io io={(void *)(uintptr_t)JXL_MMIO_BASE,jxl_phys_read,jxl_phys_write,jxl_phys_fence};
    text(JXL_PREFIX "BEGIN official=libjxl-0.11.2-effort1 scope=8bit-single-group-encode\n");
    if(jxl_fixture_select < -1 || jxl_fixture_select >= 32) stop("invalid fixture selection");
    if(jxl_probe(&io)) stop("wrong MMIO device/ABI");
    jxl_phys_write(io.opaque,JXL_IRQ_ENABLE,0); /* polling baseline, IRQ tested separately */
#ifdef JXL_SOC_SIM
    *(volatile uint32_t *)(uintptr_t)0x0c000014=1;
    *(volatile uint32_t *)(uintptr_t)0x0c002000=1u<<5;
    *(volatile uint32_t *)(uintptr_t)0x0c200000=0;
    jxl_phys_write(io.opaque,JXL_IRQ_ENABLE,1);
#endif
    for(unsigned i=0;i<32;++i) if(jxl_fixture_select<0 || (unsigned)jxl_fixture_select==i) {
        measure(&io,&jxl_fixtures[i],1);measure(&io,&jxl_fixtures[i],32);
        if(!(jxl_phys_read(io.opaque,JXL_FLAGS)&8u)) stop("not quiescent after POP");
    }
    text(JXL_PREFIX "PASS (finite test; no decoder/general-format claim)\n");
    finish(0);
}
