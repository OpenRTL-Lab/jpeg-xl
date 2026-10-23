#!/usr/bin/env python3
# OpenRTL Lab | JPEG XL | version 1.0.0
# SPDX-License-Identifier: MIT
"""Byte-compare fresh upstream observer outputs with all published captures."""
import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--prefix', required=True, type=Path)
args = parser.parse_args()
for index in range(32):
    for kind in ('raw', 'file'):
        assert Path(str(args.prefix) + f'.{kind}-{index}.bin').read_bytes() == (
            ROOT / f'tests/fixtures/{kind}-{index}.bin').read_bytes(), (kind, index)
print('OFFICIAL_CAPTURE_IDENTITY_PASS files=64')
