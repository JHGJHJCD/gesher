#!/usr/bin/env python3
"""Test production NDK wrappers with a recording native-window receiver.

The shared assertions also run against the real Windows/AOSP build. This host
check does not establish rendering, color conversion or hardware frame pacing.
Use --source-ref with --skip-null for a bounded old-source control: the old
entrypoints dereference null, while non-null controls isolate routing defects.
"""
from __future__ import annotations
import argparse
import hashlib
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-ref')
    parser.add_argument('--skip-null', action='store_true')
    parser.add_argument('--sanitize', action='store_true')
    parser.add_argument('--cxx', default=os.environ.get('CXX', 'c++'))
    args = parser.parse_args()
    path = 'compat/hwui/window/native_window.cc'
    source = (subprocess.check_output(['git', 'show', f'{args.source_ref}:{path}'], cwd=ROOT,
                                     text=True) if args.source_ref else (ROOT / path).read_text())
    begin = source.index('using gesher::Win32Surface;') + len('using gesher::Win32Surface;')
    end = source.index('ANativeWindow* gesher_window_create(')
    entrypoints = source[begin:end] + '\n}\n'
    template = (ROOT / 'tests/media/native_window_dispatch_host.cc.in').read_text()
    code = template.replace('@PRODUCTION@', entrypoints).replace(
        '@ASSERTIONS@', (ROOT / 'tests/media/native_window_dispatch_assertions.h').read_text().replace('#pragma once', ''))
    print('native-window source SHA256 ' + hashlib.sha256(source.encode()).hexdigest(), flush=True)
    with tempfile.TemporaryDirectory(prefix='gesher-native-window-') as temporary:
        work = Path(temporary)
        (work / 'test.cc').write_text(code)
        command = shlex.split(args.cxx) + ['-std=c++17', '-O1', '-g', '-Wall', '-Wextra', '-Werror',
                                          str(work / 'test.cc'), '-o', str(work / 'test')]
        if args.sanitize:
            command += ['-fsanitize=address,undefined', '-fno-sanitize-recover=all',
                        '-fno-omit-frame-pointer']
        subprocess.run(command, check=True, timeout=120)
        return subprocess.run([str(work / 'test')] + (['skip-null'] if args.skip_null else []),
                              check=False, timeout=30).returncode


if __name__ == '__main__':
    raise SystemExit(main())
