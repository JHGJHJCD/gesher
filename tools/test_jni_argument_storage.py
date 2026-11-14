#!/usr/bin/env python3
"""Check production JNI argument decoding, lifetime and allocation behavior.

The exact Args class is compiled with the production SysV/ARM64 va_list readers.
The host envelope supplies only JNI's fixed-width scalar/jvalue definitions and
register-bank inputs; no VM or Windows ABI execution is claimed. The mandatory
JniTest and Arm64Jni fixtures also exercise the large-signature path through ART.
Use --source-ref for an old-source allocation negative control, or --behavior-only
to verify the same old source's argument semantics without the allocation gate.
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
    parser.add_argument('--cxx', default=os.environ.get('CXX', 'c++'))
    parser.add_argument('--sanitize', action='store_true')
    parser.add_argument('--behavior-only', action='store_true')
    parser.add_argument('--benchmark', action='store_true')
    args = parser.parse_args()

    def read(path: str) -> str:
        if args.source_ref:
            return subprocess.run(['git', 'show', args.source_ref + ':' + path], cwd=ROOT,
                                  check=True, capture_output=True, text=True).stdout
        return (ROOT / path).read_text(encoding='utf-8')

    source = read('compat/bionic/jni_bridge.cc')
    start = 'class Args {'
    end = '// --- the System V JNIEnv table'
    if source.count(start) != 1 or source.count(end) != 1:
        raise ValueError('production JNI Args boundaries changed')
    implementation = source[source.index(start):source.index(end)]
    template = (ROOT / 'tests/native/jni_argument_storage_host.cc.in').read_text(encoding='utf-8')
    if template.count('@ARGS@') != 1:
        raise ValueError('JNI argument fixture marker changed')
    template = template.replace('@ARGS@', implementation)
    print('JNI argument source SHA256 ' + hashlib.sha256(source.encode()).hexdigest(), flush=True)
    with tempfile.TemporaryDirectory(prefix='gesher-jni-args-') as temporary:
        work = Path(temporary)
        # Copy exact source-ref headers too, so a historical control cannot silently
        # borrow newer decoding logic. These portable headers need no host adapters.
        for relative, target in (
            ('compat/bionic/shim_internal.h', 'shim_internal.h'),
            ('compat/bionic/abi_types.h', 'abi_types.h'),
            ('compat/arm64/include/gesher/arm64_va_list.h', 'gesher/arm64_va_list.h'),
        ):
            path = work / target
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(read(relative), encoding='utf-8')
        generated = work / 'arguments.cc'
        generated.write_text(template, encoding='utf-8')
        binary = work / 'arguments'
        command = shlex.split(args.cxx) + [
            '-std=c++20', '-O2', '-g', '-Wall', '-Wextra', '-Werror',
            '-fno-exceptions', '-pthread', '-I', str(work), str(generated), '-o', str(binary)]
        if args.sanitize:
            command += ['-fsanitize=address,undefined', '-fno-sanitize-recover=all',
                        '-fno-omit-frame-pointer']
        subprocess.run(command, check=True, timeout=120)
        flags = (['--behavior-only'] if args.behavior_only else [])
        if args.benchmark:
            flags += ['--benchmark']
        return subprocess.run([str(binary), *flags], check=False, timeout=45).returncode


if __name__ == '__main__':
    raise SystemExit(main())
