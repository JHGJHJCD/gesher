#!/usr/bin/env python3
"""Run production camera YUV conversion/JNI against a byte oracle and allocation gate.

JNI byte arrays are declared adapters backed by real owned buffers. Allocation
counts exclude Java-array creation and cover native conversion storage only.
No camera, codec, ART or physical-device speedup is inferred from this fixture.
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


def section(source: str, start: str, end: str) -> str:
    if source.count(start) != 1 or source.count(end) != 1:
        raise ValueError("camera conversion extraction boundary changed")
    begin = source.index(start)
    return source[begin:source.index(end, begin)]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-ref", help="Exercise an older production revision")
    parser.add_argument("--measure-only", action="store_true",
                        help="Skip the allocation gate and new invalid-input checks")
    parser.add_argument("--valid-only", action="store_true",
                        help="Keep the allocation gate but skip invalid-input cases for a baseline")
    parser.add_argument("--sanitize", action="store_true")
    parser.add_argument("--cxx", default=os.environ.get("CXX", "c++"))
    args = parser.parse_args()
    path = "compat/devices/camera_frames.cc"
    source = ((ROOT / path).read_text() if not args.source_ref else
              subprocess.run(["git", "show", args.source_ref + ":" + path], cwd=ROOT,
                             capture_output=True, text=True, check=True).stdout)
    fixture = (ROOT / "tests/media/camera_conversion_allocations_host.cc.in").read_text()
    fixture = fixture.replace("@CONVERSION@", section(source, "void Nv12ToI420(", "inline uint8_t Clamp("))
    fixture = fixture.replace("@JNI@", section(source, "jbyteArray CameraFrames_toNv21(",
                                               "// CameraFrames.nativeIsReader:"))
    print("camera conversion source SHA256 " + hashlib.sha256(source.encode()).hexdigest(), flush=True)
    with tempfile.TemporaryDirectory(prefix="gesher-camera-conversion-") as temporary:
        work = Path(temporary)
        cpp = work / "camera.cc"
        cpp.write_text(fixture)
        binary = work / ("camera.exe" if os.name == "nt" else "camera")
        command = shlex.split(args.cxx) + ["-std=c++20", "-O2", "-g", "-Wall", "-Wextra",
                                         "-Werror", "-fno-exceptions", str(cpp), "-o", str(binary)]
        if args.sanitize:
            command += ["-fsanitize=address,undefined", "-fno-sanitize-recover=all",
                        "-fno-omit-frame-pointer"]
        runtime_env = os.environ.copy()
        compiler = Path(command[0])
        if os.name == "nt" and compiler.is_absolute():
            runtime_env["PATH"] = str(compiler.parent) + os.pathsep + runtime_env.get("PATH", "")
        subprocess.run(command, check=True, timeout=120)
        mode = "measure" if args.measure_only else "valid-gate" if args.valid_only else "gate"
        result = subprocess.run([str(binary), mode], check=False, timeout=120, env=runtime_env)
        if result.returncode:
            print(f"Camera conversion fixture failed with exit code {result.returncode}", flush=True)
        return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
