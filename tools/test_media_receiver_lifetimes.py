#!/usr/bin/env python3
"""Run whole production SoundPool/MediaRecorder JNI units on a checked host JVM.

The native object fields, global references, receiver helper, Pool and JNI bodies
are production code. Audio tracks/decoders and the recorder backend are explicit
observers; this is not a WASAPI/codec/Android boot test. The host JDK names its
FileDescriptor integer 'fd', instead of Android's 'descriptor'; that field-name
adaptation is the only change to the compiled production units.

--source-ref runs only the two barrier-controlled lifetime cases. On a premature
destruction assertion, the JVM halts before letting the blocked operation resume:
the baseline control detects lost ownership without executing a use-after-free.
"""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CASES = ('pool-lifetime', 'pool-volume', 'recorder-lifetime',
         'recorder-serialization', 'concurrent-release', 'recorder-reentry')


def section(source: str, start: str, end: str) -> str:
    if source.count(start) != 1 or source.count(end) != 1:
        raise ValueError('production section boundary changed: ' + start)
    begin = source.index(start)
    return source[begin:source.index(end, begin)]


POOL_REGISTRATION = r'''
namespace gesher::media {
int RegisterPoolProbe(JNIEnv* env, jclass cls) {
  gContext = env->GetFieldID(cls, "context", "J");
  gPostEvent = env->GetMethodID(cls, "loaded", "(IIILjava/lang/Object;)V");
  const JNINativeMethod methods[] = {
    {"poolSetup", "(ILjava/lang/Object;Ljava/lang/String;)I", GESHER_NATIVE(SP_setup)},
    {"poolRelease", "()V", GESHER_NATIVE(SP_release)},
    {"poolLoad", "(Ljava/io/FileDescriptor;JJI)I", GESHER_NATIVE(SP_load)},
    {"poolPlay", "(IFFIIFI)I", GESHER_NATIVE(SP_play)},
    {"poolMute", "(Z)V", GESHER_NATIVE(SP_mute)},
    {"poolVolume", "(IFF)V", GESHER_NATIVE(SP_setVolume)},
  };
  return env->RegisterNatives(cls, methods, sizeof(methods) / sizeof(methods[0]));
}
}
'''

RECORDER_REGISTRATION = r'''
namespace gesher::media {
jint JNICALL References(JNIEnv* env, jobject thiz) {
#if RETAINED_RECEIVER
  std::lock_guard<std::mutex> lock(gLock);
  auto* receiver = reinterpret_cast<JniRecorder*>(env->GetLongField(thiz, gF.context));
  return receiver != nullptr ? receiver->references.load() : 0;
#else
  (void)env; (void)thiz; return 0;
#endif
}
int RegisterRecorderProbe(JNIEnv* env, jclass cls) {
  gF.context = env->GetFieldID(cls, "context", "J");
  gF.clazz = static_cast<jclass>(env->NewGlobalRef(cls));
  gF.post_event = env->GetStaticMethodID(cls, "recorderEvent", "(Ljava/lang/Object;IIILjava/lang/Object;)V");
  const JNINativeMethod methods[] = {
    {"recorderSetup", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;)V", GESHER_NATIVE(MR_setup)},
    {"recorderRelease", "()V", GESHER_NATIVE(MR_release)},
    {"recorderPrepare", "()V", GESHER_NATIVE(MR_prepare)},
    {"recorderStart", "()V", GESHER_NATIVE(MR_start)},
    {"recorderReset", "()V", GESHER_NATIVE(MR_reset)},
    {"recorderAmplitude", "()I", GESHER_NATIVE(MR_getMaxAmplitude)},
    {"recorderSurface", "()Ljava/lang/Object;", GESHER_NATIVE(MR_getSurface)},
    {"recorderReferences", "()I", GESHER_NATIVE(References)},
  };
  return env->RegisterNatives(cls, methods, sizeof(methods) / sizeof(methods[0]));
}
}
'''

OBSERVER = r'''
#include "host.h"
namespace gesher::media {
int RegisterPoolProbe(JNIEnv*, jclass);
int RegisterRecorderProbe(JNIEnv*, jclass);
Gate& Selected(jint which) { return which == 0 ? source_gate : recorder_gate; }
void JNICALL Arm(JNIEnv*, jclass, jint which) { Selected(which).Arm(); }
jboolean JNICALL Await(JNIEnv*, jclass, jint which) { return Selected(which).Await(); }
void JNICALL Open(JNIEnv*, jclass, jint which) { Selected(which).Open(); }
jint JNICALL Count(JNIEnv*, jclass, jint which) {
  switch (which) {
    case 0: return track_releases;
    case 1: return recorder_deletes;
    case 2: return resets;
    default: return surfaces;
  }
}
jfloat JNICALL Volume(JNIEnv*, jclass, jint side) { return side == 0 ? last_left.load() : last_right.load(); }
void JNICALL EventMode(JNIEnv*, jclass, jint mode) { recorder_event_mode = mode; }
}
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  using namespace gesher::media;
  host_vm = vm;
  JNIEnv* env = AttachedEnv("host");
  jclass cls = env->FindClass("MediaReceiverLifetimeHost");
  if (cls == nullptr || RegisterPoolProbe(env, cls) != JNI_OK || RegisterRecorderProbe(env, cls) != JNI_OK)
    return JNI_ERR;
  const JNINativeMethod methods[] = {
    {"arm", "(I)V", GESHER_NATIVE(Arm)},
    {"await", "(I)Z", GESHER_NATIVE(Await)},
    {"open", "(I)V", GESHER_NATIVE(Open)},
    {"count", "(I)I", GESHER_NATIVE(Count)},
    {"volume", "(I)F", GESHER_NATIVE(Volume)},
    {"eventMode", "(I)V", GESHER_NATIVE(EventMode)},
  };
  if (env->RegisterNatives(cls, methods, sizeof(methods) / sizeof(methods[0])) != JNI_OK) return JNI_ERR;
  return JNI_VERSION_1_6;
}
'''


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, default=os.environ.get('JAVA_HOME'))
    parser.add_argument('--cxx', default=os.environ.get('CXX', 'c++'))
    parser.add_argument('--source-ref')
    parser.add_argument('--case', choices=CASES)
    args = parser.parse_args()
    java = args.java_home or Path(shutil.which('javac') or 'missing-javac').resolve().parent.parent
    cases = (args.case,) if args.case else CASES
    if args.source_ref:
        safe_cases = ('pool-lifetime', 'recorder-lifetime')
        if args.case and args.case not in safe_cases:
            parser.error('--source-ref supports only bounded lifetime cases')
        cases = (args.case,) if args.case else safe_cases

    def read(path: str) -> str:
        if args.source_ref:
            return subprocess.check_output(['git', 'show', args.source_ref + ':' + path], cwd=ROOT, text=True)
        return (ROOT / path).read_text()

    header = (ROOT / 'tests/media/receiver_lifetime_host.h.in').read_text()
    header = header.replace('@GLOBAL_REFS@', section(read('compat/media/jni/media_jni.h'),
                             'class GlobalRef {', '// Throws a new'))
    header = header.replace('@RECORDER_CONFIG@', section(read('compat/media/record/recorder.h'),
                             'struct RecorderConfig {', 'class Recorder {'))
    failed = False
    with tempfile.TemporaryDirectory(prefix='gesher-media-receiver-') as temporary:
        work = Path(temporary)
        (work / 'host.h').write_text(header)
        units = []
        for name, registration in [('soundpool_jni', POOL_REGISTRATION), ('media_recorder_jni', RECORDER_REGISTRATION)]:
            source = read('compat/media/jni/' + name + '.cc')
            print(name + ' SHA256 ' + hashlib.sha256(source.encode()).hexdigest(), flush=True)
            body = source[source.index('namespace gesher::media {'):]
            if body.count('"descriptor", "I"') != 1:
                raise ValueError('FileDescriptor host boundary changed')
            body = body.replace('"descriptor", "I"', '"fd", "I"')
            unit = work / (name + '.cc')
            retained = 'struct JniRecorder : ReceiverState' in source
            unit.write_text('#include "host.h"\n#define RETAINED_RECEIVER ' + str(int(retained)) + '\n'
                            + body + registration)
            units.append(str(unit))
        observer = work / 'observer.cc'; observer.write_text(OBSERVER)
        library = work / 'libmedia_receiver.so'
        command = shlex.split(args.cxx) + ['-std=c++20', '-O1', '-g', '-Wall', '-Wextra', '-Werror',
            '-Wno-write-strings', '-fno-exceptions', '-shared', '-fPIC', '-pthread',
            '-fsanitize=undefined', '-fno-sanitize-recover=undefined',
            '-I', str(ROOT), '-I', str(java / 'include'), '-I', str(java / 'include/linux'),
            *units, str(observer), '-o', str(library)]
        subprocess.run(command, check=True, timeout=120)
        subprocess.run([str(java / 'bin/javac'), '-d', str(work),
                        str(ROOT / 'tests/media/MediaReceiverLifetimeHost.java')], check=True, timeout=60)
        for case in cases:
            run = subprocess.run([str(java / 'bin/java'), '-Xcheck:jni', '-XX:-UsePerfData',
                '-XX:-CreateCoredumpOnCrash', '-cp', str(work), 'MediaReceiverLifetimeHost',
                str(library), case], capture_output=True, text=True, timeout=30)
            print(run.stdout, end=''); print(run.stderr, end='')
            failed |= run.returncode != 0 or 'WARNING in native method' in run.stdout
    return int(failed)


if __name__ == '__main__':
    raise SystemExit(main())
