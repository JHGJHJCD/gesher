#!/usr/bin/env python3
"""Exercise actual locale headers against deterministic Win32 boundary replies."""
import argparse
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cc", default="cc")
    parser.add_argument("--sanitize", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    tests = root / "tests/i18n"
    with tempfile.TemporaryDirectory(prefix="gesher-preferred-languages-") as directory:
        executable = Path(directory) / "preferred-languages"
        flags = ["-std=c11", "-D_GNU_SOURCE", "-O1", "-g", "-Wall", "-Wextra", "-Werror"]
        if args.sanitize:
            flags += ["-fsanitize=address,undefined", "-fno-sanitize-recover=all",
                      "-fno-omit-frame-pointer"]
        subprocess.run([args.cc, *flags, "-I", str(tests / "win32_mock"),
                        str(tests / "preferred_languages_test.c"), "-o", str(executable)],
                       check=True)
        subprocess.run([str(executable)], check=True)


if __name__ == "__main__":
    main()
