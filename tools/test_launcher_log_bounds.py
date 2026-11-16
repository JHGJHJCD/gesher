#!/usr/bin/env python3
# Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0
"""Run full production log framing/tail code against extreme input under 24 MiB.

Real file I/O, UTF-8 decoding and PrintStream locking are retained. The streaming
observer hashes payloads and validates every emitted UTF-8 fragment without keeping
all output. Ordinary-output contracts remain in test_launcher_log_contracts.py.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--source-ref')
    parser.add_argument('--case', choices=('all', 'tail', 'forward', 'concurrency'), default='all')
    args = parser.parse_args()
    path = 'app/java/com/gesher/app/LogLines.java'
    production = (subprocess.run(['git', 'show', args.source_ref + ':' + path], cwd=ROOT,
                                check=True, capture_output=True, text=True).stdout
                  if args.source_ref else (ROOT / path).read_text(encoding='utf-8'))
    with tempfile.TemporaryDirectory(prefix='gesher-log-bounds-') as temporary:
        work = Path(temporary)
        java = work / 'LogLines.java'
        java.write_text(production, encoding='utf-8')
        subprocess.run([str(args.java_home / 'bin/javac'), '-J-XX:-UsePerfData', '-source', '11',
                        '-target', '11', '-Xlint:-options', '-encoding', 'UTF-8', '-d', str(work),
                        str(java), str(ROOT / 'tests/exec/java/com/gesher/app/LauncherLogBoundsTest.java')],
                       check=True, timeout=60)
        for case in ['tail', 'forward', 'concurrency'] if args.case == 'all' else [args.case]:
            subprocess.run([str(args.java_home / 'bin/java'), '-XX:-UsePerfData', '-Xmx24m',
                            '-cp', str(work), 'com.gesher.app.LauncherLogBoundsTest', case],
                           check=True, timeout=90)


if __name__ == '__main__':
    main()
