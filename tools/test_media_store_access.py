#!/usr/bin/env python3
"""Verify SDK-compiled MediaStore access calls against production and real framework clients.

The fixture controls provider replies and app-op answers; framework ContentResolver,
ContentProviderClient, Uri, Bundle and permission mapping remain real. The host adapter
provides native initialization/identity only. Real Binder/Parcel and Windows execution
are the separate ART fixture gate. --source-ref supplies an old-code linkage control.
"""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('java-home', 'sdk-jar', 'framework-classes', 'core-classes', 'app-classes'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--source-ref')
    parser.add_argument('--case', choices=('all', 'management', 'document', 'media', 'redacted', 'redacted-list'),
                        default='all')
    args = parser.parse_args()
    cc = shutil.which('cc')
    if not cc:
        parser.error('A C compiler is required for the declared host initialization boundary')
    java_home = args.java_home.resolve()
    javac, java = (str(java_home / 'bin' / command) for command in ('javac', 'java'))
    classpath = os.pathsep.join(str(path.resolve()) for path in
                              (args.app_classes, args.framework_classes, args.core_classes))
    source_path = 'app/java/android/provider/MediaStore.java'
    with tempfile.TemporaryDirectory(prefix='gesher-media-store-access-') as temporary:
        work = Path(temporary)
        classes, sdk_classes = work / 'classes', work / 'sdk-classes'
        classes.mkdir()
        sdk_classes.mkdir()
        library = work / 'libmedia_store_access_host.so'
        subprocess.run([cc, '-std=c11', '-D_POSIX_C_SOURCE=200809L', '-shared', '-fPIC',
                        '-I' + str(java_home / 'include'), '-I' + str(java_home / 'include/linux'),
                        str(ROOT / 'tests/location/framework_host.c'), '-o', str(library)], check=True)
        sdk_source = ROOT / 'tests/dex-services/gesher/services/MediaStoreAccessSdkCalls.java'
        subprocess.run([javac, '-J-XX:-UsePerfData', '-encoding', 'UTF-8', '-cp', str(args.sdk_jar.resolve()),
                        '-d', str(sdk_classes), str(sdk_source)], check=True)
        source = ROOT / source_path
        if args.source_ref:
            content = subprocess.run(['git', 'show', args.source_ref + ':' + source_path],
                                     cwd=ROOT, capture_output=True, check=True).stdout
            source = work / 'MediaStore.java'
            source.write_bytes(content)
        print('MediaStore.java sha256=' + hashlib.sha256(source.read_bytes()).hexdigest(), flush=True)
        subprocess.run([javac, '-J-XX:-UsePerfData', '-encoding', 'UTF-8',
                        '-cp', str(sdk_classes) + os.pathsep + classpath, '-d', str(classes),
                        str(source),
                        str(ROOT / 'tests/dex-services/gesher/services/MediaStoreAccessContractTest.java')], check=True)
        # Real Android Binder holders register through the core's Cleaner. Its package
        # belongs to jdk.unsupported on the host JDK, so load these exact input classes there.
        cleaner = work / 'cleaner' / 'sun' / 'misc'
        cleaner.mkdir(parents=True)
        cleaner_inputs = list((args.core_classes / 'sun/misc').glob('Cleaner*.class'))
        if not cleaner_inputs:
            raise ValueError('Android core input has no Cleaner classes')
        for entry in cleaner_inputs:
            shutil.copy2(entry, cleaner / entry.name)
        subprocess.run([java, '-XX:-UsePerfData', '-XX:+UnlockDiagnosticVMOptions',
                        '-XX:-BytecodeVerificationRemote', '--patch-module', 'jdk.unsupported=' + str(work / 'cleaner'),
                        '-cp', os.pathsep.join((str(classes), str(sdk_classes), classpath)),
                        'gesher.services.MediaStoreAccessContractTest', str(library), '--case', args.case],
                       check=True, timeout=90)
        print('SDK call sites + production MediaStore + real framework clients; controlled provider/app-op/native boundaries.')


if __name__ == '__main__':
    main()
