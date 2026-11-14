#!/usr/bin/env python3
"""Test exact retriever snapshot/Player reset methods with declared container/worker seams.

The mock Format preserves the real absent-key output contract. Callback closure
observes the production publication/retirement order; a separate bounded thread
checks mutex ownership without attempting a recursive blocking lock. Receiver
lookup and JNI exception reporting are declared boundaries for this state-only
fixture. The real receiver lease and checked JNI run in the separate callback
suite; actual readers/public APIs run in MediaDataSourceContractTest on Windows.
The bitrate boundary group runs only on the candidate; old-source controls
exclude it rather than execute the original signed-overflow expression.
"""
from __future__ import annotations
import argparse, hashlib, os, shlex, subprocess, tempfile
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]


def function(source, name):
    start = source.index(name)
    brace = source.index('{', start)
    end, depth = brace + 1, 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source-ref')
    p.add_argument('--sanitize', action='store_true')
    p.add_argument('--group', action='append', choices=('metadata', 'reset', 'bitrate'))
    p.add_argument('--cxx', default=os.environ.get('CXX', 'c++'))
    args = p.parse_args()
    groups = args.group or ['metadata', 'reset', 'bitrate']
    if args.source_ref:
        groups = [g for g in groups if g != 'bitrate']
        if not groups:
            p.error('old-source controls exclude the signed-overflow bitrate path')
    def read(path):
        return subprocess.check_output(['git', 'show', args.source_ref + ':' + path], cwd=ROOT,
            text=True) if args.source_ref else (ROOT / path).read_text()
    retriever = read('compat/media/jni/media_jni_others.cc')
    player = read('compat/media/player/player.cc')
    fixture = (ROOT / 'tests/media/callback/state_host.cc.in').read_text()
    if 'struct RetrieverSnapshot {' in retriever:
        receiver = ('struct ReceiverState {};\n'
            + function(retriever, 'struct RetrieverSnapshot {') + ';\n'
            + function(retriever, 'struct Retriever : ReceiverState {') + ';\n'
            + 'RetrieverSnapshot& State(Retriever& r) { return r.snapshot; }\n')
        publication = '''using JNIEnv = void;
using jobject = void*;
static Retriever* active_receiver;
static bool publication_failed;
Retriever* GetRetriever(JNIEnv*, jobject) { return active_receiver; }
void jniThrow(JNIEnv*, const char*, const char*) { publication_failed = true; }
#define MEDIA_LOGE(...) ((void)0)
''' + function(retriever, 'void SetFromSource(') + '''
bool Publish(Retriever* receiver, std::unique_ptr<DataSource> source, std::string* error) {
  active_receiver = receiver;
  publication_failed = false;
  SetFromSource(nullptr, nullptr, std::move(source), *error);
  active_receiver = nullptr;
  return !publication_failed;
}
'''
    else:
        receiver = (function(retriever, 'struct Retriever {') + ';\n'
            + 'Retriever& State(Retriever& r) { return r; }\n')
        publication = '''bool Publish(Retriever* receiver, std::unique_ptr<DataSource> source,
             std::string* error) {
  return Fill(receiver, std::move(source), error);
}
'''
    values = {'@RETRIEVER@': receiver,
        '@KEYS@': retriever[retriever.index('const struct {'):retriever.index('\nbool Fill(')],
        '@FILL@': function(retriever, 'bool Fill('),
        '@PUBLISH@': publication,
        '@RESET@': function(player, 'int Player::Reset()')}
    for marker, value in values.items():
        if fixture.count(marker) != 1: raise ValueError(marker)
        fixture = fixture.replace(marker, value)
    print('media callback state SHA256', hashlib.sha256((retriever + player).encode()).hexdigest(), flush=True)
    failed = False
    with tempfile.TemporaryDirectory(prefix='gesher-media-callback-state-') as tmp:
        work = Path(tmp); cpp = work / 'state.cc'; exe = work / 'state'
        cpp.write_text(fixture)
        command = shlex.split(args.cxx) + ['-std=c++20', '-O1', '-g', '-Wall', '-Wextra',
            '-Werror', '-pthread', '-I', str(ROOT), str(cpp), '-o', str(exe)]
        if args.sanitize:
            command += ['-fsanitize=address,undefined', '-fno-sanitize-recover=all', '-fno-omit-frame-pointer']
        subprocess.run(command, check=True, timeout=120)
        for group in groups:
            failed |= subprocess.run([str(exe), group], timeout=30).returncode != 0
    return int(failed)


if __name__ == '__main__': raise SystemExit(main())
