#!/usr/bin/env python3
"""Host-portable tests of production HWUI auto GPU selector and adapter policy.

These tests do NOT benchmark physical GPU devices. Windows graphics CI plus
a real Intel integrated GPU are required to validate runtime performance.
"""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]

def main():
    compiler = shlex.split(os.environ.get("CXX", "c++"))
    with tempfile.TemporaryDirectory(prefix="gesher-hwui-") as tmp:
        for name, sources in (
            ("policy", ["tests/native/hwui_renderer_policy_test.cc"]),
            ("selector", ["compat/hwui/gl/pipeline_select.cc",
                          "tests/hwui-renderer/selector_test.cc"]),
        ):
            exe = str(Path(tmp) / (name + (".exe" if os.name == "nt" else "")))
            cmd = compiler + ["-std=c++20", "-Wall", "-Wextra", "-Werror",
                              "-I", str(ROOT / "tests/hwui-renderer/stubs"),
                              "-I", str(ROOT),
                              *(str(ROOT / src) for src in sources), "-o", exe]
            subprocess.run(cmd, cwd=ROOT, check=True)
            subprocess.run([exe], cwd=ROOT, check=True)
    print("hwui auto GPU selector tests all passed")

if __name__ == "__main__":
    main()
