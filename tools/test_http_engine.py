#!/usr/bin/env python3
# Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0
"""Exercise production android.net.http over real HTTP sockets on a host JVM.

Only Context and Network are host fixtures. All engine/request/upload/callback
code is compiled unchanged from app/java/android/net/http.
"""
import argparse
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--java-home', type=Path, required=True)
    args = p.parse_args()
    with tempfile.TemporaryDirectory(prefix='gesher-httpengine-') as temporary:
        work = Path(temporary)
        context = work / 'android/content/Context.java'
        context.parent.mkdir(parents=True)
        context.write_text('package android.content; public class Context {}')
        network = work / 'android/net/Network.java'
        network.parent.mkdir(parents=True, exist_ok=True)
        network.write_text('package android.net; public class Network { public java.net.URLConnection '
                           'openConnection(java.net.URL u) throws java.io.IOException { return u.openConnection(); }'
                           'public javax.net.SocketFactory getSocketFactory() { return javax.net.SocketFactory.getDefault(); }'
                           'public java.net.InetAddress getByName(String host) throws java.net.UnknownHostException {'
                           'return java.net.InetAddress.getByName(host); }}')
        sources = list((ROOT / 'app/java/android/net/http').glob('*.java'))
        subprocess.run([str(args.java_home / 'bin/javac'), '--release', '17', '-encoding', 'UTF-8',
                        '-d', str(work), str(context), str(network), *map(str, sources),
                        str(ROOT / 'tests/net/HttpEngineHostTest.java')], check=True, timeout=60)
        subprocess.run([str(args.java_home / 'bin/java'), '-cp', str(work), 'HttpEngineHostTest'],
                       check=True, timeout=90)
        # URL permits installing the global factory only once in a VM. Test the
        # actual process-global installation separately from ordinary requests.
        subprocess.run([str(args.java_home / 'bin/java'), '-cp', str(work), 'HttpEngineHostTest',
                        'global-factory'], check=True, timeout=30)


if __name__ == '__main__':
    main()
