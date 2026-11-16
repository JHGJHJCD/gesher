#!/usr/bin/env python3
# Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0
"""Exercise the exact AndroidX WebView Boundary handler on the host JVM.

The enclosing Android-dependent SupportLib is an adapter. Production Boundary is
copied in full, and only Class.getMethod is intercepted to count real reflection
lookups. Actual reflection, proxies, invocation, reentry and concurrent callers
are exercised. Allocation counts cover dispatch only, not WebView2/AndroidX IPC.
--source-ref supplies an old-source control; --measure reports without cache gates.
"""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import subprocess
import tempfile

from test_install_metadata_comparison import method

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--source-ref')
    parser.add_argument('--measure', action='store_true')
    args = parser.parse_args()
    path = 'app/java/com/gesher/app/webview/SupportLib.java'
    source = subprocess.check_output(['git', 'show', args.source_ref + ':' + path],
                                     cwd=ROOT, text=True) if args.source_ref else (
                                         ROOT / path).read_text(encoding='utf-8')
    exact = method(source, 'static final class Boundary implements InvocationHandler')
    print('WebView Boundary production SHA256', hashlib.sha256(exact.encode()).hexdigest(), flush=True)
    needle = 'delegate.getClass().getMethod(method.getName(), method.getParameterTypes())'
    if exact.count(needle) != 1:
        raise ValueError('Expected one delegate reflection-resolution boundary')
    exact = exact.replace(needle, 'lookup(delegate, method)')
    fixture = (ROOT / 'tests/java-performance/WebViewBoundaryDispatchHost.java.in').read_text(encoding='utf-8')
    fixture = fixture.replace('@PRODUCTION@', exact).replace('@CACHE_GATE@', str(not args.measure).lower())
    fixture = fixture.replace('@INTERFACES@', '\n'.join(
        f'interface Alias{i} {{ void ping(); }}' for i in range(96)))
    fixture = fixture.replace('@ALIASES@', ', '.join(f'Alias{i}.class' for i in range(96)))
    with tempfile.TemporaryDirectory(prefix='gesher-webview-boundary-') as temporary:
        work = Path(temporary)
        target = work / 'WebViewBoundaryDispatchHost.java'
        target.write_text(fixture, encoding='utf-8')
        subprocess.run([str(args.java_home / 'bin/javac'), '-J-XX:-UsePerfData', '-encoding', 'UTF-8',
                        '-d', str(work), str(target)], check=True, timeout=60)
        subprocess.run([str(args.java_home / 'bin/java'), '-XX:-UsePerfData', '-Xmx128m',
                        '-cp', str(work), 'com.gesher.app.webview.WebViewBoundaryDispatchHost'],
                       check=True, timeout=60)


if __name__ == '__main__':
    main()
