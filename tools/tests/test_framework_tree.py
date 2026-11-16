"""Soong glob prefix indexing: full-scan equivalence and bounded match work."""
import importlib.util
from pathlib import Path
import random
import re
import sys
import unittest
from unittest.mock import patch

FRAMEWORK = Path(__file__).resolve().parents[1] / 'framework'
sys.path.insert(0, str(FRAMEWORK))
SPEC = importlib.util.spec_from_file_location('framework_tree_index', FRAMEWORK / 'tree.py')
tree = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(tree)
sys.path.pop(0)


def fixture(files, projects=('frameworks/base',)):
    result = tree.Tree.__new__(tree.Tree)
    result.projects = sorted(projects, key=lambda p: -len(p))
    result._files = {p: list(files[p]) for p in projects}
    return result


def full_scan(subject, base, pattern):
    full = tree.norm(f'{base}/{pattern}')
    project = subject.project_of(full)
    if project is None:
        return []
    regex = re.compile(tree.glob_regex(full))
    return sorted(f for f in subject.files(project) if regex.fullmatch(f))


class FrameworkTreeTests(unittest.TestCase):
    def test_globs_match_full_scan_and_preserve_original_file_order(self):
        names = ['a.java', 'a/z.java', 'a.txt', 'ab/x.java', '.hidden/a.java',
                 'space dir/x.java', 'unicode-\u05d0/a.java', 'a/deep/y.aidl',
                 'a/deep/a.java', 'a0/wrong.java', 'az/wrong.java', 'a/deep/file[1].java']
        subject = fixture({'frameworks/base': ['frameworks/base/' + f for f in names]})
        original = list(subject.files('frameworks/base'))
        patterns = ['**/*.java', '*.java', 'a/*.java', 'a/**/*.java', 'a/**',
                    'a/?.java', 'a/[az].java', 'a/deep/file[[]1].java',
                    'a*/*.java', 'a?/*.java', '[ab]/*.java', 'a[0b]/*.java',
                    'space dir/*', 'unicode-\u05d0/**', '.hidden/*', 'missing/**',
                    'a.java', 'a/../a.txt', '../base/a/*.java', 'a//./*.java']
        for pattern in patterns:
            with self.subTest(pattern=pattern):
                self.assertEqual(full_scan(subject, 'frameworks/base', pattern),
                                 subject.glob('frameworks/base', pattern))
        self.assertEqual(original, subject.files('frameworks/base'))
        self.assertEqual([f for f in original if f.startswith('frameworks/base/a/')],
                         subject.dir_files('frameworks/base/a'))
        self.assertEqual([], subject.glob('unknown/project', '**/*'))

    def test_random_paths_nested_projects_and_repeated_queries(self):
        rng = random.Random(651)
        files = {'frameworks/base': [], 'frameworks/base/nested': []}
        for project in files:
            for i in range(3000):
                files[project].append(f'{project}/d{rng.randrange(20)}/s{rng.randrange(5)}/f{i}.{rng.choice(["java", "aidl", "txt"])}')
            rng.shuffle(files[project])
        subject = fixture(files, tuple(files))
        for project in files:
            for pattern in ['**/*', '**/*.java', 'd*/s?/*.aidl', 'd[12]/**', 'd1/s2/*', 'd19/**/*', 'd20/*']:
                expected = full_scan(subject, project, pattern)
                self.assertEqual(expected, subject.glob(project, pattern))
                index = subject._files[('sorted', project)]
                self.assertEqual(expected, subject.glob(project, pattern))
                self.assertIs(index, subject._files[('sorted', project)])

    def test_only_literal_subtree_is_matched(self):
        project = 'frameworks/base'
        subject = fixture({project: [f'{project}/d{directory}/f{i}.java'
                                    for directory in range(60) for i in range(1000)]})
        patterns = ['d1/*.java', 'd2/**/*.java', 'd3/f?.java']
        expected = [full_scan(subject, project, p) for p in patterns]
        compile_regex = re.compile
        matches = 0

        class CountedRegex:
            def __init__(self, pattern):
                self.regex = compile_regex(pattern)

            def fullmatch(self, value):
                nonlocal matches
                matches += 1
                return self.regex.fullmatch(value)

        with patch.object(tree.re, 'compile', side_effect=CountedRegex):
            actual = [subject.glob(project, p) for p in patterns]
        self.assertEqual(expected, actual)
        self.assertEqual(3000, matches)
        print(f'Framework glob regex checks: full_scan=180000 indexed={matches}; '
              'one retained sorted reference list per queried project')


if __name__ == '__main__':
    unittest.main()
