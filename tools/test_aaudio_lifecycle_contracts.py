#!/usr/bin/env python3
"""Run complete production AAudio code with deterministic Track/Capture observers.

The platform includes are replaced by declared host device seams; every AAudio
function and the real export table are compiled unchanged. No hardware, Windows
or ELF execution is inferred. --source-ref uses the same assertions against the
chosen revision, skipping only callback-thread close/release controls that would
make the known baseline join its own thread. The actual NDK caller is separate.
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


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-ref")
    parser.add_argument("--cxx", default=os.environ.get("CXX", "c++"))
    parser.add_argument("--sanitize", action="store_true")
    args = parser.parse_args()
    if args.source_ref:
        source = subprocess.run(
            ["git", "show", args.source_ref + ":compat/media/ndk/aaudio.cc"],
            cwd=ROOT, check=True, capture_output=True, text=True).stdout
    else:
        source = (ROOT / "compat/media/ndk/aaudio.cc").read_text()
    boundary = "namespace gesher::media {"
    if source.count(boundary) != 1:
        raise ValueError("AAudio namespace boundary changed")
    production = source[source.index(boundary):]
    print("complete production AAudio SHA256 " + hashlib.sha256(production.encode()).hexdigest(),
          flush=True)
    template = (ROOT / "tests/media/aaudio_lifecycle_host.cc.in").read_text()
    if template.count("@PRODUCTION_IMPLEMENTATION@") != 1:
        raise ValueError("host fixture marker changed")
    with tempfile.TemporaryDirectory(prefix="gesher-aaudio-lifecycle-") as tmp:
        folder = Path(tmp)
        cpp = folder / "aaudio.cc"
        cpp.write_text(template.replace("@PRODUCTION_IMPLEMENTATION@", production))
        executable = folder / "test"
        flags = ["-std=c++20", "-O1", "-g", "-Wall", "-Wextra", "-Werror",
                 "-fno-exceptions", "-pthread"]
        if args.sanitize:
            flags += ["-fsanitize=address,undefined", "-fno-sanitize-recover=all"]
        subprocess.run(shlex.split(args.cxx) + flags + [str(cpp), "-o", str(executable)],
                       check=True, timeout=120)
        return subprocess.run([str(executable)] + (["--baseline-safe"] if args.source_ref else []),
                              check=False, timeout=30).returncode


if __name__ == "__main__":
    raise SystemExit(main())
