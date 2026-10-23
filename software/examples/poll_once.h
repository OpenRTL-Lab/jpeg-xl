// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
#ifndef JXL_EXAMPLE_POLL_ONCE_H
#define JXL_EXAMPLE_POLL_ONCE_H
#include "jxl_mmio.h"
enum { JXL_EXAMPLE_BUSY = -10, JXL_EXAMPLE_NOT_SUBMITTED = -11,
       JXL_EXAMPLE_STILL_OWNED = -12, JXL_EXAMPLE_BAD_COMPLETION = -13 };
int jxl_example_poll_once(const struct jxl_io *io, const struct jxl_job *job,
                         struct jxl_completion *completion, unsigned poll_budget);
#endif
