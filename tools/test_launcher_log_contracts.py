#!/usr/bin/env python3
"""Run real launcher log code, including a large suffix read with a bounded JVM heap.

The host boundary copies only Launcher's exact printTail method to avoid loading
Android services. LogLines is compiled in full; file I/O, UTF-8 decoding, Java
streams, and thread synchronization are real. --source-ref selects both original
production files for separate behavior and allocation/memory negative controls.
"""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', required=True, type=Path)
    parser.add_argument('--source-ref')
    parser.add_argument('--case', choices=('all', 'behavior', 'allocation', 'tail-memory'), default='all')
    args = parser.parse_args()

    def read(relative: str) -> str:
        if args.source_ref:
            return subprocess.run(['git', 'show', args.source_ref + ':' + relative], cwd=ROOT,
                                  capture_output=True, text=True, check=True).stdout
        return (ROOT / relative).read_text(encoding='utf-8')

    log_lines = read('app/java/com/gesher/app/LogLines.java')
    launcher = read('app/java/com/gesher/app/Launcher.java')
    marker = '    private static void printTail(File file, int lines) {'
    if launcher.count(marker) != 1:
        raise ValueError('Launcher printTail boundary changed')
    # This is the final method; retain its complete body and exclude only the class close.
    method = launcher[launcher.index(marker):].rstrip()
    if not method.endswith('\n}'):
        raise ValueError('Launcher class boundary changed')
    method = method[:-2]
    template = (ROOT / 'tests/exec/launcher_log_host.java.in').read_text(encoding='utf-8')
    if template.count('@PRINT_TAIL@') != 1:
        raise ValueError('Host template boundary changed')
    print('Launcher log source SHA256 ' + hashlib.sha256((log_lines + method).encode()).hexdigest(), flush=True)
    with tempfile.TemporaryDirectory(prefix='gesher-launcher-logs-') as temporary:
        work = Path(temporary)
        (work / 'LogLines.java').write_text(log_lines, encoding='utf-8')
        (work / 'LauncherLogHostTest.java').write_text(template.replace('@PRINT_TAIL@', method), encoding='utf-8')
        subprocess.run([str(args.java_home / 'bin/javac'), '-source', '11', '-target', '11',
                        '-Xlint:-options', '-encoding', 'UTF-8',
                        '-d', str(work), str(work / 'LogLines.java'), str(work / 'LauncherLogHostTest.java'),
                        str(ROOT / 'tests/exec/java/gesher/LauncherTailTest.java')], check=True, timeout=60)
        java = [str(args.java_home / 'bin/java'), '-XX:-UsePerfData', '-Xmx24m', '-cp', str(work)]
        if args.case in ('all', 'behavior'):
            subprocess.run(java + ['gesher.LauncherTailTest'], check=True, timeout=30)
        for case in ('behavior', 'allocation', 'tail-memory') if args.case == 'all' else (args.case,):
            subprocess.run(java + ['com.gesher.app.LauncherLogHostTest', case], check=True, timeout=60)


if __name__ == '__main__':
    main()
