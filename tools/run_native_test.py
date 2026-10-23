#!/usr/bin/env python3
# OpenRTL Lab | JPEG XL | version 1.0.0
# SPDX-License-Identifier: MIT
"""Run real complete encoder RTL against public captures or directed faults."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
from prepare_native_fixtures import prepare

ROOT = Path(__file__).resolve().parents[1]


def native_negative(binary, golden, log_path):
    """Change one reference bit at runtime, keeping the tested executable exact."""
    original = golden.read_bytes()
    binary_sha = hashlib.sha256(binary.read_bytes()).hexdigest()
    lines = original.decode().splitlines()
    lines[0] = f'{int(lines[0], 16) ^ 1:x}'
    started = time.monotonic()
    try:
        golden.write_text('\n'.join(lines) + '\n')
        with log_path.open('w') as log:
            result = subprocess.run([str(binary)], stdout=log, stderr=subprocess.STDOUT,
                                    cwd=ROOT, timeout=600)
        text = log_path.read_text()
        assert result.returncode != 0 and 'FAST_NATIVE_OFFICIAL_BYTES' in text, \
            'Negative must fail the intended assertion'
        return dict(mode='negative', exit_code=result.returncode, expected_result=True,
                    seconds=time.monotonic()-started,
                    same_executable_sha256=binary_sha, reference_bits_flipped=1)
    finally:
        golden.write_bytes(original)
        assert hashlib.sha256(binary.read_bytes()).hexdigest() == binary_sha


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--rtl', type=Path, default=ROOT / 'rtl/native')
    parser.add_argument('--suite', choices=('native', 'fault'), default='native')
    parser.add_argument('--mode', choices=('both', 'normal', 'negative'), default='both')
    args = parser.parse_args()
    out = args.out.resolve(); out.mkdir(parents=True, exist_ok=False)
    rtl = args.rtl.resolve()
    product = sorted(rtl.rglob('*.sv'))
    assert (rtl / 'FastNativeEncoder.sv') in product
    top = 'FastNativeTest' if args.suite == 'native' else 'FastNativeFaultTest'
    stem = 'fast_native' if args.suite == 'native' else 'fast_native_fault'
    fixture = prepare(out / 'fixtures')
    record = dict(status=1, suite=args.suite, finished=False, runs=[])
    def save():
        temporary = out / 'completion.tmp'
        temporary.write_text(json.dumps(record, indent=2) + '\n')
        temporary.replace(out / 'completion.json')
    def command(label, argv):
        with (out / (label + '.log')).open('w') as log:
            subprocess.run(list(map(str, argv)), cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True)
    save()
    try:
        native_binary = None
        for mode in (('normal', 'negative') if args.mode == 'both' else (args.mode,)):
            # Native negative uses the exact normal netlist/executable. The ROM
            # is loaded at process startup; no DUT or assertion is disabled.
            if mode == 'negative' and args.suite == 'native' and native_binary is not None:
                record['runs'].append(native_negative(native_binary,
                    Path(fixture['prefix'] + '.words.column-7.hex'), out/'negative-run.log'))
                save()
                continue
            target = out / mode
            emit = ['mill', '-i', 'jpegxl.tool.runMain', 'openimage.jpegxl.fast.Emit' + top,
                    target, fixture['prefix']]
            if args.suite == 'native':
                emit += [str(fixture['raw_words']), str(fixture['file_words'])]
            command(mode + '-emit', emit + ['normal' if args.suite == 'native' else mode])
            sources = product + sorted(target.rglob('*.sv'))
            assert len({p.name for p in sources}) == len(sources), 'Duplicate SV: do not combine two product sets'
            obj = target / 'obj'
            command(mode + '-compile', ['verilator', '--cc', '--exe', '--build', '--assert',
                '-DENABLE_INITIAL_MEM_=1', '--top-module', top, '-j', '2', '--threads', '1',
                '--output-split', '2000', '--output-split-cfuncs', '100', '--Mdir', obj,
                '-CFLAGS', '-O1', *['-I' + str(p) for p in sorted({s.parent for s in sources})],
                *sources, ROOT / ('tools/' + stem + '_clock.cpp')])
            if args.suite == 'native':
                native_binary = obj / ('V' + top)
                if mode == 'negative':
                    record['runs'].append(native_negative(native_binary,
                        Path(fixture['prefix'] + '.words.column-7.hex'), out/'negative-run.log'))
                    save()
                    continue
            started = time.monotonic()
            with (out / (mode + '-run.log')).open('w') as log:
                run = subprocess.run([str(obj / ('V' + top))], cwd=ROOT,
                                     stdout=log, stderr=subprocess.STDOUT, timeout=600)
            text = (out / (mode + '-run.log')).read_text()
            marker = 'FAST_NATIVE_OFFICIAL_BYTES' if args.suite == 'native' else 'FAST_NATIVE_FAULT_STATUS'
            if mode == 'negative':
                assert run.returncode != 0 and marker in text, 'Negative must fail the intended assertion'
            else:
                assert run.returncode == 0
                if args.suite == 'native':
                    assert 'FAST_NATIVE_SUITE_PASS' in text
                    assert len(re.findall(r'FAST_NATIVE_COMPLETE phase=', text)) == 64
                else:
                    assert 'FAST_NATIVE_FAULT_SUITE_PASS' in text
                    assert len(re.findall(r'FAST_NATIVE_FAULT_CASE_PASS case=', text)) == 16
            record['runs'].append(dict(mode=mode, exit_code=run.returncode,
                                        seconds=time.monotonic()-started, expected_result=True))
            save()
        record['status'] = 0
    except Exception as error:
        record['error'] = str(error)
        raise
    finally:
        record['finished'] = True
        save()
    print(json.dumps(record))


if __name__ == '__main__':
    main()
