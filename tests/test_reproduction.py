# OpenRTL Lab | JPEG XL | version 1.0.0
# SPDX-License-Identifier: MIT
"""Test fixture packing and explicit file-list failure handling, not a codec."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace
import sys

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('fixtures', ROOT/'tools/prepare_native_fixtures.py')
fixtures = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixtures)
sys.path.insert(0,str(ROOT/'tools'))
import run_native_test

class Reproduction(unittest.TestCase):
    def test_runtime_negative_keeps_binary_and_restores_rom(self):
        with tempfile.TemporaryDirectory() as temp:
            folder=Path(temp); binary=folder/'binary'; binary.write_bytes(b'fixed executable')
            golden=folder/'golden.hex'; golden.write_text('a\nb\n'); before=golden.read_bytes()
            def simulate(argv, **kwargs):
                self.assertEqual(golden.read_text(),'b\nb\n')
                kwargs['stdout'].write('FAST_NATIVE_OFFICIAL_BYTES\n')
                return SimpleNamespace(returncode=1)
            with patch.object(run_native_test.subprocess,'run',side_effect=simulate):
                result=run_native_test.native_negative(binary,golden,folder/'run.log')
            self.assertTrue(result['expected_result'])
            self.assertEqual(golden.read_bytes(),before)
            self.assertEqual(binary.read_bytes(),b'fixed executable')

    def test_wrong_negative_assertion_is_rejected_and_rom_restored(self):
        with tempfile.TemporaryDirectory() as temp:
            folder=Path(temp); binary=folder/'binary'; binary.write_bytes(b'fixed')
            golden=folder/'golden.hex'; golden.write_text('0\n'); before=golden.read_bytes()
            def simulate(argv, **kwargs):
                kwargs['stdout'].write('UNRELATED_FAILURE\n')
                return SimpleNamespace(returncode=1)
            with patch.object(run_native_test.subprocess,'run',side_effect=simulate):
                with self.assertRaises(AssertionError):
                    run_native_test.native_negative(binary,golden,folder/'run.log')
            self.assertEqual(golden.read_bytes(),before)

    def test_all_words_and_padding(self):
        with tempfile.TemporaryDirectory() as temp:
            out = Path(temp)/'rom'
            result = fixtures.prepare(out)
            self.assertEqual(result['new_reference_encodes'], 0)
            rows = json.loads((ROOT/'tests/fixtures/fixtures.json').read_text())['fixtures']
            for kind, suffix in (('raw','.raw.column-2.hex'),('file','.words.column-7.hex')):
                expected = b''
                for row in rows:
                    blob = (ROOT/'tests/fixtures'/row[kind]['path']).read_bytes()
                    expected += blob + bytes((-len(blob)) % 64)
                actual = b''.join(int(line,16).to_bytes(64,'little') for line in
                                  Path(result['prefix']+suffix).read_text().splitlines())
                self.assertEqual(actual,expected)
            with self.assertRaises(FileExistsError):
                fixtures.prepare(out)

    def test_filelist_collision_and_no_overwrite(self):
        with tempfile.TemporaryDirectory() as temp:
            folder=Path(temp); native=folder/'native'; soc=folder/'soc'
            native.mkdir();soc.mkdir()
            (native/'FastNativeEncoder.sv').write_text('module FastNativeEncoder; endmodule\n')
            for name in ('JxlCsr','JxlDmaBridge'):
                (soc/(name+'.sv')).write_text('module '+name+'; endmodule\n')
            out=folder/'all.f'
            argv=['python3',str(ROOT/'tools/make_soc_filelist.py'),'--native-rtl',str(native),
                  '--soc-rtl',str(soc),'--out',str(out)]
            subprocess.run(argv,check=True,capture_output=True)
            before=out.read_bytes()
            self.assertNotEqual(subprocess.run(argv,capture_output=True).returncode,0)
            self.assertEqual(out.read_bytes(),before)
            (soc/'FastNativeEncoder.sv').write_text('module wrong; endmodule\n')
            self.assertNotEqual(subprocess.run(argv[:-1]+[str(folder/'bad.f')],capture_output=True).returncode,0)
            self.assertFalse((folder/'bad.f').exists())

if __name__=='__main__':
    unittest.main()
