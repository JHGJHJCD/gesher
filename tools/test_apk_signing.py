#!/usr/bin/env python3
"""Exercise the production APK verifier against real, locally signed APK fixtures."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apksig-jar", required=True, type=Path)
    args = parser.parse_args()
    apksig = args.apksig_jar.resolve()
    with tempfile.TemporaryDirectory(prefix="gesher-apk-signing-") as directory:
        work = Path(directory)
        subprocess.run([
            "keytool", "-genkeypair", "-keystore", str(work / "signer.p12"),
            "-storetype", "PKCS12", "-storepass", "fixture-only", "-alias", "fixture",
            "-keyalg", "RSA", "-keysize", "2048", "-validity", "30",
            "-dname", "CN=Gesher disposable signing test", "-noprompt",
        ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        subprocess.run([
            "javac", "--release", "17", "-cp", str(apksig), "-d", str(work),
            str(ROOT / "app/java/com/gesher/server/ApkSigningVerifier.java"),
            str(ROOT / "tests/package/ApkSigningVerifierTest.java"),
        ], check=True)
        subprocess.run([
            "java", "-cp", str(work) + os.pathsep + str(apksig),
            "com.gesher.server.ApkSigningVerifierTest",
            str(ROOT / "tests/dex-services/fixtures"), str(work),
        ], check=True, timeout=60)


if __name__ == "__main__":
    main()
