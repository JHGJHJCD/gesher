#!/usr/bin/env python3
"""Run the production MediaDataSource adapter on a real checked host JVM/JNI.

Only VM attachment/logging and the abstract Android callback type are declared
host boundaries. The entire production adapter is compiled unchanged; Windows
MediaDataSourceContractTest supplies actual ART, media readers and public APIs.
--source-ref safely exercises the former production adapter without invoking
its known invalid-JNI cleanup path. An extra bounded destination suffix keeps
invalid callback-count controls from writing outside an allocated object.
"""
from __future__ import annotations
import argparse
import hashlib
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
GROUPS = ('valid', 'negative', 'exception', 'overcount', 'known-size', 'unknown-size',
          'size-error', 'serialized', 'close-error', 'pending')


def section(text: str, start: str, end: str) -> str:
    if text.count(start) != 1 or text.count(end) != 1:
        raise ValueError('production adapter boundary changed')
    return text[text.index(start):text.index(end, text.index(start))]


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source-ref')
    p.add_argument('--group', action='append', choices=GROUPS)
    p.add_argument('--java-home', type=Path, default=os.environ.get('JAVA_HOME'))
    p.add_argument('--cxx', default=os.environ.get('CXX', 'c++'))
    args = p.parse_args()
    java_home = args.java_home
    if java_home is None:
        javac = shutil.which('javac')
        if javac is None: p.error('a full JDK is required')
        java_home = Path(javac).resolve().parent.parent
    groups = args.group or list(GROUPS)
    if args.source_ref:
        groups = [g for g in groups if g != 'pending']
        if not groups: p.error('old-source controls do not invoke invalid-JNI cleanup')
    def read(path):
        return subprocess.check_output(['git', 'show', args.source_ref + ':' + path],
            cwd=ROOT, text=True) if args.source_ref else (ROOT / path).read_text()
    fixture = (ROOT / 'tests/media/callback/adapter_host.cc.in').read_text()
    adapter_source = ROOT / 'compat/media/jni/java_data_source.cc'
    other_sources = []
    if args.source_ref:
        player = read('compat/media/jni/media_player_jni.cc')
        if 'class JavaDataSource :' not in player:
            p.error('this negative control expects the original private JavaDataSource')
        old = section(player, 'class JavaDataSource :', '// --- Natives ')
        common = read('compat/media/jni/media_jni.h')
        refs = section(common, 'class GlobalRef {', '// A weak global reference')
        adapter = refs + old + '''
std::unique_ptr<DataSource> OpenJavaDataSource(JNIEnv* env, jobject source) {
  return std::make_unique<JavaDataSource>(env, source);
}
'''
        fixture = fixture.replace('@ADAPTER@', adapter)
        print('old adapter SHA256', hashlib.sha256(old.encode()).hexdigest(), flush=True)
    else:
        fixture = fixture.replace('@ADAPTER@', '')
        other_sources = [str(adapter_source)]
        print('adapter SHA256', hashlib.sha256(adapter_source.read_bytes()).hexdigest(), flush=True)
    failed = []
    with tempfile.TemporaryDirectory(prefix='gesher-media-callback-') as tmp:
        work = Path(tmp)
        cpp = work / 'host.cc'; cpp.write_text(fixture)
        lib = work / 'libmedia_callback.so'
        command = shlex.split(args.cxx) + ['-std=c++20', '-O1', '-g', '-Wall', '-Wextra',
            '-Werror', '-shared', '-fPIC', '-pthread', '-fsanitize=undefined',
            '-fno-sanitize-recover=undefined', '-I', str(ROOT),
            '-I', str(java_home / 'include'), '-I', str(java_home / 'include/linux'),
            str(cpp), *other_sources, '-o', str(lib)]
        subprocess.run(command, check=True, timeout=120)
        subprocess.run([str(java_home / 'bin/javac'), '-d', str(work),
            str(ROOT / 'tests/media/callback/MediaDataSource.java'),
            str(ROOT / 'tests/media/callback/MediaCallbackHost.java')], check=True, timeout=60)
        for group in groups:
            r = subprocess.run([str(java_home / 'bin/java'), '-Xcheck:jni',
                '-XX:-CreateCoredumpOnCrash', '-XX:-UsePerfData', '-cp', str(work),
                'MediaCallbackHost', str(lib), group], text=True, capture_output=True, timeout=30)
            print(r.stdout, end='', flush=True)
            bad_jni = re.search(r'WARNING in native method|FATAL ERROR in native method|runtime error:',
                                r.stdout + r.stderr)
            if r.returncode or bad_jni:
                failed.append(group)
                print('FAIL', group, 'exit', r.returncode, r.stderr[:4000], flush=True)
        print('MediaDataSource groups:', len(groups) - len(failed), '/', len(groups), flush=True)
    return int(bool(failed))


if __name__ == '__main__':
    raise SystemExit(main())
