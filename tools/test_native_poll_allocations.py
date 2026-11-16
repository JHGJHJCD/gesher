#!/usr/bin/env python3
"""Run the production native Poll against real Windows UDP sockets and WSAPoll.

The fixture supplies real getsockopt/getpeername/peek adapters. ConnectEx
completion is a declared no-op boundary, so this complements the existing full
TCP/UDP lifecycle gates; it does not replace them. A controlled native error
exercises retry compaction; real queued unrequested data drives resampling.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cxx", default=os.environ.get("CXX", "c++"))
    parser.add_argument("--source-ref")
    parser.add_argument("--measure", action="store_true")
    parser.add_argument("--compile-only", action="store_true")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.compile_only and args.output is None:
        parser.error("--compile-only requires --output")
    if os.name != "nt" and not args.compile_only:
        parser.error("this fixture executes real Windows sockets")
    path = "compat/posix/src/winsock.cc"
    source = (subprocess.check_output(["git", "show", f"{args.source_ref}:{path}"], cwd=ROOT, text=True)
              if args.source_ref else (ROOT / path).read_text(encoding="utf-8"))
    begin = "namespace {\n// Winsock's WSAPOLLFD"
    end = "// --- names --------------------------------------------------------------------"
    if source.count(begin) != 1 or source.count(end) != 1:
        raise ValueError("native poll extraction boundary changed")
    implementation = source[source.index(begin):source.index(end)]
    if "class PollScratchLease" in implementation:
        implementation = "#define GESHER_NATIVE_POLL_CACHED_SCRATCH 1\n" + implementation
    template = (ROOT / "tests/native/native_poll_allocations_windows.cc.in").read_text(encoding="utf-8")
    template = template.replace("@IMPLEMENTATION@", implementation)
    template = template.replace("@HEADER@", str(ROOT / "compat/posix/src/socket_internal.h").replace("\\", "/"))
    with tempfile.TemporaryDirectory(prefix="gesher-native-poll-") as temporary:
        folder = Path(temporary)
        cpp = folder / "test.cc"
        executable = args.output.resolve() if args.output else folder / "test.exe"
        executable.parent.mkdir(parents=True, exist_ok=True)
        cpp.write_text(template, encoding="utf-8")
        command = shlex.split(args.cxx) + ["-std=c++20", "-O2", "-g", "-Wall", "-Wextra", "-Werror",
                                         "-fno-exceptions", "-static", "-pthread", str(cpp), "-o", str(executable), "-lws2_32"]
        subprocess.run(command, check=True, timeout=120)
        if args.compile_only:
            print(f"Compiled native poll fixture: {executable}", flush=True)
        else:
            subprocess.run([str(executable), "measure" if args.measure else "gate"], check=True, timeout=60)


if __name__ == "__main__":
    main()
