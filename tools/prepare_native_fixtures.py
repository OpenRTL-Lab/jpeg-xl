#!/usr/bin/env python3
# OpenRTL Lab | JPEG XL | version 1.0.0
# SPDX-License-Identifier: MIT
"""Repack published raw/file bytes into test-only memories, never encode pixels."""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def prepare(out):
    rows = json.loads((ROOT / 'tests/fixtures/fixtures.json').read_text())['fixtures']
    assert [r['id'] for r in rows] == list(range(32))
    out.mkdir(parents=True, exist_ok=False)
    prefix = out / 'official'
    raw_words, file_words, raw_bases, file_bases = [], [], [], []
    inputs = {}
    for row in rows:
        raw_bases.append(len(raw_words)); file_bases.append(len(file_words))
        for kind, words in (('raw', raw_words), ('file', file_words)):
            entry = row[kind]
            path = ROOT / 'tests/fixtures' / entry['path']
            blob = path.read_bytes()
            assert hashlib.sha256(blob).hexdigest() == entry['sha256']
            assert len(blob) == row[kind + '_bytes']
            inputs[entry['path']] = entry['sha256']
            words.extend(int.from_bytes(blob[n:n+64], 'little') for n in range(0, len(blob), 64))
    def hex_file(suffix, values):
        Path(str(prefix) + suffix).write_text(''.join(f'{v:x}\n' for v in values))
    hex_file('.raw.column-2.hex', raw_words)
    hex_file('.words.column-7.hex', file_words)
    hex_file('.raw-bases.column-0.hex', raw_bases)
    hex_file('.bases.column-0.hex', file_bases)
    for column, key in ((1, 'channels'), (2, 'width'), (3, 'height'), (5, 'file_bytes')):
        hex_file(f'.jobs.column-{column}.hex', [r[key] for r in rows])
    hex_file('.jobs.column-4.hex', [0x4a580000 + n for n in range(32)])
    fault = (ROOT / 'tests/fixtures/file-0.bin').read_bytes()
    assert (ROOT / 'tests/fixtures/raw-0.bin').read_bytes() == b'\0' and len(fault) == 174
    hex_file('.fault-file.hex', [int.from_bytes(fault[n:n+64], 'little') for n in range(0, len(fault), 64)])
    record = dict(status=0, fixtures=32, prefix=str(prefix), raw_words=len(raw_words),
                  file_words=len(file_words), inputs=inputs, new_reference_encodes=0)
    (out / 'completion.json').write_text(json.dumps(record, indent=2) + '\n')
    return record


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', required=True, type=Path)
    print(json.dumps(prepare(parser.parse_args().out.resolve())))
