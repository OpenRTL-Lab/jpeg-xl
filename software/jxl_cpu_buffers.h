// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
/* SPDX-License-Identifier: MIT
 * CPU-side test/benchmark buffer handling, NOT an image encoding model.
 * All bases are 64-byte aligned; actual fixture sizes are <= 1MiB.
 * No reads beyond the unpadded raw/reference arrays are permitted.
 */
#ifndef JXL_CPU_BUFFERS_H
#define JXL_CPU_BUFFERS_H
#include <stdint.h>
typedef uint64_t jxl_alias_u64 __attribute__((__may_alias__));

static inline void jxl_cpu_prepare(volatile uint8_t *src,volatile uint8_t *dst,
                                  const uint8_t *raw,unsigned bytes,unsigned file_bytes) {
    volatile jxl_alias_u64 *s=(volatile jxl_alias_u64 *)src;
    const jxl_alias_u64 *r=(const jxl_alias_u64 *)raw;
    unsigned words=bytes/8,i=0;
    for(;i+8<=words;i+=8) {
        s[i]=r[i];s[i+1]=r[i+1];s[i+2]=r[i+2];s[i+3]=r[i+3];
        s[i+4]=r[i+4];s[i+5]=r[i+5];s[i+6]=r[i+6];s[i+7]=r[i+7];
    }
    for(;i<words;++i)s[i]=r[i];
    for(i=words*8;i<bytes;++i)src[i]=raw[i];
    for(;i<((bytes+63u)&~63u);++i)src[i]=0;
    volatile jxl_alias_u64 *d=(volatile jxl_alias_u64 *)dst;
    unsigned output_words=((file_bytes+63u)&~63u)/8;
    const uint64_t paint=UINT64_C(0xa5a5a5a5a5a5a5a5);
    for(i=0;i<output_words;i+=8) {
        d[i]=paint;d[i+1]=paint;d[i+2]=paint;d[i+3]=paint;
        d[i+4]=paint;d[i+5]=paint;d[i+6]=paint;d[i+7]=paint;
    }
}

/* 0 = every byte matches including zero bus padding, 1 = file, 2 = padding. */
static inline int jxl_cpu_verify(volatile const uint8_t *dst,const uint8_t *file,unsigned bytes) {
    volatile const jxl_alias_u64 *d=(volatile const jxl_alias_u64 *)dst;
    const jxl_alias_u64 *f=(const jxl_alias_u64 *)file;
    unsigned words=bytes/8,i=0;
    for(;i+8<=words;i+=8) {
        if(d[i]!=f[i] || d[i+1]!=f[i+1] || d[i+2]!=f[i+2] || d[i+3]!=f[i+3] ||
           d[i+4]!=f[i+4] || d[i+5]!=f[i+5] || d[i+6]!=f[i+6] || d[i+7]!=f[i+7])return 1;
    }
    for(;i<words;++i)if(d[i]!=f[i])return 1;
    for(i=words*8;i<bytes;++i)if(dst[i]!=file[i])return 1;
    for(;i<((bytes+63u)&~63u);++i)if(dst[i]!=0)return 2;
    return 0;
}
#endif
