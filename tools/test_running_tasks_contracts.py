#!/usr/bin/env python3
"""Exercise the exact production task aggregation method with controlled host value types.

The separate RunningTaskSnapshotTest executes real framework values and generated Binder
transport on Windows. This host probe isolates the deterministic selection algorithm.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-ref", help="Compile this git revision as a negative control")
    args = parser.parse_args()
    relative = "app/java/com/gesher/server/ActivityTaskManagerService.java"
    source = (subprocess.check_output(["git", "show", args.source_ref + ":" + relative],
                                     cwd=ROOT, text=True) if args.source_ref
              else (ROOT / relative).read_text())
    start = source.index("public List<ActivityManager.RunningTaskInfo> getTasks(")
    brace = source.index("{", start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    method = source[start:end]
    harness = (ROOT / "tests/services/RunningTasksHost.java.in").read_text()
    with tempfile.TemporaryDirectory(prefix="gesher-running-tasks-") as temporary:
        work = Path(temporary)
        java = work / "RunningTasksHost.java"
        java.write_text(harness.replace("// PRODUCTION_METHOD", method))
        subprocess.run(["javac", "-d", str(work), str(java)], check=True)
        subprocess.run(["java", "-cp", str(work), "RunningTasksHost"], check=True, timeout=30)


if __name__ == "__main__":
    main()
