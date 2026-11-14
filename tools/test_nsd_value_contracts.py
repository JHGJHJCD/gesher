#!/usr/bin/env python3
"""SDK-compiled NSD values over the production Android16 implementation.

Real framework/core classes and the existing native initialization boundary are
used. Native Parcel is tested by the same fixture on Windows, not replaced here.
--source-ref compiles the baseline NsdServiceInfo for negative controls.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--sdk-jar', type=Path, required=True)
    parser.add_argument('--framework-classes', type=Path, required=True)
    parser.add_argument('--core-classes', type=Path, required=True)
    parser.add_argument('--app-classes', type=Path, required=True)
    parser.add_argument('--source-ref', help='Use an old NsdServiceInfo source as a negative control')
    parser.add_argument('--case', choices=['all', 'entries', 'total', 'values', 'complete'], default='all')
    args = parser.parse_args()
    cc = shutil.which('cc')
    if not cc:
        parser.error('A C compiler is required for the declared native initialization boundary')
    classpath = os.pathsep.join(str(path.resolve()) for path in
                               (args.app_classes, args.framework_classes, args.core_classes))
    javac = str(args.java_home.resolve() / 'bin/javac')
    java = str(args.java_home.resolve() / 'bin/java')
    with tempfile.TemporaryDirectory(prefix='gesher-nsd-value-contracts-') as temporary:
        work = Path(temporary)
        classes, sdk_classes = work / 'classes', work / 'sdk-classes'
        classes.mkdir()
        sdk_classes.mkdir()
        library = work / 'libnsd_value_host.so'
        subprocess.run([cc, '-std=c11', '-D_POSIX_C_SOURCE=200809L', '-shared', '-fPIC',
                        '-I' + str(args.java_home.resolve() / 'include'),
                        '-I' + str(args.java_home.resolve() / 'include/linux'),
                        str(ROOT / 'tests/component-caller/framework_host.c'), '-o', str(library)],
                       check=True)
        # Every test call site binds to the actual public Android SDK descriptors.
        subprocess.run([javac, '-J-XX:-UsePerfData', '-encoding', 'UTF-8',
                        '-cp', str(args.sdk_jar.resolve()), '-d', str(sdk_classes),
                        str(ROOT / 'tests/dex-services/gesher/services/NsdValueContractTest.java')],
                       check=True)
        sources = []
        for name in ('nsd/NsdServiceInfo',):
            relative = 'app/java/android/net/' + name + '.java'
            source = ROOT / relative
            if args.source_ref and name == 'nsd/NsdServiceInfo':
                old = subprocess.run(['git', 'show', args.source_ref + ':' + relative],
                                     cwd=ROOT, capture_output=True, check=True)
                source = work / 'NsdServiceInfo.java'
                source.write_bytes(old.stdout)
            sources.append(str(source))
        subprocess.run([javac, '-J-XX:-UsePerfData', '-encoding', 'UTF-8',
                        '-cp', classpath, '-d', str(classes), *sources], check=True)
        subprocess.run([java, '-XX:-UsePerfData', '-XX:+UnlockDiagnosticVMOptions',
                        '-XX:-BytecodeVerificationRemote', '-cp',
                        os.pathsep.join((str(classes), str(sdk_classes), classpath)),
                        'gesher.services.NsdValueContractTest', str(library), args.case],
                       check=True, timeout=60)
        print('Public SDK call sites and real production NSD value type; '
              'host initialization adapter only. Native Parcel is a separate Windows gate.')


if __name__ == '__main__':
    main()
