#!/usr/bin/env python3
"""Production effect JNI against real framework AudioEffect/Visualizer classes.

Only attribution/Parcel and logging are replaced at the host boundary. The
classes' public constructors, queries, state transitions, JNI signatures and
finalizers are unmodified. A separate public guest fixture runs on ART/Windows.
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
def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--framework-classes", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--cxx", default=os.environ.get("CXX", "c++"))
    parser.add_argument("--negative-control", action="store_true", help="restore false-success setup in disposable build; must fail")
    args = parser.parse_args()
    java = args.java_home or Path(shutil.which("javac") or "missing-javac").resolve().parent.parent
    source = (ROOT / "compat/media/jni/media_jni.cc").read_text()
    begin = source.index("constexpr jint kEffectNoInit")
    implementation = source[begin:source.index("// --- End unavailable audio effects", begin)]
    print("Effect production SHA256 " + hashlib.sha256(implementation.encode()).hexdigest(), flush=True)
    if args.negative_control: implementation = implementation.replace("return kEffectUnsupported;", "return 0;")
    template = (ROOT / "tests/media/audio_effect_host.cc.in").read_text().replace("@EFFECTS@", implementation)
    failed = False
    with tempfile.TemporaryDirectory(prefix="gesher-audio-effects-") as tmp:
        folder = Path(tmp); cpp = folder / "effects.cc"; cpp.write_text(template)
        library = folder / "libaudioeffect_jni.so"
        subprocess.run(shlex.split(args.cxx) + ["-std=c++20", "-O1", "-g", "-Wall", "-Wextra", "-Werror", "-Wno-write-strings",
                       "-fno-exceptions", "-shared", "-fPIC", "-fsanitize=undefined", "-fno-sanitize-recover=undefined",
                       "-I", str(java / "include"), "-I", str(java / "include/linux"), str(cpp), "-o", str(library)], check=True, timeout=120)
        subprocess.run([str(java / "bin/javac"), "-classpath", str(args.framework_classes), "-d", str(folder)] +
                       [str(p) for p in (ROOT / "tests/media/effects_host").rglob("*.java")], check=True, timeout=60)
        for case in (("constructors",) if args.negative_control else ("queries", "constructors")):
            result = subprocess.run([str(java / "bin/java"), "-Xcheck:jni", "-XX:-CreateCoredumpOnCrash", "-Djava.library.path=" + str(folder),
                                     "-cp", str(folder) + os.pathsep + str(args.framework_classes), "AudioEffectHostTest", case],
                                     capture_output=True, text=True, timeout=30)
            print(result.stdout, end=""); print(result.stderr, end="")
            failed |= result.returncode != 0 or "WARNING in native method" in result.stdout
    return int(failed)
if __name__ == "__main__": raise SystemExit(main())
