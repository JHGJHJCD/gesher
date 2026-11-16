#!/usr/bin/env python3
"""Check production file capture using only valid PCM/float WAV samples.

The source interface, pacing and complete FileSource implementation are copied
unchanged. The fixture supplies real files and a known-sample oracle; it does
not open a recording device or generate malformed/truncated input files.
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


def section(source: str, begin: str, end: str) -> str:
    if source.count(begin) != 1 or source.count(end) != 1:
        raise ValueError("audio file source extraction boundary changed")
    at = source.index(begin)
    return source[at:source.index(end, at)]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-ref", help="Exercise an older revision with the same valid samples")
    parser.add_argument("--sanitize", action="store_true")
    parser.add_argument("--cxx", default=os.environ.get("CXX", "c++"))
    args = parser.parse_args()

    def read(path: str) -> str:
        if args.source_ref:
            return subprocess.check_output(["git", "show", args.source_ref + ":" + path], cwd=ROOT, text=True)
        return (ROOT / path).read_text(encoding="utf-8")

    source = read("compat/media/audio/capture.cc")
    parts = {
        "@API@": section(read("compat/media/audio/capture.h"), "class CaptureSource {", "// WASAPI shared mode"),
        "@PACING@": section(source, "class PacedSource :", "class SilentSource :"),
        "@FILE@": section(source, "// A WAV file (", "// The source this machine has"),
    }
    fixture = (ROOT / "tests/media/audio_file_source_host.cc.in").read_text(encoding="utf-8")
    for marker, body in parts.items():
        fixture = fixture.replace(marker, body)
    print("audio file source SHA256 " + hashlib.sha256("".join(parts.values()).encode()).hexdigest(), flush=True)
    with tempfile.TemporaryDirectory(prefix="gesher-audio-file-") as temporary:
        work = Path(temporary)
        cpp = work / "file_source.cc"
        cpp.write_text(fixture, encoding="utf-8")
        binary = work / ("file_source.exe" if os.name == "nt" else "file_source")
        command = shlex.split(args.cxx) + ["-std=c++20", "-O2", "-g", "-Wall", "-Wextra", "-Werror",
                                         "-fno-exceptions", "-pthread", str(cpp), "-o", str(binary)]
        if os.name == "nt" and not args.sanitize:
            command += ["-static"]
        if args.sanitize:
            command += ["-fsanitize=address,undefined", "-fno-sanitize-recover=all", "-fno-omit-frame-pointer"]
        runtime = os.environ.copy()
        compiler = Path(command[0])
        if os.name == "nt" and compiler.is_absolute():
            runtime["PATH"] = str(compiler.parent) + os.pathsep + runtime.get("PATH", "")
        subprocess.run(command, check=True, timeout=120)
        return subprocess.run([str(binary)], cwd=work, env=runtime, check=False, timeout=60).returncode


if __name__ == "__main__":
    raise SystemExit(main())
