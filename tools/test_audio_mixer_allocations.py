#!/usr/bin/env python3
"""Measure real Track mixing and optionally compare every output byte to an older source.

The existing audio-state fixture supplies the unchanged production extraction and
declared Engine/clock boundary. This fixture drives looping PCM, not WASAPI.
Allocation counts cover only warmed Track::MixInto calls and position queries.
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
    fixture = (ROOT / "tests/media/audio_mixer_allocations_host.cc.in").read_text()
    return fixture.replace("@PRODUCTION@", production)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-ref", help="Run a previous source with the allocation gate intact")
    parser.add_argument("--compare-ref", help="Also compare all output bytes/positions to this source")
    parser.add_argument("--sanitize", action="store_true")
    parser.add_argument("--cxx", default=os.environ.get("CXX", "c++"))
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="gesher-audio-mixer-") as temporary:
        work = Path(temporary)

        def run(label: str, ref: str | None, enforce: bool) -> tuple[int, Path]:
            cpp = work / (label + ".cc")
            cpp.write_text(source(ref))
            binary = work / label
            output = work / (label + ".pcm")
            command = shlex.split(args.cxx) + [
                "-std=c++20", "-O2", "-g", "-Wall", "-Wextra", "-Werror", "-fno-exceptions",
                "-pthread", str(cpp), "-o", str(binary)]
            if args.sanitize:
                command += ["-fsanitize=address,undefined", "-fno-sanitize-recover=all",
                            "-fno-omit-frame-pointer"]
            subprocess.run(command, check=True, timeout=120)
            result = subprocess.run([str(binary), str(output), "gate" if enforce else "measure"],
                                    check=False, timeout=120)
            return result.returncode, output

        result, candidate = run("candidate", args.source_ref, True)
        if args.compare_ref:
            reference_result, reference = run("reference", args.compare_ref, False)
            if reference_result:
                return reference_result
            if candidate.read_bytes() != reference.read_bytes():
                raise SystemExit("Mixer output or positions differ from reference")
            print(f"Bit-exact mixer output/position comparison: {candidate.stat().st_size} bytes PASS",
                  flush=True)
        return result


if __name__ == "__main__":
    raise SystemExit(main())
