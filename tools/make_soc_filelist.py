#!/usr/bin/env python3
# OpenRTL Lab | JPEG XL | version 1.0.0
# SPDX-License-Identifier: MIT
"""Build explicit SV/Verilog inputs for a lowered SoC and one native product."""
import argparse
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--soc-rtl', type=Path, help='Omit for a native-only external simulation list')
parser.add_argument('--native-rtl', type=Path, required=True)
parser.add_argument('--out', type=Path, required=True)
args = parser.parse_args()
roots = [args.native_rtl.resolve()]
if args.soc_rtl:
    roots.append(args.soc_rtl.resolve())
assert all(p.is_dir() for p in roots)
files = sorted({p for root in roots for p in root.rglob('*') if p.suffix in ('.sv', '.v')})
assert any(p.name == 'FastNativeEncoder.sv' for p in files)
if args.soc_rtl:
    assert any(p.name == 'JxlDmaBridge.sv' for p in files)
    assert any(p.name == 'JxlCsr.sv' for p in files)
names = [p.name for p in files]
assert len(set(names)) == len(names), 'Duplicate filenames; inspect module-name collisions before compiling'
assert not args.out.exists(), 'Preserve existing file lists'
assert not any(any(c.isspace() for c in str(p)) for p in files), 'Use paths without whitespace'
args.out.parent.mkdir(parents=True, exist_ok=True)
args.out.write_text(''.join('+incdir+' + str(p) + '\n' for p in sorted({f.parent for f in files})) +
                    ''.join(str(f) + '\n' for f in files))
print(f'JXL_SOC_FILELIST_PASS files={len(files)}')
