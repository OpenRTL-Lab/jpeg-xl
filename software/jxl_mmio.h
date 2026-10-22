// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
/* SPDX-License-Identifier: MIT */
#ifndef OPENETH_JXL_MMIO_H
#define OPENETH_JXL_MMIO_H
#include <stdint.h>
#include <stddef.h>

#define JXL_MMIO_BASE UINT64_C(0x10040000)
#define JXL_MAGIC UINT32_C(0x4a584c31)
#define JXL_ABI UINT32_C(0x00010000)
enum { JXL_ID=0, JXL_VERSION=4, JXL_FLAGS=8, JXL_OUTSTANDING=12,
       JXL_IRQ_ENABLE=16, JXL_IRQ_PENDING=20, JXL_TAG=0x20,
       JXL_WIDTH=0x24, JXL_HEIGHT=0x28, JXL_CHANNELS=0x2c,
       JXL_SOURCE_LO=0x30, JXL_SOURCE_HI=0x34, JXL_DEST_LO=0x38, JXL_DEST_HI=0x3c,
       JXL_SOURCE_SPAN=0x40, JXL_DEST_SPAN=0x44, JXL_LIMIT=0x48, JXL_SUBMIT=0x4c,
       JXL_CANCEL_TAG=0x50, JXL_CANCEL=0x54, JXL_RESULT_POP=0x58, JXL_CANCEL_POP=0x5c,
       JXL_RESULT_TAG=0x60, JXL_STATUS=0x64, JXL_BYTES=0x68, JXL_FAULT_LO=0x6c,
       JXL_ACCEPTED_LO=0x74, JXL_FIRST_WRITE_LO=0x7c, JXL_RETIRED_LO=0x84,
       JXL_READS=0x8c, JXL_WRITES=0x90, JXL_ACK_TAG=0x98, JXL_ACK_FOUND=0x9c };
struct jxl_io {
    void *opaque;
    uint32_t (*read32)(void *, unsigned);
    void (*write32)(void *, unsigned, uint32_t);
    void (*fence)(void *);
};
struct jxl_job {
    uint32_t tag, width, height, channels;
    uint64_t source, destination;
    uint32_t source_span, destination_span, output_limit;
};
struct jxl_completion {
    uint32_t tag, status, output_bytes, reads, writes;
    uint64_t fault_address, accepted_cycle, first_write_cycle, retired_cycle;
};
enum { JXL_OK=0, JXL_AGAIN=1, JXL_BAD_JOB=-1, JXL_WRONG_DEVICE=-2 };

static inline int jxl_probe(const struct jxl_io *io) {
    return io->read32(io->opaque,JXL_ID)==JXL_MAGIC &&
        io->read32(io->opaque,JXL_VERSION)==JXL_ABI ? JXL_OK : JXL_WRONG_DEVICE;
}
static inline int jxl_region(uint64_t base, uint32_t span) {
    return base>=UINT64_C(0x80000000) && base<UINT64_C(0x180000000) &&
        span && span<UINT32_C(0x1000000) && ((base|span)&63)==0 &&
        span<=UINT64_C(0x180000000)-base;
}
/* Nonblocking. One software producer owns the shadow CSR bank; serialize this
 * routine if called by multiple threads. CPU cache/DMA ownership is the caller's
 * responsibility: use coherent DMA buffers or platform DMA synchronization. */
static inline int jxl_try_submit(const struct jxl_io *io, const struct jxl_job *j) {
    if (!j->width || j->width>256 || !j->height || j->height>256 ||
        !j->channels || j->channels>4 ||
        !jxl_region(j->source,j->source_span) || !jxl_region(j->destination,j->destination_span) ||
        j->source_span < ((j->width*j->height*j->channels+63u)&~63u) ||
        !j->output_limit || j->output_limit>j->destination_span ||
        !(j->source+j->source_span<=j->destination || j->destination+j->destination_span<=j->source))
        return JXL_BAD_JOB;
    /* Never issue a blocking fifth doorbell: CPU must remain able to POP. */
    if (!(io->read32(io->opaque,JXL_FLAGS)&1u)) return JXL_AGAIN;
#define JW(reg,value) io->write32(io->opaque,(reg),(uint32_t)(value))
    JW(JXL_TAG,j->tag); JW(JXL_WIDTH,j->width); JW(JXL_HEIGHT,j->height); JW(JXL_CHANNELS,j->channels);
    JW(JXL_SOURCE_LO,j->source); JW(JXL_SOURCE_HI,j->source>>32);
    JW(JXL_DEST_LO,j->destination); JW(JXL_DEST_HI,j->destination>>32);
    JW(JXL_SOURCE_SPAN,j->source_span); JW(JXL_DEST_SPAN,j->destination_span); JW(JXL_LIMIT,j->output_limit);
    io->fence(io->opaque); JW(JXL_SUBMIT,1); io->fence(io->opaque);
#undef JW
    return JXL_OK;
}
static inline uint64_t jxl_read64(const struct jxl_io *io, unsigned lo) {
    uint64_t low=io->read32(io->opaque,lo);
    return low | ((uint64_t)io->read32(io->opaque,lo+4)<<32);
}
/* Snapshot remains stable until explicit POP. No buffer may be reused earlier. */
static inline int jxl_try_complete(const struct jxl_io *io, struct jxl_completion *c) {
    if (!(io->read32(io->opaque,JXL_FLAGS)&2u)) return JXL_AGAIN;
    io->fence(io->opaque);
    c->tag=io->read32(io->opaque,JXL_RESULT_TAG); c->status=io->read32(io->opaque,JXL_STATUS);
    c->output_bytes=io->read32(io->opaque,JXL_BYTES); c->fault_address=jxl_read64(io,JXL_FAULT_LO);
    c->accepted_cycle=jxl_read64(io,JXL_ACCEPTED_LO); c->first_write_cycle=jxl_read64(io,JXL_FIRST_WRITE_LO);
    c->retired_cycle=jxl_read64(io,JXL_RETIRED_LO);
    c->reads=io->read32(io->opaque,JXL_READS); c->writes=io->read32(io->opaque,JXL_WRITES);
    io->fence(io->opaque); io->write32(io->opaque,JXL_RESULT_POP,1); io->fence(io->opaque);
    return JXL_OK;
}
static inline void jxl_cancel(const struct jxl_io *io, uint32_t tag) {
    io->write32(io->opaque,JXL_CANCEL_TAG,tag); io->fence(io->opaque);
    io->write32(io->opaque,JXL_CANCEL,1); io->fence(io->opaque);
}
static inline int jxl_try_cancel_ack(const struct jxl_io *io, uint32_t *tag, uint32_t *found) {
    if (!(io->read32(io->opaque,JXL_FLAGS)&4u)) return JXL_AGAIN;
    *tag=io->read32(io->opaque,JXL_ACK_TAG); *found=io->read32(io->opaque,JXL_ACK_FOUND);
    io->write32(io->opaque,JXL_CANCEL_POP,1); io->fence(io->opaque); return JXL_OK;
}
/* RV64 bare-metal MMIO callbacks. This does not allocate buffers or use a board. */
#ifdef __riscv
static inline uint32_t jxl_phys_read(void *base,unsigned off) {
    return *(volatile uint32_t *)((uintptr_t)base+off);
}
static inline void jxl_phys_write(void *base,unsigned off,uint32_t data) {
    *(volatile uint32_t *)((uintptr_t)base+off)=data;
}
static inline void jxl_phys_fence(void *unused) {
    (void)unused; __asm__ volatile("fence iorw, iorw" ::: "memory");
}
#endif
#endif
