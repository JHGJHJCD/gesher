#!/usr/bin/env python3
"""Run actual CaptureStream methods with allocation counts and PCM/sample contracts.

Only capture-engine attachment is adapted; resampling, ring buffering, callbacks,
state and PCM conversion are production source. This is not a WASAPI/device test.
"""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import re
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def section(source: str, start: str, end: str) -> str:
    if source.count(start) != 1 or source.count(end) != 1:
        raise ValueError("capture production extraction boundary changed")
    begin = source.index(start)
    return source[begin:source.index(end, begin)]


def generate(ref: str | None) -> str:
    def read(path: str) -> str:
        return ((ROOT / path).read_text() if not ref else
                subprocess.run(["git", "show", ref + ":" + path], cwd=ROOT,
                               capture_output=True, text=True, check=True).stdout)
    header = re.sub(r"^\s*#(?:include|pragma).*\n", "", read("compat/media/audio/capture.h"), flags=re.M)
    header = header.replace("private:", "public:")
    source = read("compat/media/audio/capture.cc")
    engine_header = read("compat/media/audio/engine.h")
    engine_source = read("compat/media/audio/engine.cc")
    parts = {
        "@HEADER@": header,
        "@TYPES@": section(engine_header, "enum class PcmFormat :", "// Android's stream types"),
        "@STATUSES@": "\n".join(re.findall(r"constexpr int (?:kOk|kInvalidOperation) = [^;]+;", engine_header)),
        "@BYTES@": section(engine_source, "size_t BytesPerSample(", "// --- Stream volume groups"),
        "@MEMBERS@": section(source, "CaptureStream::CaptureStream(", "std::shared_ptr<CaptureStream> OpenCapture("),
        "@PCM@": source[source.index("void FloatToPcm("):source.rindex("}  // namespace gesher::media")],
    }
    fixture = (ROOT / "tests/media/audio_capture_allocations_host.cc.in").read_text()
    for marker, body in parts.items():
        fixture = fixture.replace(marker, body)
    print("audio capture source SHA256 " + hashlib.sha256("".join(parts.values()).encode()).hexdigest(), flush=True)
    return fixture


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-ref")
    parser.add_argument("--compare-ref")
    parser.add_argument("--measure-only", action="store_true")
    parser.add_argument("--sanitize", action="store_true")
    parser.add_argument("--cxx", default=os.environ.get("CXX", "c++"))
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="gesher-audio-capture-") as temporary:
        work = Path(temporary)
        def run(label: str, ref: str | None, gate: bool) -> tuple[int, Path]:
            cpp = work / (label + ".cc")
            cpp.write_text(generate(ref))
            binary = work / (label + (".exe" if os.name == "nt" else ""))
            record = work / (label + ".pcm")
            command = shlex.split(args.cxx) + ["-std=c++20", "-O2", "-g", "-Wall", "-Wextra", "-Werror",
                                             "-fno-exceptions", "-pthread", str(cpp), "-o", str(binary)]
            if args.sanitize:
                command += ["-fsanitize=address,undefined", "-fno-sanitize-recover=all", "-fno-omit-frame-pointer"]
            runtime_env = os.environ.copy()
            compiler = Path(command[0])
            if os.name == "nt" and compiler.is_absolute():
                runtime_env["PATH"] = str(compiler.parent) + os.pathsep + runtime_env.get("PATH", "")
            subprocess.run(command, check=True, timeout=120)
            result = subprocess.run([str(binary), str(record), "gate" if gate else "measure"],
                                    check=False, timeout=120, env=runtime_env)
            if result.returncode:
                print(f"Audio capture fixture failed with exit code {result.returncode}", flush=True)
            return result.returncode, record
        result, candidate = run("candidate", args.source_ref, not args.measure_only)
        if args.compare_ref:
            reference_result, reference = run("reference", args.compare_ref, False)
            if reference_result:
                return reference_result
            if candidate.read_bytes() != reference.read_bytes():
                raise SystemExit("Capture sample/state/callback output differs from reference")
            print(f"Bit-exact capture sample/state/callback comparison: {candidate.stat().st_size} bytes PASS", flush=True)
        return result


if __name__ == "__main__":
    raise SystemExit(main())
