#!/usr/bin/env python3
# OpenRTL Lab | JPEG XL | version 1.0.0
# SPDX-License-Identifier: MIT
"""Check the complete distribution inventory, common sensitive literals and links."""
import hashlib
import ipaddress
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
HEADER = "// OpenRTL Lab | JPEG XL | version 1.0.0\n// SPDX-License-Identifier: MIT\n"


def sensitive(text):
    patterns = (
        r"/(?:home|Users|mnt|media)/[A-Za-z0-9_.-]+/",
        r"/(?:[A-Za-z0-9_.-]+-data|private-[A-Za-z0-9_-]+|workspaces?)(?:/|\b)",
        r"[A-Za-z]:[\\/](?:Users|work|workspace)[\\/]",
        r"\b(?:v13|term|capop)-[0-9a-f]{12,}\b",
        r"\b(?:ssh|scp)\s+[^\s]*@[^\s]+",
        r"gh[pousr]_[A-Za-z0-9]{20,}",
        r"github_pat_[A-Za-z0-9_]{20,}",
        r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----",
        r"https?://[^\s/]+:[^\s/@]+@",
    )
    for pattern in patterns:
        if re.search(pattern, text):
            return True
    for match in re.finditer(r"(?<![\w.])(?:\d{1,3}\.){3}\d{1,3}(?![\w.])", text):
        try:
            addr = ipaddress.ip_address(match.group())
        except ValueError:
            continue
        if addr.is_private or addr.is_loopback or addr.is_link_local:
            return True
    return False


def files(root):
    return {str(p.relative_to(root)): p for p in root.rglob('*')
            if p.is_file() and not set(p.relative_to(root).parts) & {'.git', 'build', 'out', '__pycache__'}}


def check(root=ROOT):
    actual = files(root)
    index = json.loads((root / 'PUBLIC_FILES.json').read_text())['files']
    assert not any(set(Path(name).parts) & {'.git', 'build', 'out', '__pycache__'} for name in index)
    assert set(actual) == set(index) | {'PUBLIC_FILES.json'}, 'Inventory file set mismatch'
    for name, path in actual.items():
        assert not path.is_symlink(), 'Symlinks are not permitted'
        assert not sensitive(name), ('Sensitive filename', name)
        data = path.read_bytes()
        if name in index:
            assert hashlib.sha256(data).hexdigest() == index[name], ('File digest mismatch', name)
        content = data.decode('utf-8', errors='ignore') if path.suffix == '.bin' else data.decode('utf-8')
        assert not sensitive(content), ('Sensitive text', name)
        if path.suffix == '.md':
            for target in re.findall(r'\[[^\]]*\]\(([^ )]+)(?:[^)]*)\)', content):
                if ':' in target or target.startswith('#'):
                    continue
                dest = (path.parent / target.split('#')[0]).resolve()
                assert dest.is_relative_to(root.resolve()) and dest.exists(), ('Broken link', name, target)
            if name.endswith('.zh-CN.md'):
                assert name.replace('.zh-CN.md', '.md') in actual, ('Missing language pair', name)
            elif name != 'LICENSE.md':
                assert name[:-3] + '.zh-CN.md' in actual, ('Missing language pair', name)
    sources = json.loads((root / 'SOURCE_MANIFEST.json').read_text())['files']
    for name, entry in sources.items():
        assert name in index
        data = (root / name).read_bytes()
        assert hashlib.sha256(data).hexdigest() == entry['sha256']
    print(json.dumps({'status': 0, 'files': len(actual), 'source_entries': len(sources)}))


if __name__ == '__main__':
    try:
        check()
    except (AssertionError, ValueError, UnicodeError, OSError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
