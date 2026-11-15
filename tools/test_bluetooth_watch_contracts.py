#!/usr/bin/env python3
"""Exercise production watch startup/state methods with a blocked polling boundary.

The background watcher is a real Java thread. Only the Windows device-enumeration
boundary is delayed; production startWatch and state/stateOf are copied unchanged.
--source-ref proves the caller blocked before the asynchronous-start repair.
"""
from __future__ import annotations
import argparse
import hashlib
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def section(source: str, start: str, end: str) -> str:
    if source.count(start) != 1 or source.count(end) != 1:
        raise ValueError('production watch method boundary changed')
    return source[source.index(start):source.index(end, source.index(start))]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--source-ref')
    args = parser.parse_args()
    relative = 'app/java/com/gesher/server/BluetoothManagerService.java'
    source = (subprocess.check_output(['git', 'show', args.source_ref + ':' + relative],
                                     cwd=ROOT, text=True) if args.source_ref else (ROOT / relative).read_text())
    methods = section(source, '    public static int stateOf(', '    public boolean setEnabled(')
    methods += section(source, '    private void startWatch()', '    /** Looks at Windows once')
    print('Bluetooth watch methods SHA256', hashlib.sha256(methods.encode()).hexdigest(), flush=True)
    template = (ROOT / 'tests/bluetooth/BluetoothWatchHost.java.in').read_text()
    with tempfile.TemporaryDirectory(prefix='gesher-bluetooth-watch-') as tmp:
        work = Path(tmp)
        java = work / 'BluetoothWatchHost.java'
        java.write_text(template.replace('// PRODUCTION_WATCH_METHODS', methods))
        subprocess.run([str(args.java_home / 'bin/javac'), '-d', str(work), str(java)], check=True, timeout=60)
        subprocess.run([str(args.java_home / 'bin/java'), '-cp', str(work), 'BluetoothWatchHost'],
                       check=True, timeout=30)


if __name__ == '__main__':
    main()
