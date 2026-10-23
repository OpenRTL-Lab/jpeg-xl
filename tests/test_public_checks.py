#!/usr/bin/env python3
# OpenRTL Lab | JPEG XL | version 1.0.0
# SPDX-License-Identifier: MIT
"""Fictitious sensitivity and whole-payload tamper checks, not real secrets."""
import importlib.util
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('checker', ROOT / 'tools/check_public_tree.py')
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class Checks(unittest.TestCase):
    def test_personal_path(self):
        self.assertTrue(checker.sensitive(str(Path('/', 'home', 'example', 'private', 'file'))))

    def test_private_address(self):
        self.assertTrue(checker.sensitive('.'.join(map(str, (10, 234, 56, 78)))))
        self.assertFalse(checker.sensitive('1.149.0'))

    def test_fake_token(self):
        self.assertTrue(checker.sensitive('ghp_' + 'X' * 30))

    def test_internal_paths_and_runtime_ids(self):
        for root in ('example' + '-data', 'workspace', 'workspaces', 'private' + '-cache'):
            self.assertTrue(checker.sensitive('/' + root + '/example'))
        self.assertTrue(checker.sensitive('v13' + '-' + 'a' * 12))
        self.assertTrue(checker.sensitive('C:' + chr(92) + 'Users' + chr(92) + 'example'))
        self.assertFalse(checker.sensitive('src/main/scala/example/Top.scala'))
        self.assertFalse(checker.sensitive('https://github.com/ucb-bar/chipyard.git'))

    def test_manifest_and_checker_not_exempt(self):
        for name in ('tools/check_public_tree.py', 'SOURCE_MANIFEST.json', 'tests/fixtures/note.txt'):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                target = root / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text('ghp_' + 'X' * 30)
                (root / 'PUBLIC_FILES.json').write_text(json.dumps({'files': {name: hashlib.sha256(target.read_bytes()).hexdigest()}}))
                with self.assertRaises(AssertionError):
                    checker.check(root)


if __name__ == '__main__':
    unittest.main()
