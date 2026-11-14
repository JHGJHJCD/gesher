#!/usr/bin/env python3
"""Check real Track filter-history bounds and playback across rate changes.

This uses the existing production Track extraction and declared Engine/clock
boundary. Every parameter change first mixes zero output frames, then checks
the tap bound before permitting a positive-frame mix. An older source therefore
fails by assertion without executing its invalid history access.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

from test_audio_state_contracts import generate

ROOT = Path(__file__).resolve().parents[1]


def source(ref: str | None) -> str:
    production = generate("audio", ref)
    boundary = "namespace {\nusing namespace gesher::media;"
    if production.count(boundary) != 1:
        raise ValueError("audio state fixture boundary changed")
    production = production[:production.index(boundary)]
    fixture = (ROOT / "tests/media/audio_rate_history_host.cc.in").read_text()
    return fixture.replace("@PRODUCTION@", production)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-ref", help="Safely check the same invariants on an older source")
    parser.add_argument("--sanitize", action="store_true")
    parser.add_argument("--cxx", default=os.environ.get("CXX", "c++"))
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="gesher-audio-rate-history-") as temporary:
        work = Path(temporary)
        cpp = work / "rate_history.cc"
        cpp.write_text(source(args.source_ref), encoding="utf-8")
        binary = work / "rate_history"
        command = shlex.split(args.cxx) + [
            "-std=c++20", "-O2", "-g", "-Wall", "-Wextra", "-Werror", "-fno-exceptions",
            "-pthread", str(cpp), "-o", str(binary)]
        if args.sanitize:
            command += ["-fsanitize=address,undefined", "-fno-sanitize-recover=all",
                        "-fno-omit-frame-pointer"]
        subprocess.run(command, check=True, timeout=120)
        return subprocess.run([str(binary)], check=False, timeout=120).returncode


if __name__ == "__main__":
    raise SystemExit(main())
