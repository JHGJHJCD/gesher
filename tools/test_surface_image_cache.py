#!/usr/bin/env python3
"""Run actual surface image-cache and TextureLayer lifecycle methods on the host.

Skia pixel ownership, QueueSurface publication and JNI field access are declared
adapters. The production cache, layer mutex and attach/detach functions are intact;
the mandatory Windows surface-app regression checks the real framework/Skia path.
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


def section(text: str, start: str, end: str) -> str:
    if text.count(start) != 1 or text.count(end) != 1:
        raise ValueError("surface production extraction boundary changed")
    begin = text.index(start)
    return text[begin:text.index(end, begin)]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-ref")
    parser.add_argument("--sanitize", action="store_true")
    parser.add_argument("--cxx", default=os.environ.get("CXX", "c++"))
    args = parser.parse_args()
    path = "compat/media/surface/surface_jni.cc"
    if args.source_ref:
        production = subprocess.run(["git", "show", args.source_ref + ":" + path], cwd=ROOT,
                                    capture_output=True, text=True, check=True).stdout
    else:
        production = (ROOT / path).read_text()
    fixture = (ROOT / "tests/media/surface_image_cache_host.cc.in").read_text()
    regions = {
        "@CACHE@": ("class FrameImages {", "// --- SurfaceTexture"),
        "@LAYER@": ("class TextureLayer :", "class TextureLayerDrawable"),
        "@DETACH@": ("void JNICALL HR_detachSurfaceTexture(", "jboolean JNICALL TL_prepare("),
        "@ATTACH@": ("void JNICALL TL_setSurfaceTexture(", "void JNICALL TL_updateSurfaceTexture("),
    }
    for marker, (start, end) in regions.items():
        fixture = fixture.replace(marker, section(production, start, end))
    print("surface image-cache source SHA256 " + hashlib.sha256(production.encode()).hexdigest(),
          flush=True)
    with tempfile.TemporaryDirectory(prefix="gesher-surface-cache-") as temporary:
        work = Path(temporary)
        cpp = work / "surface_cache.cc"
        cpp.write_text(fixture)
        binary = work / "surface_cache"
        command = shlex.split(args.cxx) + [
            "-std=c++20", "-O1", "-g", "-Wall", "-Wextra", "-Werror", "-fno-exceptions",
            "-pthread", str(cpp), "-o", str(binary)]
        if args.sanitize:
            command += ["-fsanitize=address,undefined", "-fno-sanitize-recover=all",
                        "-fno-omit-frame-pointer"]
        subprocess.run(command, check=True, timeout=120)
        return subprocess.run([str(binary)], check=False, timeout=30).returncode


if __name__ == "__main__":
    raise SystemExit(main())
