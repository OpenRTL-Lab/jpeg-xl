#!/usr/bin/env python3
# OpenRTL Lab | JPEG XL | version 1.0.0
# SPDX-License-Identifier: MIT
"""Compile and execute the single-owner polling example's fake-register tests."""
import argparse
from pathlib import Path
import subprocess
ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--out', type=Path, required=True)
a = p.parse_args()
a.out.mkdir(parents=True, exist_ok=False)
binary = a.out.resolve() / 'test-poll'
subprocess.run(['cc', '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror',
                '-I' + str(ROOT / 'software'), str(ROOT / 'software/examples/poll_once.c'),
                str(ROOT / 'software/examples/test_poll_once.c'), '-o', str(binary)], check=True)
subprocess.run([str(binary)], check=True)
