#!/usr/bin/env python3
"""Exercise real surface/window presentation methods with a Win32 back-buffer adapter.

Checks crop, clipping, zero padding and source ownership against a pixel oracle.
The allocation gate covers presentation after a preallocated back buffer; no
GDI, SkiaGL, GPU readback or physical-display performance is inferred.
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


def section(source: str, start: str, end: str) -> str:
    if source.count(start) != 1 or source.count(end) != 1:
        raise ValueError("surface presentation extraction boundary changed")
    begin = source.index(start)
    return source[begin:source.index(end, begin)]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-ref")
    parser.add_argument("--measure-only", action="store_true")
    parser.add_argument("--sanitize", action="store_true")
    parser.add_argument("--cxx", default=os.environ.get("CXX", "c++"))
    args = parser.parse_args()

    def read(path: str) -> str:
        return ((ROOT / path).read_text() if not args.source_ref else
                subprocess.run(["git", "show", args.source_ref + ":" + path], cwd=ROOT,
                               capture_output=True, text=True, check=True).stdout)

    surface = section(read("compat/hwui/window/win32_surface.cc"),
                      "void Win32Surface::PresentPixels(", "int Win32Surface::dequeueBuffer(")
    window = section(read("compat/hwui/window/gesher_popup.cc"),
                     "void Window::PresentPixels(", "void Window::PresentLayered(")
    # Derive the boundary declaration from production; the older implementation
    # has no stride argument, so it also compiles for the measured negative control.
    declaration = window[:window.index("{")].replace("Window::", "").strip()
    declaration = declaration.replace("int sourceStride", "int sourceStride = 0") + ";"
    fixture = (ROOT / "tests/media/surface_present_allocations_host.cc.in").read_text()
    fixture = fixture.replace("@WINDOW_DECLARATION@", declaration)
    fixture = fixture.replace("@PRODUCTION@", "namespace gesher {\n" + window + surface + "\n}")
    print("surface presentation source SHA256 " + hashlib.sha256((window + surface).encode()).hexdigest(),
          flush=True)
    with tempfile.TemporaryDirectory(prefix="gesher-surface-present-") as temporary:
        work = Path(temporary)
        cpp = work / "present.cc"
        cpp.write_text(fixture)
        binary = work / ("present.exe" if os.name == "nt" else "present")
        command = shlex.split(args.cxx) + ["-std=c++20", "-O2", "-g", "-Wall", "-Wextra",
                                         "-Werror", "-fno-exceptions", "-pthread", str(cpp), "-o", str(binary)]
        if args.sanitize:
            command += ["-fsanitize=address,undefined", "-fno-sanitize-recover=all",
                        "-fno-omit-frame-pointer"]
        runtime_env = os.environ.copy()
        compiler = Path(command[0])
        if os.name == "nt" and compiler.is_absolute():
            runtime_env["PATH"] = str(compiler.parent) + os.pathsep + runtime_env.get("PATH", "")
        subprocess.run(command, check=True, timeout=120)
        result = subprocess.run([str(binary), "measure" if args.measure_only else "gate"],
                                check=False, timeout=120, env=runtime_env)
        if result.returncode:
            print(f"Surface presentation fixture failed with exit code {result.returncode}", flush=True)
        return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
