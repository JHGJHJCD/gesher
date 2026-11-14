#!/usr/bin/env python3
"""Exercise guest pthread clocks/ownership with verbatim production code.

Windows address waits/wakes and thread IDs use Linux futex/gettid boundaries;
clock reads can be scripted for the attribute/explicit-clock and long-slice
cases. No production method or dispatch registration is replaced. This does
not establish Windows scheduler, process-shared or suspend/resume behavior.
The public fixture also runs through the real ELF exports in Windows CI.
"""
from __future__ import annotations
import argparse
import hashlib
import os
from pathlib import Path
import re
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
GROUPS = ("public", "clocks", "validation", "ownership", "slices", "nullable", "concurrency")


def section(source: str, first: str, last: str) -> str:
    if source.count(first) != 1 or source.count(last) != 1:
        raise ValueError("ambiguous production boundary: " + first)
    start = source.index(first)
    return source[start:source.index(last, start)]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-ref", help="Use original production code as a negative control")
    parser.add_argument("--group", choices=GROUPS, action="append")
    parser.add_argument("--sanitize", action="store_true")
    parser.add_argument("--linux-reference", action="store_true",
                        help="Run the independent native Linux intersection fixture")
    parser.add_argument("--cxx", default=os.environ.get("CXX", "c++"))
    args = parser.parse_args()
    if args.linux_reference:
        with tempfile.TemporaryDirectory(prefix="gesher-pthread-reference-") as temp:
            binary = Path(temp) / "reference"
            subprocess.run(["cc", "-std=c11", "-Wall", "-Wextra", "-Werror", "-pthread",
                            str(ROOT / "tests/native/pthread_wait_clock_linux_reference.c"),
                            "-o", str(binary)], check=True, timeout=60)
            return subprocess.run([str(binary)], timeout=10).returncode
    groups = args.group or list(GROUPS)
    # Null is a documented valid pthread deadline. The old implementation
    # dereferences it under mutex contention; it also waits forever when a
    # writer reacquires its own rwlock through an untimed entry point. The
    # negative control uses bounded error assertions for those contracts.
    if args.source_ref:
        for group in ("nullable", "concurrency"):
            if group in groups:
                groups.remove(group)
                print("Original-source control omits " + group +
                      "; nullable and untimed self-owner paths are verified "
                      "on the candidate.", flush=True)

    def read(path: str) -> str:
        if args.source_ref:
            return subprocess.check_output(["git", "show", args.source_ref + ":" + path],
                                           cwd=ROOT).decode()
        return (ROOT / path).read_text()

    pthread = read("compat/bionic/pthread_bionic.cc")
    extra = read("compat/bionic/extra_sys_bionic.cc")
    shim = read("compat/bionic/shim.cc")
    timed = ""
    if "// The timed forms the core lacks:" in extra:
        timed = section(extra, "// The timed forms the core lacks:", "// Spinlocks")
    parts = {
        "@FUTEX_HELPERS@": section(pthread, "using Word =", "// --- thread-exit work"),
        "@MUTEX_HELPERS@": section(pthread, "// --- mutex", "}  // namespace\n\n// --- the System V"),
        "@PTHREAD_API@": section(pthread, "// Mutex attributes:", "// Once."),
        "@EXTRA_WORD@": section(extra, "std::atomic<uint32_t>* Word(", "int64_t NowRealtimeNs("),
        "@SEMAPHORES@": section(extra, "int64_t MsUntil(", "// --- scheduling"),
        "@LEGACY_TIMED@": timed,
        "@DEADLINE@": section(shim, "uint32_t MillisUntil(", "void BIONIC_SYSV GcovStub("),
        "@PTHREAD_REGISTRATIONS@": "\n".join(re.findall(
            r"^  P\(pthread_(?:mutex|cond|rwlock)[A-Za-z_]*\);$", pthread, re.M)),
        "@EXTRA_REGISTRATIONS@": "\n".join(re.findall(
            r'^  E\("(?:sem_[A-Za-z_]+|pthread_(?:rwlock_(?:timed|clock)[A-Za-z_]+|'
            r'mutex_(?:timedlock_monotonic_np|clocklock)|cond_clockwait))", [A-Za-z_]+\);$',
            extra, re.M)),
    }
    template = (ROOT / "tests/native/pthread_clock_contract_host_test.cc.in").read_text()
    for marker, content in parts.items():
        if template.count(marker) != 1:
            raise ValueError("missing or ambiguous template marker: " + marker)
        template = template.replace(marker, content)
    for name, source in (("pthread", pthread), ("extra_sys", extra), ("deadline", shim)):
        print(name + " source SHA256 " + hashlib.sha256(source.encode()).hexdigest(), flush=True)
    with tempfile.TemporaryDirectory(prefix="gesher-pthread-clocks-") as temp:
        work = Path(temp)
        source = work / "contracts.cc"
        binary = work / "contracts"
        source.write_text(template)
        command = shlex.split(args.cxx) + [
            "-std=c++20", "-O1", "-g", "-Wall", "-Wextra", "-Werror",
            "-Wno-unused-function", "-fno-builtin", "-pthread",
            "-I" + str(ROOT / "compat/bionic"),
            "-I" + str(ROOT / "compat/arm64/include"),
            "-I" + str(ROOT / "tests/native"), str(source), "-o", str(binary),
        ]
        if args.sanitize:
            command += ["-fsanitize=address,undefined", "-fno-sanitize-recover=all",
                        "-fno-omit-frame-pointer"]
        subprocess.run(command, check=True, timeout=120)
        failed = []
        for group in groups:
            try:
                result = subprocess.run([str(binary), group], text=True, capture_output=True,
                                        timeout=30)
            except subprocess.TimeoutExpired:
                failed.append(group)
                print("FAIL " + group + ": bounded fixture timed out", flush=True)
                continue
            print(result.stdout, end="", flush=True)
            if result.returncode != 0:
                failed.append(group)
                print(f"FAIL {group}: exit={result.returncode}\n{result.stderr[:12000]}", flush=True)
        if failed:
            print("Failing pthread contract groups: " + ", ".join(failed), flush=True)
            return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
