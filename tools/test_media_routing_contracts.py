#!/usr/bin/env python3
"""Run production media-routing Java with controlled audio, identity and Binder boundaries.

This uses real framework route/session values, not replacement Android values. Actual
public manager construction and remote Binder/host audio run in Windows fixtures.
"""
from __future__ import annotations
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--framework-classes", required=True, type=Path)
    parser.add_argument("--core-classes", required=True, type=Path)
    args = parser.parse_args()
    classpath = os.pathsep.join(str(value.resolve()) for value in
                               [args.framework_classes, args.core_classes])
    with tempfile.TemporaryDirectory(prefix="gesher-media-routing-") as temporary:
        work = Path(temporary)
        subprocess.run(["javac", "-encoding", "UTF-8", "-cp", classpath,
                        "-d", str(work),
                        str(ROOT / "app/java/com/gesher/server/MediaRouterService.java"),
                        str(ROOT / "tests/media-routing/MediaRoutingHost.java")], check=True)
        subprocess.run(["java", "-cp", str(work) + os.pathsep + classpath,
                        "com.gesher.server.MediaRoutingHost"], check=True, timeout=30)


if __name__ == "__main__":
    main()
