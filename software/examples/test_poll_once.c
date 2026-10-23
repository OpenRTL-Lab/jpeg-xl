// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
/* Host protocol test only: no MMIO or encoder model. */
#include "poll_once.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
struct fake { uint32_t regs[64]; unsigned submits, pops; int complete; };
static uint32_t rd(void *p, unsigned off) { return ((struct fake *)p)->regs[off/4]; }
static void wr(void *p, unsigned off, uint32_t value) {
    struct fake *f = p;
    f->regs[off/4] = value;
    if (off == JXL_SUBMIT) {
        ++f->submits;
        f->regs[JXL_FLAGS/4] = f->complete ? 2 : 0;
    }
    if (off == JXL_RESULT_POP) { ++f->pops; f->regs[JXL_FLAGS/4] = 9; }
}
static void fence_cb(void *p) { (void)p; }
static void init(struct fake *f) {
    memset(f, 0, sizeof *f);
    f->regs[JXL_ID/4] = JXL_MAGIC; f->regs[JXL_VERSION/4] = JXL_ABI;
    f->regs[JXL_FLAGS/4] = 9;
    f->regs[JXL_RESULT_TAG/4] = 1;
}
int main(void) {
    struct fake f;
    struct jxl_io io = { &f, rd, wr, fence_cb };
    struct jxl_job j = { 1, 1, 1, 1, 0x90000000, 0x90080000, 64, 4096, 4096 };
    struct jxl_completion c;
    init(&f); f.regs[JXL_ID/4] = 0;
    assert(jxl_example_poll_once(&io, &j, &c, 2) == JXL_WRONG_DEVICE && !f.submits);
    init(&f); f.regs[JXL_OUTSTANDING/4] = 1;
    assert(jxl_example_poll_once(&io, &j, &c, 2) == JXL_EXAMPLE_BUSY && !f.submits);
    init(&f); f.regs[JXL_FLAGS/4] = 8;
    assert(jxl_example_poll_once(&io, &j, &c, 2) == JXL_EXAMPLE_NOT_SUBMITTED && !f.submits);
    init(&f); j.source++;
    assert(jxl_example_poll_once(&io, &j, &c, 2) == JXL_BAD_JOB && !f.submits); j.source--;
    init(&f);
    assert(jxl_example_poll_once(&io, &j, &c, 2) == JXL_EXAMPLE_STILL_OWNED);
    assert(f.submits == 1 && f.pops == 0 && !f.regs[JXL_IRQ_ENABLE/4]);
    init(&f); f.complete = 1; f.regs[JXL_STATUS/4] = 6;
    assert(jxl_example_poll_once(&io, &j, &c, 2) == JXL_OK && c.status == 6 && f.pops == 1);
    init(&f); f.complete = 1; f.regs[JXL_RESULT_TAG/4] = 2;
    assert(jxl_example_poll_once(&io, &j, &c, 2) == JXL_EXAMPLE_BAD_COMPLETION);
    puts("JXL_POLL_EXAMPLE_HOST_PASS cases=7 (no hardware)");
    return 0;
}
