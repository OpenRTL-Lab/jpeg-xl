#!/usr/bin/env python3
# OpenRTL Lab | JPEG XL | version 1.0.0
# SPDX-License-Identifier: MIT
"""Recompute published metrics from numeric task records; no hardware access."""
import hashlib
import json
import math
from pathlib import Path
import statistics

ROOT = Path(__file__).resolve().parents[1]
metrics = json.loads((ROOT / 'evidence/measurements.json').read_text())
cycles = json.loads((ROOT / 'evidence/task-cycles.json').read_text())
fixtures = json.loads((ROOT / 'tests/fixtures/fixtures.json').read_text())['fixtures']
assert metrics['clock_hz'] == cycles['clock_hz'] == 50000000
assert len(metrics['rows']) == len(fixtures) == 32
assert len(cycles['rounds']) == 3


def equal(a, b):
    assert math.isclose(a, b, rel_tol=1e-10, abs_tol=1e-10), (a, b)


for fixture in fixtures:
    for kind in ('raw', 'file'):
        data = (ROOT / 'tests/fixtures' / fixture[kind]['path']).read_bytes()
        assert len(data) == fixture[kind + '_bytes']
        assert hashlib.sha256(data).hexdigest() == fixture[kind]['sha256']
total = 0
mixed = []
for round_index, record in enumerate(cycles['rounds']):
    assert record['status'] == 0 and record['complete_files_and_padding_matched']
    batches = {(b['fixture'], b['count']): b for b in record['batches']}
    assert set(batches) == {(i, n) for i in range(32) for n in (1, 32)}
    for row in metrics['rows']:
        fixture = fixtures[row['id']]
        for key in ('id', 'channels', 'width', 'height', 'raw_bytes', 'file_bytes'):
            assert row[key] == fixture[key]
        sample = row['samples'][round_index]
        one, batch = batches[(row['id'], 1)], batches[(row['id'], 32)]
        for b in (one, batch):
            assert len(b['tasks']) == b['count']
            for task in b['tasks']:
                assert task['accepted'] <= task['first_write'] <= task['retired']
                assert task['bytes'] == row['file_bytes']
                assert task['reads'] == (row['raw_bytes'] + 63) // 64
                assert task['writes'] == (row['file_bytes'] + 63) // 64
                total += 1
        task = one['tasks'][0]
        equal(sample['cpu_single_cycles'], one['cpu_cycles'])
        equal(sample['cpu_batch32_cycles'], batch['cpu_cycles'])
        equal(sample['hardware_single_us'], (task['retired'] - task['accepted']) / 50)
        equal(sample['first_output_single_us'], (task['first_write'] - task['accepted']) / 50)
        equal(sample['batch32_input_MBps'], row['raw_bytes'] * 32 * 50 / batch['cpu_cycles'])
    all_cycles = sum(batches[(i, 32)]['cpu_cycles'] for i in range(32))
    value = sum(f['raw_bytes'] for f in fixtures) * 32 * 50 / all_cycles
    equal(metrics['rounds'][round_index]['batch32_mixed_input_MBps'], value)
    mixed.append(value)
for row in metrics['rows']:
    for key, value in row['median'].items():
        equal(value, statistics.median(s[key] for s in row['samples']))
assert total == metrics['verified_tasks'] == 3168
equal(statistics.median(mixed), metrics['mixed_input_MBps_median'])
print(json.dumps({'status': 0, 'tasks_recomputed': total, 'fixture_files_verified': 64}))
