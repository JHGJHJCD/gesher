#!/usr/bin/env python3
"""Compile the production raw-syscall/regex adapter and exercise its public ABI."""
import argparse
import hashlib
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=ROOT / "compat/bionic/syscall_bionic.cc")
    parser.add_argument("--cxx", default=os.environ.get("CXX", "c++"))
    parser.add_argument("--sanitize", action="store_true")
    args = parser.parse_args()
    print("Production source SHA256 " + hashlib.sha256(args.source.read_bytes()).hexdigest(), flush=True)
    with tempfile.TemporaryDirectory(prefix="gesher-raw-syscall-") as folder:
        executable = Path(folder) / "raw-syscall-contracts"
        # -O0 also avoids GCC's optimized std::regex internal-state warning;
        # this fixture checks ABI behavior rather than regex throughput.
        command = shlex.split(args.cxx) + ["-std=c++20", "-O0", "-g", "-Wall", "-Wextra", "-Werror",
            "-I", str(ROOT / "compat/bionic"), "-I", str(ROOT / "compat/bionic/include"),
            "-I", str(ROOT / "compat/arm64/include"), "-I", str(ROOT / "compat/elf/include"),
            str(args.source), str(ROOT / "tests/native/raw_syscall_host_test.cc"), "-o", str(executable)]
        if args.sanitize:
            command += ["-fsanitize=address,undefined", "-fno-omit-frame-pointer"]
        subprocess.run(command, check=True, timeout=120)
        return subprocess.run([str(executable)], cwd=folder, timeout=30).returncode


if __name__ == "__main__":
    raise SystemExit(main())
