#!/usr/bin/env python3
# OpenRTL Lab | JPEG XL | version 1.0.0
# SPDX-License-Identifier: MIT
"""Compare fresh native output with all published generated bodies."""
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
out = Path(sys.argv[1])
entries = json.loads((ROOT / 'SOURCE_MANIFEST.json').read_text())['files']
expected = {name.removeprefix('rtl/native/'): v['body_sha256']
            for name, v in entries.items() if name.startswith('rtl/native/') and name.endswith('.sv')}
actual = {str(f.relative_to(out)): hashlib.sha256(f.read_bytes()).hexdigest()
          for f in out.rglob('*.sv')}
assert actual == expected, ('Generated file/body mismatch', sorted(set(actual) ^ set(expected)),
                            [name for name in expected.keys() & actual.keys() if expected[name] != actual[name]])
print(json.dumps({'status': 0, 'generated_sv': len(actual)}))
