#!/usr/bin/env python3
"""Verify production fnmatch against a dynamic-programming matching oracle.

The oracle independently explores all token/string states. Its bracket tokens
use the unchanged production bracket parser, so this verifies matching and
backtracking, not POSIX locale or bracket parsing semantics. --compare-ref
additionally checks the complete prior matcher on a seeded all-flags corpus.
Instrumentation only increments a counter at the production match-loop entry.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PATH = "compat/posix/src/fnmatch.cc"


def read(ref: str | None) -> str:
    if ref:
        return subprocess.check_output(["git", "show", f"{ref}:{PATH}"], cwd=ROOT, text=True)
    return (ROOT / PATH).read_text(encoding="utf-8")


def implementation(source: str, namespace: str) -> str:
    for include in ("#include <ctype.h>\n", "#include <fnmatch.h>\n", "#include <string.h>\n"):
        source = source.replace(include, "")
    boundary = "  for (;;) {\n    int pc ="
    if source.count(boundary) != 1 or source.count('extern "C" int fnmatch(') != 1:
        raise ValueError("fnmatch source instrumentation boundary changed")
    source = source.replace(boundary, "  for (;;) {\n    ++work;\n    int pc =")
    source = source.replace('extern "C" int fnmatch(', "int Fnmatch(")
    return f"namespace {namespace} {{\nsize_t work = 0;\n{source}\n}}\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cxx", default=os.environ.get("CXX", "c++"))
    parser.add_argument("--source-ref")
    parser.add_argument("--compare-ref")
    parser.add_argument("--measure", action="store_true", help="report prior-source depth/work failures without gating them")
    parser.add_argument("--sanitize", action="store_true")
    args = parser.parse_args()
    template = (ROOT / "tests/native/fnmatch_performance_host.cc.in").read_text(encoding="utf-8")
    source = template.replace("@HEADER@", str(ROOT / "compat/posix/include/fnmatch.h").replace("\\", "/"))
    source = source.replace("@PRODUCTION@", implementation(read(args.source_ref), "production"))
    source = source.replace("@REFERENCE@", implementation(read(args.compare_ref), "reference") if args.compare_ref else "")
    source = source.replace("@HAS_REFERENCE@", "1" if args.compare_ref else "0")
    with tempfile.TemporaryDirectory(prefix="gesher-fnmatch-") as temporary:
        folder = Path(temporary)
        cpp = folder / "test.cc"
        executable = folder / ("test.exe" if os.name == "nt" else "test")
        cpp.write_text(source, encoding="utf-8")
        command = shlex.split(args.cxx) + ["-std=c++20", "-O2", "-g", "-Wall", "-Wextra", "-Werror",
                                         "-fno-exceptions", str(cpp), "-o", str(executable)]
        if os.name == "nt":
            command += ["-static"]
        if args.sanitize:
            command += ["-fsanitize=address,undefined", "-fno-sanitize-recover=all", "-fno-omit-frame-pointer"]
        subprocess.run(command, check=True, timeout=120)
        subprocess.run([str(executable), "measure" if args.measure else "gate"], check=True, timeout=60)


if __name__ == "__main__":
    main()
