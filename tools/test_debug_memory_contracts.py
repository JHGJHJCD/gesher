#!/usr/bin/env python3
"""Exercise actual Debug memory sampling and JNI helpers at controlled boundaries.

Production buffer management, page arithmetic, field-name construction, Java
exception sequencing and system publication are extracted without alteration.
--source-root runs the same fixture against an earlier revision. The fixture
rejects forbidden C++ allocation before allocating large blocks and, like the
production native, compiles with -fno-exceptions.
"""
import argparse
import hashlib
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def between(source, begin, end):
    if source.count(begin) != 1 or source.count(end) != 1:
        raise ValueError("ambiguous production boundary: " + begin)
    start = source.index(begin)
    return source[start:source.index(end, start)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, default=ROOT)
    parser.add_argument("--cxx", default=os.environ.get("CXX", "c++"))
    parser.add_argument("--sanitize", action="store_true")
    args = parser.parse_args()
    native = (args.source_root / "compat/framework/android_runtime.cc").read_text()
    pieces = {
        "@WORKING_SET@": between(native, "struct WorkingSetUsage {", "// Debug.MemoryInfo's per-category fields"),
        "@CATEGORY_FIELDS@": between(native, "struct CategoryFields {", "struct Category {"),
        "@JAVA_HEAP@": between(native, "jlong JavaHeapKb(JNIEnv* env)", "static jboolean Debug_getMemoryInfoPid"),
        "@SYSTEM_MEMORY@": between(native, "// Debug.MEMINFO_*", "static const JNINativeMethod gDebugMethods[]"),
    }
    closing = "}  // namespace"
    if pieces["@JAVA_HEAP@"].count(closing) != 1:
        raise ValueError("ambiguous Java-heap namespace boundary")
    pieces["@JAVA_HEAP@"] = pieces["@JAVA_HEAP@"].replace(closing, "")
    source = (ROOT / "tests/native/debug_memory_host_test.cc.in").read_text()
    for marker, body in pieces.items():
        if source.count(marker) != 1:
            raise ValueError("ambiguous fixture marker: " + marker)
        source = source.replace(marker, body)
    print("android_runtime.cc", hashlib.sha256(native.encode()).hexdigest(), flush=True)
    with tempfile.TemporaryDirectory(prefix="gesher-debug-memory-") as folder:
        folder = Path(folder)
        unit = folder / "debug_memory.cc"
        output = folder / "debug_memory"
        unit.write_text(source)
        command = shlex.split(args.cxx) + ["-std=c++17", "-fno-exceptions", "-O1", "-g", "-Wall", "-Wextra", "-Werror",
                                         str(unit), "-o", str(output)]
        if args.sanitize:
            command += ["-fsanitize=address,undefined", "-fno-omit-frame-pointer", "-fno-sanitize-recover=all"]
        subprocess.run(command, check=True, timeout=120)
        result = subprocess.run([str(output)], capture_output=True, text=True, timeout=30)
        print(result.stdout, end="", flush=True)
        if result.returncode:
            print(result.stderr[:12000], end="", flush=True)
        return int(result.returncode != 0)


if __name__ == "__main__":
    raise SystemExit(main())
