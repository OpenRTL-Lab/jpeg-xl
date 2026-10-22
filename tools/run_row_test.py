#!/usr/bin/env python3
# OpenRTL Lab | JPEG XL | version 1.0.0
# SPDX-License-Identifier: MIT
"""Fresh Chisel emission, normal row test and intentional-error rejection."""
import argparse
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument('--out', type=Path, required=True)
a = p.parse_args()
out = a.out.resolve()
out.mkdir(parents=True, exist_ok=False)
for mode in ('normal', 'negative'):
    target = out / mode
    with (out / (mode + '-emit.log')).open('w') as log:
        subprocess.run(['mill', '-i', 'jpegxl.tool.runMain',
                        'openimage.jpegxl.fast.EmitFastLengthRowMiter', str(target), mode],
                       cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True)
    product = sorted(target.rglob('*.sv'))
    with (out / (mode + '-compile.log')).open('w') as log:
        subprocess.run(['verilator', '--cc', '--exe', '--build', '--assert',
                        '--top-module', 'FastLengthRowMiter', '-j', '2',
                        '--threads', '1', '--output-split', '2000', '--output-split-cfuncs', '100',
                        '--Mdir', str(target / 'obj'), '-CFLAGS', '-O1',
                        *['-I' + str(p) for p in sorted({f.parent for f in product})],
                        *map(str, product),
                        str(ROOT / 'tools/fast_length_row_clock.cpp')],
                       cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True)
    result = subprocess.run([str(target / 'obj/VFastLengthRowMiter')],
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    (out / (mode + '-run.log')).write_text(result.stdout)
    if mode == 'normal':
        assert result.returncode == 0 and 'FAST_ROW_MITER_PASS' in result.stdout
    else:
        assert result.returncode != 0 and ('FAST_ROW_MITER_COST' in result.stdout or 'wrong' in result.stdout)
(out / 'completion.json').write_text(json.dumps({'status': 0, 'normal_pass': True,
                                                'intentional_error_rejected': True}) + '\n')
print((out / 'completion.json').read_text())
