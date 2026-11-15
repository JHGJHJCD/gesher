#!/usr/bin/env python3
"""Exercise production deferred-rewrite failure flow with controlled boundaries."""
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
    parser.add_argument("--source", type=Path, default=ROOT / "compat/elf/fs_rewrite.cc")
    parser.add_argument("--cxx", default=os.environ.get("CXX", "c++"))
    parser.add_argument("--sanitize", action="store_true")
    args = parser.parse_args()
    source = args.source.read_text()
    start, end = "bool RewriteOnExecution(", "\nbool HandleSyscallTrap(EXCEPTION_POINTERS* info) {"
    if source.count(start) != 1 or source.count(end) != 1:
        raise ValueError("ambiguous deferred rewrite section boundaries")
    section = source[source.index(start):source.index(end, source.index(start))]
    template = (ROOT / "tests/native/deferred_rewrite_failure_test.cc.in").read_text()
    if template.count("@PRODUCTION_DEFERRED_FUNCTIONS@") != 1:
        raise ValueError("ambiguous production template marker")
    finish_start, finish_end = "bool Rewriter::Finish(", "\n// --- deferred rewrite, on execution"
    if source.count(finish_start) != 1 or source.count(finish_end) != 1:
        raise ValueError("ambiguous trap registry section boundaries")
    finish = source[source.index(finish_start):source.index(finish_end, source.index(finish_start))]
    registry_template = (ROOT / "tests/native/rewrite_registry_test.cc.in").read_text()
    if registry_template.count("@PRODUCTION_FINISH@") != 1:
        raise ValueError("ambiguous registry template marker")
    fixtures = [("failures", section, template.replace("@PRODUCTION_DEFERRED_FUNCTIONS@", section)),
                ("registry", finish, registry_template.replace("@PRODUCTION_FINISH@", finish))]
    with tempfile.TemporaryDirectory(prefix="gesher-deferred-failures-") as folder:
        failed = False
        for name, production, generated in fixtures:
            print(name + " production SHA256 " + hashlib.sha256(production.encode()).hexdigest(), flush=True)
            source_path = Path(folder) / (name + ".cc")
            executable = Path(folder) / name
            source_path.write_text(generated)
            command = shlex.split(args.cxx) + ["-std=c++20", "-O1", "-g", "-Wall", "-Wextra", "-Werror",
                str(source_path), "-o", str(executable)]
            if args.sanitize:
                command += ["-fsanitize=address,undefined", "-fno-omit-frame-pointer"]
            subprocess.run(command, check=True, timeout=120)
            failed |= subprocess.run([str(executable)], cwd=folder, timeout=30).returncode != 0
        return int(failed)


if __name__ == "__main__":
    raise SystemExit(main())
