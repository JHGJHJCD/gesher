#!/usr/bin/env python3
"""Run the data-only final-page fixture against the actual Linux memfd kernel API.

Unlike test_memfd_contracts.py's regular-tempfile Win32 boundary model, this
uses real memfd storage and the same source used by Android/Windows CI.
Each case runs in a fresh process; failure or a signal cannot hide later cases.
"""
import argparse
import hashlib
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
CASES = ("logical-length", "rounded-length", "nonfixed", "aliases-reused-fd",
         "truncate", "noaccess", "partial-unmap")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cc", default=os.environ.get("CC", "cc"))
    args = parser.parse_args()
    source = ROOT / "tests/native/memfd_tail_test.c"
    print("Source SHA256 " + hashlib.sha256(source.read_bytes()).hexdigest(), flush=True)
    with tempfile.TemporaryDirectory(prefix="gesher-memfd-tail-") as temporary:
        executable = Path(temporary) / "memfd-tail-reference"
        command = shlex.split(args.cc) + ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
            "-DGESHER_MEMFD_TAIL_STANDALONE", str(source), "-o", str(executable)]
        subprocess.run(command, check=True, timeout=120)
        failed = []
        for case in CASES:
            environment = os.environ.copy()
            environment["GESHER_MEMFD_TAIL_CASE"] = case
            try:
                result = subprocess.run([str(executable)], env=environment, cwd=temporary,
                                        text=True, capture_output=True, timeout=30)
                print(result.stdout, end="", flush=True)
                if result.stderr:
                    print(result.stderr, end="", flush=True)
                if result.returncode or "memfd_tail: all passed" not in result.stdout:
                    failed.append(case)
                    print(f"FAIL {case}: exit={result.returncode}", flush=True)
                else:
                    print("PASS " + case, flush=True)
            except subprocess.TimeoutExpired as error:
                failed.append(case)
                for output in (error.stdout, error.stderr):
                    if output:
                        print(output.decode(errors="replace") if isinstance(output, bytes) else output,
                              end="", flush=True)
                print("FAIL " + case + ": timeout", flush=True)
        return int(bool(failed))


if __name__ == "__main__":
    raise SystemExit(main())
