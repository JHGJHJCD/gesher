#!/usr/bin/env python3
"""Exercise capture-engine selection using its exact production method.

The registry, membership scan and weak/strong ownership are real. Stream state
is an atomic stand-in, and callers serialize access as the engine does. This
does not exercise the capture worker, its clock or recording hardware.
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


def method(source: str, begin: str) -> str:
    if source.count(begin) != 1:
        raise ValueError("capture-engine selection boundary changed")
    start = source.index(begin)
    at = source.index("{", start) + 1
    depth = 1
    while depth:
        depth += (source[at] == "{") - (source[at] == "}")
        at += 1
    return source[start:at]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-ref")
    parser.add_argument("--measure-only", action="store_true")
    parser.add_argument("--sanitize", action="store_true")
    parser.add_argument("--cxx", default=os.environ.get("CXX", "c++"))
    args = parser.parse_args()
    path = "compat/media/audio/capture.cc"
    source = ((ROOT / path).read_text(encoding="utf-8") if not args.source_ref else
              subprocess.check_output(["git", "show", args.source_ref + ":" + path], cwd=ROOT, text=True))
    current = "bool Started(std::vector" in source
    selection = method(source, "  bool Started(" if current else "  std::vector<std::shared_ptr<CaptureStream>> Started(")
    wrappers = ("bool Any() { return Started(); }\n"
                "std::vector<std::shared_ptr<CaptureStream>> Snapshot() { std::vector<std::shared_ptr<CaptureStream>> out; Started(&out); return out; }"
                if current else
                "bool Any() { return !Started().empty(); }\n"
                "std::vector<std::shared_ptr<CaptureStream>> Snapshot() { return Started(); }")
    fixture = (ROOT / "tests/media/capture_stream_selection_host.cc.in").read_text(encoding="utf-8")
    fixture = fixture.replace("@SELECTION@", selection).replace("@WRAPPERS@", wrappers)
    print("capture selection SHA256 " + hashlib.sha256(selection.encode()).hexdigest(), flush=True)
    with tempfile.TemporaryDirectory(prefix="gesher-capture-selection-") as temporary:
        work = Path(temporary)
        cpp = work / "selection.cc"
        cpp.write_text(fixture, encoding="utf-8")
        binary = work / ("selection.exe" if os.name == "nt" else "selection")
        command = shlex.split(args.cxx) + ["-std=c++20", "-O2", "-g", "-Wall", "-Wextra", "-Werror",
                                         "-fno-exceptions", "-pthread", str(cpp), "-o", str(binary)]
        if os.name == "nt":
            command += ["-static"]
        if args.sanitize:
            command += ["-fsanitize=address,undefined", "-fno-sanitize-recover=all", "-fno-omit-frame-pointer"]
            # Some restricted Windows hosts do not search PATH for dependent
            # runtime DLLs. Stage only the matching compiler's runtime beside
            # this throwaway executable.
            if os.name == "nt":
                for name in ("libclang_rt.asan_dynamic-x86_64.dll", "libc++.dll", "libunwind.dll"):
                    runtime = Path(command[0]).parent / name
                    if runtime.is_file():
                        shutil.copyfile(runtime, work / name)
        subprocess.run(command, check=True, timeout=120)
        return subprocess.run([str(binary), "measure" if args.measure_only else "gate"], check=False, timeout=60).returncode


if __name__ == "__main__":
    raise SystemExit(main())
