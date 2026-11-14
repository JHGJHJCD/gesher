#!/usr/bin/env python3
"""Run the public memory-advice fixture against actual Linux libc/kernel."""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]

with tempfile.TemporaryDirectory(prefix="gesher-madvise-reference-") as temporary:
    executable = Path(temporary) / "madvise-reference"
    subprocess.run([os.environ.get("CC", "cc"), "-std=c17", "-O2", "-Wall", "-Wextra", "-Werror",
                    "-DGESHER_MADVISE_MAIN", str(ROOT / "tests/native/madvise_contract.c"),
                    "-o", str(executable)], check=True)
    subprocess.run([str(executable)], check=True, timeout=30)
