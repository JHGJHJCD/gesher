"""Real source-jar byte equivalence and bounded streaming I/O."""
import importlib.util
import io
import os
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile

SCRIPT = Path(__file__).resolve().parents[1] / 'framework' / 'soong_zip.py'
SPEC = importlib.util.spec_from_file_location('gesher_soong_zip', SCRIPT)
soong_zip = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(soong_zip)


class SourceJarTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.source = self.base / 'sources'
        self.source.mkdir()

    def run_zip(self, *entries):
        output = self.base / 'result.srcjar'
        with patch.object(sys, 'argv', [str(SCRIPT), '-srcjar', '-write_if_changed',
                                       '-o', str(output), *map(str, entries)]):
            soong_zip.main()
        return output

    def reference(self, entries):
        # Pre-change production writer: identical timestamps, names and compression.
        output = io.BytesIO()
        with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
            for name, file in sorted(entries):
                info = zipfile.ZipInfo(name.replace(os.sep, '/'), (2008, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, file.read_bytes())
        return output.getvalue()

    def test_archive_bytes_and_metadata_match_original(self):
        entries = []
        for name, size in [('empty', 0), ('short.java', 31), ('nested/a.java', 65535),
                           ('nested/b.java', 65536), ('nested/c.java', 65537),
                           ('space dir/שלום.java', 200003)]:
            file = self.source / name
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_bytes((bytes(range(251)) * ((size + 250) // 251))[:size])
            entries.append((name, file))
        output = self.run_zip('-C', self.source, '-D', self.source)
        self.assertEqual(self.reference(entries), output.read_bytes())
        with zipfile.ZipFile(output) as archive:
            self.assertIsNone(archive.testzip())
            for info in archive.infolist():
                self.assertEqual((2008, 1, 1, 0, 0, 0), info.date_time)
                self.assertEqual(zipfile.ZIP_DEFLATED, info.compress_type)

    def test_explicit_files_basename_and_duplicates(self):
        file = self.source / 'one.java'
        file.write_bytes(b'class One {}')
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            output = self.run_zip('-f', file, '-f', file)
            expected = self.reference([('one.java', file), ('one.java', file)])
        self.assertEqual(expected, output.read_bytes())

    def test_unchanged_archive_keeps_timestamp_changed_archive_is_published(self):
        file = self.source / 'one.java'
        file.write_bytes(b'class One {}')
        output = self.run_zip('-f', file)
        timestamp = 1200000000000000000
        os.utime(output, ns=(timestamp, timestamp))
        before = output.stat().st_mtime_ns
        first = output.read_bytes()
        self.run_zip('-f', file)
        self.assertEqual(before, output.stat().st_mtime_ns)
        self.assertEqual(first, output.read_bytes())
        file.write_bytes(b'class Two {}')
        self.run_zip('-f', file)
        self.assertNotEqual(before, output.stat().st_mtime_ns)
        with zipfile.ZipFile(output) as archive:
            self.assertEqual(b'class Two {}', archive.read('one.java'))
        self.assertEqual([], list(self.base.glob('.soong_zip-*')))

    def test_failed_generation_preserves_previous_archive(self):
        file = self.source / 'one.java'
        file.write_bytes(b'class One {}')
        output = self.run_zip('-f', file)
        before = output.read_bytes()
        timestamp = output.stat().st_mtime_ns
        with self.assertRaises(FileNotFoundError):
            self.run_zip('-f', self.source / 'missing.java')
        self.assertEqual(before, output.read_bytes())
        self.assertEqual(timestamp, output.stat().st_mtime_ns)
        self.assertEqual([], list(self.base.glob('.soong_zip-*')))

    def test_without_change_flag_rewrites_output(self):
        file = self.source / 'one.java'
        file.write_bytes(b'class One {}')
        output = self.run_zip('-f', file)
        timestamp = 1200000000000000000
        os.utime(output, ns=(timestamp, timestamp))
        with patch.object(sys, 'argv', [str(SCRIPT), '-o', str(output), '-f', str(file)]):
            soong_zip.main()
        self.assertNotEqual(timestamp, output.stat().st_mtime_ns)

    def test_failed_publication_preserves_archive_and_cleans_mode_copied_temp(self):
        file = self.source / 'one.java'
        file.write_bytes(b'class One {}')
        output = self.run_zip('-f', file)
        before = output.read_bytes()
        timestamp = output.stat().st_mtime_ns
        mode = stat.S_IMODE(output.stat().st_mode)
        try:
            for output_mode in (mode, stat.S_IREAD):
                output.chmod(output_mode)
                file.write_bytes(b'class Changed {}')
                with patch.object(soong_zip.os, 'replace', side_effect=PermissionError('fixture publication denied')):
                    with self.assertRaisesRegex(PermissionError, 'fixture publication denied'):
                        self.run_zip('-f', file)
                self.assertEqual(before, output.read_bytes())
                self.assertEqual(timestamp, output.stat().st_mtime_ns)
                self.assertEqual([], list(self.base.glob('.soong_zip-*')))
        finally:
            output.chmod(mode)

    def test_known_entry_size_selects_zip64_before_streaming(self):
        # Exercise the real ZIP64 headers with a lowered library threshold,
        # rather than creating a multi-gigabyte test file.
        file = self.source / 'zip64.java'
        content = bytes(range(251)) * 1000
        file.write_bytes(content)
        with patch.object(zipfile, 'ZIP64_LIMIT', 64 * 1024):
            output = self.run_zip('-f', file)
            self.assertEqual(self.reference([('zip64.java', file)]), output.read_bytes())
        with zipfile.ZipFile(output) as archive:
            info = archive.getinfo('zip64.java')
            self.assertEqual(45, info.extract_version)
            self.assertEqual(len(content), info.file_size)
            self.assertEqual(content, archive.read('zip64.java'))

    def test_large_entry_never_uses_unbounded_read(self):
        file = self.source / 'large.java'
        with file.open('wb') as stream:
            for _ in range(256):
                stream.write(b'generated-source\n' * 4096)
        sizes = []
        real_open = open
        class CountingInput:
            def __init__(self, stream):
                self.stream = stream
            def fileno(self):
                return self.stream.fileno()
            def read(self, size=-1):
                sizes.append(size)
                if size < 0 or size > 64 * 1024:
                    raise AssertionError('Source jar used an unbounded input read')
                return self.stream.read(size)
            def __enter__(self):
                return self
            def __exit__(self, *args):
                self.stream.close()
        def counted_open(path, mode):
            return CountingInput(real_open(path, mode))
        with patch.object(soong_zip, 'open', counted_open, create=True):
            output = self.run_zip('-f', file)
        self.assertGreater(len(sizes), 256)
        with zipfile.ZipFile(output) as archive:
            self.assertEqual(file.stat().st_size, archive.getinfo('large.java').file_size)
            self.assertIsNone(archive.testzip())
            with file.open('rb') as original, archive.open('large.java') as packed:
                while chunk := original.read(64 * 1024):
                    self.assertEqual(chunk, packed.read(len(chunk)))
                self.assertEqual(b'', packed.read(1))
        first = output.read_bytes()
        self.assertEqual(first, self.run_zip('-f', file).read_bytes())


if __name__ == '__main__':
    unittest.main()
