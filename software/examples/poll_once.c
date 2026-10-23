// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
/* Single-owner polling example; coherent or explicitly synchronized DMA only. */
#include "poll_once.h"

/* Caller supplies a reserved, mapped CSR aperture, valid jxl_io callbacks and
 * DMA addresses (not CPU virtual pointers). Both buffers stay allocated on a
 * STILL_OWNED return. A later matching completion, not a timeout/cancel ACK,
 * releases them. This example deliberately permits only one exclusive job. */
int jxl_example_poll_once(const struct jxl_io *io, const struct jxl_job *job,
                          struct jxl_completion *completion, unsigned poll_budget) {
    int rc = jxl_probe(io);
    if (rc != JXL_OK) return rc;
    if (!(io->read32(io->opaque, JXL_FLAGS) & 8u) ||
        io->read32(io->opaque, JXL_OUTSTANDING)) return JXL_EXAMPLE_BUSY;
    io->write32(io->opaque, JXL_IRQ_ENABLE, 0);
    rc = jxl_try_submit(io, job);
    if (rc == JXL_AGAIN) return JXL_EXAMPLE_NOT_SUBMITTED;
    if (rc != JXL_OK) return rc;
    while (poll_budget--) {
        rc = jxl_try_complete(io, completion);
        if (rc == JXL_AGAIN) continue;
        if (rc != JXL_OK || completion->tag != job->tag)
            return JXL_EXAMPLE_BAD_COMPLETION;
        /* Return transport success; the caller MUST also inspect hardware
         * completion->status before accessing output_bytes. */
        return JXL_OK;
    }
    return JXL_EXAMPLE_STILL_OWNED;
}
