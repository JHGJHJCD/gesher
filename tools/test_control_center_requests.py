#!/usr/bin/env python3
"""Run production ControlCenter request I/O with host-only launcher boundaries.

The shared concurrent-write fixture also runs on ART/Windows. This host check does
not open Windows UI and makes no claim about ShellExecute or shortcut creation.
"""
from __future__ import annotations
import argparse
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', required=True, type=Path)
    parser.add_argument('--source-ref', help='Run the identical checks with an older ControlCenter')
    parser.add_argument('--requests-only', action='store_true', help='Run only concurrent publication')
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='gesher-panel-request-host-') as temporary:
        work = Path(temporary)
        source = ROOT / 'app/java/com/gesher/server/ControlCenter.java'
        if args.source_ref:
            text = subprocess.run(['git', 'show', args.source_ref + ':app/java/com/gesher/server/ControlCenter.java'],
                                  cwd=ROOT, check=True, capture_output=True, text=True).stdout
            source = work / 'ControlCenter.java'
            source.write_text(text, encoding='utf-8')
        shell = work / 'Shell.java'
        shell.write_text('''package com.gesher.app.shell;
public final class Shell {
    public static boolean exists(String path) { return true; }
    public static int createShortcut(String link, String launcher, String arguments,
            String directory, String icon, String description, String appId) { return 0; }
}
''', encoding='utf-8')
        classes = work / 'classes'
        classes.mkdir()
        subprocess.run([str(args.java_home / 'bin/javac'), '-encoding', 'UTF-8', '-d', str(classes),
                        str(source), str(shell), str(ROOT / 'tests/control-center/ControlCenterHost.java'),
                        str(ROOT / 'tests/dex-services/gesher/services/ControlCenterRequestsTest.java')],
                       check=True, timeout=60)
        subprocess.run([str(args.java_home / 'bin/java'), '-cp', str(classes),
                        'gesher.services.ControlCenterRequestsTest' if args.requests_only
                        else 'com.gesher.server.ControlCenterHost'], check=True, timeout=90)


if __name__ == '__main__':
    main()
