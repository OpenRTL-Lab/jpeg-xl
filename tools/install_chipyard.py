#!/usr/bin/env python3
# OpenRTL Lab | JPEG XL | version 1.0.0
# SPDX-License-Identifier: MIT
"""Install only the reviewed integration sources into a pinned upstream tree."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
CHIPYARD = '69eba860a352343e4ac6b6df0f3638a79a86ec78'


def install(chipyard):
    actual = subprocess.check_output(['git', '-C', str(chipyard), 'rev-parse', 'HEAD'], text=True).strip()
    assert actual == CHIPYARD, 'Use the documented upstream revision'
    target = chipyard / 'generators/jpegxl'
    assert not target.exists() and not (chipyard / 'jpegxl.sbt').exists(), 'Inspect existing install; never overwrite'
    target.mkdir()
    for name in ('integration', 'examples', 'rtl'):
        shutil.copytree(ROOT / name, target / name)
    shutil.copyfile(ROOT / 'integration/chipyard/jpegxl.sbt', chipyard / 'jpegxl.sbt')
    print(json.dumps(dict(status=0, chipyard_commit=actual, installed=str(target))))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--chipyard', type=Path, required=True)
    install(parser.parse_args().chipyard.resolve())
