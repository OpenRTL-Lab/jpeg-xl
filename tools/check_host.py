#!/usr/bin/env python3
# OpenRTL Lab | JPEG XL | version 1.0.0
# SPDX-License-Identifier: MIT
"""Compile and run host-only contracts. This does not execute RTL."""
import argparse
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument('--out', type=Path, required=True)
a = p.parse_args()
a.out.mkdir(parents=True, exist_ok=True)
for name in ('test_jxl_mmio', 'test_jxl_cpu_buffers'):
    binary = a.out.resolve() / name
    subprocess.run(['cc', '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror',
                    '-I' + str(ROOT / 'software'), '-I' + str(ROOT / 'tests/fixtures'),
                    str(ROOT / 'software' / (name + '.c')), '-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
print(json.dumps({'status': 0, 'host_tests': 2, 'rtl_simulations': 0}))
