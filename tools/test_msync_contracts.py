#!/usr/bin/env python3
"""Verify production msync range validation before native views or copy writes."""
import argparse
import hashlib
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent


def function(source, first, following):
    if source.count(first) != 1 or source.count(following) != 1:
        raise ValueError("ambiguous production function: " + first)
    return source[source.index(first):source.index(following, source.index(first))]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cxx", default=os.environ.get("CXX", "c++"))
    parser.add_argument("--sanitize", action="store_true")
    args = parser.parse_args()
    mapper = (ROOT / "compat/posix/src/mman.cc").read_text()
    loader = (ROOT / "compat/elf/fs_rewrite.cc").read_text()
    source = function(mapper, "int msync(void*", "\nint mincore(") + function(
        loader, "int SyncLoadedMemory(", "\nint UnmapLoadedMemory(")
    template = (ROOT / "tests/mman/msync_host_test.cc.in").read_text()
    if template.count("@PRODUCTION_MSYNC@") != 1:
        raise ValueError("ambiguous production template marker")
    print("Production section SHA256 " + hashlib.sha256(source.encode()).hexdigest(), flush=True)
    with tempfile.TemporaryDirectory(prefix="gesher-msync-") as folder:
        generated = Path(folder) / "test.cc"
        executable = Path(folder) / "test"
        generated.write_text(template.replace("@PRODUCTION_MSYNC@", source))
        command = shlex.split(args.cxx) + ["-std=c++20", "-O1", "-g", "-Wall", "-Wextra", "-Werror",
            str(generated), "-o", str(executable)]
        if args.sanitize:
            command += ["-fsanitize=address,undefined", "-fno-omit-frame-pointer"]
        subprocess.run(command, check=True, timeout=120)
        return subprocess.run([str(executable)], cwd=folder, timeout=30).returncode


if __name__ == "__main__":
    raise SystemExit(main())
