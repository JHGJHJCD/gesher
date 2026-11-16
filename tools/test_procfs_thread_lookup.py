#!/usr/bin/env python3
"""Run production procfs thread validation against live Windows threads.

Only Toolhelp/OpenThread calls are counted; both wrappers call the real Win32
APIs. The fixture includes current/other/foreign/dead threads and retained
handles. --source-ref and --measure expose the old snapshot work unchanged.
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
    parser.add_argument("--compile-only", action="store_true", help="cross-compile the Windows fixture without executing it")
    parser.add_argument("--output", type=Path, help="retain the compiled executable at this path")
    args = parser.parse_args()
    if args.compile_only and args.output is None:
        parser.error("--compile-only requires --output")
    if os.name != "nt" and not args.compile_only:
        parser.error("this fixture executes real Windows thread ownership APIs")
    if args.source_ref:
        source = subprocess.check_output(["git", "show", f"{args.source_ref}:compat/posix/src/procfs.cc"], cwd=ROOT, text=True)
    else:
        source = (ROOT / "compat/posix/src/procfs.cc").read_text(encoding="utf-8")
    begin = "std::vector<DWORD> ThreadIds() {"
    end = "// The executable's name without its extension"
    if source.count(begin) != 1 or source.count(end) != 1:
        raise ValueError("procfs thread lookup source boundary changed")
    implementation = source[source.index(begin):source.index(end)]
    template = (ROOT / "tests/native/procfs_thread_lookup_windows.cc.in").read_text(encoding="utf-8")
    with tempfile.TemporaryDirectory(prefix="gesher-procfs-threads-") as temporary:
        folder = Path(temporary)
        cpp = folder / "test.cc"
        executable = args.output.resolve() if args.output else folder / "test.exe"
        executable.parent.mkdir(parents=True, exist_ok=True)
        cpp.write_text(template.replace("@IMPLEMENTATION@", implementation), encoding="utf-8")
        subprocess.run(shlex.split(args.cxx) + ["-std=c++20", "-O2", "-Wall", "-Wextra", "-Werror",
                       "-fno-exceptions", "-static", str(cpp), "-o", str(executable)], check=True, timeout=120)
        if args.compile_only:
            print(f"Compiled procfs thread fixture: {executable}", flush=True)
        else:
            subprocess.run([str(executable), "measure" if args.measure else "gate"], check=True, timeout=45)


if __name__ == "__main__":
    main()
