#!/usr/bin/env python3
"""Run production mouse translation/routing and Win32 message handling on host seams.

Win32 capture, coordinate conversion and Android event storage are declared
adapters. The Windows input_probe supplies the real window/message-loop gate.
--source-ref compiles the same behavior checks against the old production code.
"""
import argparse
import hashlib
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def block(source, signature):
    start = source.index(signature)
    end = source.index('{', start) + 1
    depth = 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-ref')
    parser.add_argument('--sanitize', action='store_true')
    parser.add_argument('--cxx', default=os.environ.get('CXX', 'c++'))
    args = parser.parse_args()

    def read(path):
        return subprocess.check_output(['git', 'show', args.source_ref + ':' + path],
                cwd=ROOT, text=True) if args.source_ref else (ROOT / path).read_text()

    translate = read('compat/input/translate.cc')
    window = read('compat/hwui/window/gesher_window.cc')
    header = read('compat/hwui/window/gesher_window.h')
    popup = read('compat/hwui/window/gesher_popup.cc')
    start = window.index('    case WM_LBUTTONDOWN:')
    producer = window[start:window.index('    case WM_MOUSEMOVE:', start)]
    cancel = ''
    if '    case WM_CAPTURECHANGED:' in window:
        start = window.index('    case WM_CAPTURECHANGED:')
        cancel = window[start:window.index('    case WM_LBUTTONDOWN:', start)]
    values = {
        '@EVENT@': block(header, 'struct WindowEvent') + ';',
        '@MOUSE_MOTION@': block(translate, 'AndroidEvent Translator::MouseMotion('),
        '@MOUSE@': block(translate, 'void Translator::Mouse('),
        '@TRANSLATE@': block(translate, 'void Translator::Translate('),
        '@IS_INPUT@': block(translate, 'bool Translator::IsInput('),
        '@IS_POINTER@': block(popup, 'bool IsPointer('),
        '@IS_KEY@': block(popup, 'bool IsKey('),
        '@ROUTE@': block(popup, 'bool Window::RouteEvent('),
        '@PRODUCER@': cancel + producer,
    }
    template = (ROOT / 'tests/input/mouse_capture_host.cc.in').read_text()
    for key, value in values.items():
        if template.count(key) != 1:
            raise ValueError(key)
        template = template.replace(key, value)
    print('mouse capture sources SHA256', hashlib.sha256(
        (translate + window + header + popup).encode()).hexdigest(), flush=True)
    with tempfile.TemporaryDirectory(prefix='gesher-mouse-capture-') as temp:
        source, executable = Path(temp) / 'probe.cc', Path(temp) / 'probe'
        source.write_text(template)
        command = shlex.split(args.cxx) + ['-std=c++20', '-O1', '-g', '-Wall',
            '-Wextra', '-Werror', '-pthread', str(source), '-o', str(executable)]
        if args.sanitize:
            command += ['-fsanitize=address,undefined', '-fno-sanitize-recover=all',
                        '-fno-omit-frame-pointer']
        subprocess.run(command, check=True, timeout=120)
        return subprocess.run([str(executable)], timeout=30).returncode


if __name__ == '__main__':
    raise SystemExit(main())
