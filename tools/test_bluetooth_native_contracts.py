#!/usr/bin/env python3
"""Exercise production WinGatt lifetime code with deterministic COM stand-ins.

The ABI declarations, async wait and entire WinGatt class are extracted unchanged.
Only Windows activation, time and COM results are supplied by the host fixture.
--source-ref runs the same close races against an earlier revision.
"""

import argparse
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def between(source, start, end):
    if source.count(start) != 1 or source.count(end) != 1:
        raise ValueError(f"Bluetooth source boundary changed: {start}")
    return source[source.index(start):source.index(end)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-ref")
    parser.add_argument("--sanitize", action="store_true")
    args = parser.parse_args()

    def read(path):
        if args.source_ref:
            return subprocess.check_output(
                ["git", "show", f"{args.source_ref}:{path}"], cwd=ROOT, text=True)
        return (ROOT / path).read_text()

    source = read("compat/devices/bluetooth_win.cc")
    api = read("compat/devices/bluetooth.h")
    winrt = read("compat/devices/winrt.h")
    fixture = (ROOT / "tests/bluetooth/native_contracts_host.cc.in").read_text()
    replacements = {
        "@WINRT_API@": between(winrt, "// IInspectable's methods", "// Joins this thread"),
        "@BLUETOOTH_API@": between(api, "// The local radio.", "}  // namespace gesher::devices::bluetooth"),
        "@ABI@": between(source, "// --- ABI declarations", "// --- helpers"),
        "@HELPERS@": between(source, "// A delegate's vtable", "// The UUIDs of the ServiceClassIDList"),
        "@GATT@": between(source, "class WinGatt final", "// --- RFCOMM over Winsock"),
    }
    security = "HRESULT SetRfcommSecurity(SOCKET socket, bool secure) {"
    replacements["@SECURITY@"] = (
        "#define HAVE_SECURITY_HELPER 1\n" +
        between(source, security, "// Waits until `s` is readable")
        if security in source else "#define HAVE_SECURITY_HELPER 0\n")
    for marker, content in replacements.items():
        if fixture.count(marker) != 1:
            raise ValueError(f"Bluetooth fixture boundary changed: {marker}")
        fixture = fixture.replace(marker, content)
    with tempfile.TemporaryDirectory(prefix="gesher-bluetooth-native-") as temp:
        src, exe = Path(temp) / "test.cc", Path(temp) / "test"
        src.write_text(fixture)
        command = shlex.split(os.environ.get("CXX", "c++")) + [
            "-std=c++20", "-O1", "-g", "-pthread", "-Wall", "-Wextra", "-Werror",
            "-Wno-unused-parameter", "-Wno-unused-function", "-Wno-non-virtual-dtor",
            "-I" + str(ROOT / "compat/devices"), str(src), "-o", str(exe)]
        if args.sanitize:
            command += ["-fsanitize=address,undefined", "-fno-sanitize-recover=all",
                        "-fno-omit-frame-pointer", "-fno-pie", "-no-pie"]
        subprocess.run(command, check=True, timeout=90)
        subprocess.run([str(exe)], check=True, timeout=15)


if __name__ == "__main__":
    main()
