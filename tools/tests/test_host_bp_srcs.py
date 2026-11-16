"""Source-list equivalence and directory I/O bounds for the host build."""
import importlib.util
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / 'host' / 'bp_srcs.py'
SPEC = importlib.util.spec_from_file_location('host_bp_srcs', SCRIPT)
bp_srcs = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(bp_srcs)


class HostSourcesTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        for name in ('root.proto', 'src/a.proto', 'src/b.proto', 'src/a.cc',
                     'src/deep/c.proto', 'other/d.proto', '.hidden/e.proto',
                     'space dir/f.proto'):
            file = self.base / name
            file.parent.mkdir(parents=True, exist_ok=True)
            file.touch()

    def reference(self, modules, name):
        # The pre-change implementation, including non-existent literal files.
        result = []
        for pattern in modules[name]['srcs']:
            if pattern.startswith(':'):
                result += self.reference(modules, pattern[1:])
            elif any(c in pattern for c in '*?['):
                regex = re.compile(bp_srcs.glob_regex(pattern))
                for dp, _, files in os.walk(self.base):
                    for file in files:
                        rel = os.path.relpath(os.path.join(dp, file), self.base).replace(os.sep, '/')
                        if regex.fullmatch(rel):
                            result.append(os.path.join(self.base, rel))
            else:
                result.append(os.path.join(self.base, pattern))
        return sorted(set(result))

    def test_nested_filegroups_and_glob_semantics(self):
        patterns = ['*.proto', '**/*.proto', 'src/?.proto', 'src/[ab].proto',
                    'src/**/c.proto', '.hidden/*', 'missing/**/*.cc', 'space dir/*']
        for pattern in patterns:
            with self.subTest(pattern=pattern):
                modules = {'main': {'srcs': [pattern, ':shared', pattern, ':shared']},
                           'shared': {'srcs': [':leaf', 'missing-literal.proto']},
                           'leaf': {'srcs': ['src/*.cc', 'root.proto']}}
                self.assertEqual(self.reference(modules, 'main'),
                                 bp_srcs.module_sources(self.base, modules, 'main'))

    def test_one_walk_for_all_globs(self):
        modules = {'main': {'srcs': ['**/*.proto', 'src/*.proto', ':shared']},
                   'shared': {'srcs': ['other/*', '**/*.proto']}}
        expected = self.reference(modules, 'main')
        with patch.object(bp_srcs.os, 'walk', wraps=os.walk) as walk:
            actual = bp_srcs.module_sources(self.base, modules, 'main')
        self.assertEqual(expected, actual)
        self.assertEqual(1, walk.call_count)

    def test_literals_and_empty_modules_do_not_walk(self):
        for sources in ([], ['root.proto', 'missing.proto', 'root.proto']):
            with patch.object(bp_srcs.os, 'walk') as walk:
                actual = bp_srcs.module_sources(self.base, {'main': {'srcs': sources}}, 'main')
            self.assertEqual(sorted({os.path.join(self.base, p) for p in sources}), actual)
            walk.assert_not_called()

    def test_cli_parses_real_blueprint(self):
        (self.base / 'Android.bp').write_text('''
proto_sources = ["**/*.proto"]
filegroup { name: "group", srcs: proto_sources, }
cc_library_host_shared { name: "main", srcs: [":group", "src/*.cc", "absent.cc"], }
''', encoding='utf-8')
        result = subprocess.run([sys.executable, str(SCRIPT), str(self.base), '.', 'main'],
                                check=True, capture_output=True, text=True, timeout=30)
        base = os.path.join(str(self.base), '.')
        modules = {'main': {'srcs': ['**/*.proto', 'src/*.cc', 'absent.cc']}}
        self.assertEqual(';'.join(bp_srcs.module_sources(base, modules, 'main')), result.stdout)
        self.assertEqual('', result.stderr)


if __name__ == '__main__':
    unittest.main()
