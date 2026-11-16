#!/usr/bin/env python3
"""Exercise production Binder reply construction and framing without the broker.

The byte-stream adapter checks partial writes and injected failures; Windows
also runs real pipe round trips. Measurements count allocations inside the
production send, after constructing the reply and preallocating the observer.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def section(text: str, start: str, end: str) -> str:
    if text.count(start) != 1 or text.count(end) != 1:
        raise ValueError("Binder framing source boundary changed")
    return text[text.index(start):text.index(end)]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cxx", default=os.environ.get("CXX", "c++"))
    parser.add_argument("--source-ref")
    parser.add_argument("--measure", action="store_true")
    parser.add_argument("--sanitize", action="store_true")
    parser.add_argument("--compile-only", action="store_true")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.compile_only and args.output is None:
        parser.error("--compile-only requires --output")
    path = "compat/binder/binderd.cc"
    source = (subprocess.check_output(["git", "show", f"{args.source_ref}:{path}"], cwd=ROOT, text=True)
              if args.source_ref else (ROOT / path).read_text(encoding="utf-8"))
    output = section(source, "struct Out {", "// binder_thread_write.")
    if "struct WireReply : Out" not in output:
        output += "using WireReply = Out;\n"
    write = section(source, "bool WriteExact(HANDLE pipe, const void* buf, size_t n) {", "constexpr uint32_t kMaxMessage")
    send = section(source, "bool WriteMessage(HANDLE pipe,", "uint64_t RandomKey() {")
    template = (ROOT / "tests/native/binder_reply_allocations_host.cc.in").read_text(encoding="utf-8")
    template = template.replace("@OUTPUT@", output).replace("@WRITE@", write).replace("@SEND@", send)
    with tempfile.TemporaryDirectory(prefix="gesher-binder-reply-") as temporary:
        folder = Path(temporary)
        cpp = folder / "test.cc"
        executable = args.output.resolve() if args.output else folder / ("test.exe" if os.name == "nt" else "test")
        executable.parent.mkdir(parents=True, exist_ok=True)
        cpp.write_text(template, encoding="utf-8")
        command = shlex.split(args.cxx) + ["-std=c++20", "-O2", "-g", "-Wall", "-Wextra", "-Werror",
                                         "-fno-exceptions", "-pthread", str(cpp), "-o", str(executable)]
        if os.name == "nt" or args.compile_only:
            command += ["-static"]
        if args.sanitize:
            command += ["-fsanitize=address,undefined", "-fno-sanitize-recover=all", "-fno-omit-frame-pointer"]
        subprocess.run(command, check=True, timeout=120)
        if args.compile_only:
            print(f"Compiled Binder framing fixture: {executable}", flush=True)
        else:
            subprocess.run([str(executable), "measure" if args.measure else "gate"], check=True, timeout=60)


if __name__ == "__main__":
    main()
