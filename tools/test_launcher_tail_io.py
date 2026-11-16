#!/usr/bin/env python3
# Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0
"""Exercise production LogLines with counted real-file I/O at its open boundary.

Only the Files factory is replaced to count bytes from the underlying channel or
reader. --source-ref and --measure run an older production source with the same
fixture; --measure omits new suffix/snapshot contracts and the I/O reduction gate.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', required=True, type=Path)
    parser.add_argument('--source-ref')
    parser.add_argument('--measure', action='store_true')
    args = parser.parse_args()
    path = 'app/java/com/gesher/app/LogLines.java'
    production = (subprocess.run(['git', 'show', args.source_ref + ':' + path], cwd=ROOT,
                                check=True, capture_output=True, text=True).stdout
                  if args.source_ref else (ROOT / path).read_text(encoding='utf-8'))
    factories = {
        'Files.newByteChannel(file.toPath())': 'LauncherTailIoTest.openChannel(file)',
        'Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)':
            'LauncherTailIoTest.openReader(file)',
    }
    matches = [factory for factory in factories if factory in production]
    if len(matches) != 1 or production.count(matches[0]) != 1:
        raise ValueError('production log-tail file-open boundary changed')
    production = production.replace(matches[0], factories[matches[0]])
    with tempfile.TemporaryDirectory(prefix='gesher-tail-io-') as temporary:
        work = Path(temporary)
        java = work / 'LogLines.java'
        java.write_text(production, encoding='utf-8')
        subprocess.run([str(args.java_home / 'bin/javac'), '-J-XX:-UsePerfData', '-source', '11', '-target', '11',
                        '-Xlint:-options', '-encoding', 'UTF-8', '-d', str(work), str(java),
                        str(ROOT / 'tests/exec/java/com/gesher/app/LauncherTailIoTest.java')],
                       check=True, timeout=60)
        subprocess.run([str(args.java_home / 'bin/java'), '-XX:-UsePerfData', '-Xmx24m',
                        '-cp', str(work), 'com.gesher.app.LauncherTailIoTest',
                        'measure' if args.measure else 'gate'], check=True, timeout=90)


if __name__ == '__main__':
    main()
