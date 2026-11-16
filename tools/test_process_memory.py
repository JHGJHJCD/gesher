#!/usr/bin/env python3
"""Portable parser/aggregation and resource-lifetime tests for process_memory.py."""
import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("process_memory", Path(__file__).with_name("process_memory.py"))
memory = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(memory)


def header(begin=0x60000000, size=0x4000, checksum=123, version=b"118\0"):
    return b"art\n" + version + struct.pack("<6I", 0x10000, 1, begin, size, checksum, 456)


def region(allocation=0x10000, kind="anonymous", filename=None, size=4096, resident=4096, private=4096):
    return dict(base=allocation, allocation=allocation, type=kind, file=filename, size=size,
                resident_bytes=resident, private_resident_bytes=private)


class ImageTests(unittest.TestCase):
    def test_relocated_image_matches_checksums(self):
        original = memory.parse_art_header(header())
        images = {memory.image_identity(original): ["boot-framework.art"]}
        actual = memory.identify_image(header(begin=0x61000000), 0x61000000, images)
        self.assertEqual(actual["file"], "boot-framework.art")
        self.assertEqual(actual["header"]["image_size"], 0x4000)

    def test_equal_size_different_checksum_does_not_match_file(self):
        original = memory.parse_art_header(header())
        actual = memory.identify_image(header(checksum=124), 0x60000000,
                                       {memory.image_identity(original): ["wrong.art"]})
        self.assertIsNone(actual["file"])
        self.assertEqual(actual["matching_files"], [])

    def test_header_begin_must_equal_remote_address(self):
        self.assertIsNone(memory.identify_image(header(), 0x61000000, {}))

    def test_rejects_unsupported_short_and_impossible_headers(self):
        for data in (header(version=b"119\0"), header()[:31], b"xxx\n" + header()[4:],
                     header(size=1), header(begin=0), header(begin=0xfffff000, size=0x4000)):
            with self.subTest(data=data):
                self.assertIsNone(memory.parse_art_header(data))

    def test_duplicate_identity_remains_ambiguous(self):
        original = memory.parse_art_header(header())
        actual = memory.identify_image(header(), 0x60000000,
                                       {memory.image_identity(original): ["a.art", "b.art"]})
        self.assertIsNone(actual["file"])
        self.assertEqual(len(actual["matching_files"]), 2)

    def test_load_images_and_invalid_catalog(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            with self.assertRaisesRegex(ValueError, "No .art"):
                memory.load_images([directory])
            (directory / "boot.art").write_bytes(header())
            self.assertEqual(len(memory.load_images([directory])), 1)
            (directory / "bad.art").write_bytes(b"not an ART image")
            with self.assertRaisesRegex(ValueError, "Unsupported or invalid"):
                memory.load_images([directory])


class ImageRangeTests(unittest.TestCase):
    def discovery(self, begin=0x60000000, size=0x3001, filename="boot.art", allocation=None):
        data = header(begin=begin, size=size)
        parsed = memory.parse_art_header(data)
        image = memory.identify_image(data, begin, {memory.image_identity(parsed): [filename]})
        return dict(allocation=begin if allocation is None else allocation, image=image)

    def item(self, base, allocation=0x60000000, size=4096, kind="anonymous"):
        item = region(allocation=allocation, size=size, kind=kind)
        item["base"] = base
        return item

    def test_split_protections_and_final_partial_page_belong_to_image(self):
        items = [self.item(0x60000000), self.item(0x60001000, size=8192), self.item(0x60003000)]
        items[0]["protect"], items[1]["protect"], items[2]["protect"] = 4, 2, 4
        memory.associate_image_regions(items, [self.discovery()], 4096)
        self.assertTrue(all(item["art_image"]["file"] == "boot.art" for item in items))
        groups = memory.aggregate_regions(items)
        self.assertEqual(len(groups), 1)
        self.assertEqual(groups[0]["resident_bytes"], 3 * 4096)
        self.assertEqual(groups[0]["committed_region_bytes"], 4 * 4096)

    def test_excludes_other_allocation_and_trailing_metadata_and_partial_overlap(self):
        items = [self.item(0x60001000, allocation=0x60001000),
                 self.item(0x60004000), self.item(0x60003000, size=8192),
                 self.item(0x60001000, kind="mapped_section"), self.item(0x5ffff000)]
        memory.associate_image_regions(items, [self.discovery()], 4096)
        self.assertTrue(all("art_image" not in item for item in items))

    def test_overlapping_image_headers_do_not_choose_first_candidate(self):
        items = [self.item(0x60001000)]
        discoveries = [self.discovery(), self.discovery(begin=0x60001000, size=0x1000,
                        filename="other.art", allocation=0x60000000)]
        memory.associate_image_regions(items, discoveries, 4096)
        self.assertNotIn("art_image", items[0])

    def test_split_unmatched_image_has_one_unknown_group(self):
        discovery = self.discovery()
        discovery["image"]["file"] = None
        discovery["image"]["matching_files"] = ["a.art", "b.art"]
        items = [self.item(0x60000000), self.item(0x60001000)]
        memory.associate_image_regions(items, [discovery], 4096)
        groups = memory.aggregate_regions(items)
        self.assertEqual(len(groups), 1)
        self.assertIn("unmatched/ambiguous", groups[0]["name"])
        self.assertEqual(groups[0]["regions"], 2)


class AggregationTests(unittest.TestCase):
    def test_shared_file_pages_are_not_reported_as_private_commit(self):
        groups = memory.aggregate_regions([
            region(filename="boot.oat", kind="mapped_section", size=40960, private=0),
            region(allocation=0x20000, filename="boot.oat", kind="mapped_section", resident=0, private=0),
            region(allocation=0x30000, resident=4096, private=4096)])
        self.assertEqual(len(groups), 2)
        mapped = next(group for group in groups if group["name"] == "boot.oat")
        self.assertEqual(mapped["committed_region_bytes"], 45056)
        self.assertEqual(mapped["resident_bytes"], 4096)
        self.assertEqual(mapped["private_resident_bytes"], 0)
        self.assertNotIn("private_commit_bytes", mapped)
        self.assertNotIn("commit", mapped)

    def test_distinguishes_anonymous_allocations_from_pagefile_sections(self):
        groups = memory.aggregate_regions([region(), region(), region(kind="mapped_section")])
        self.assertEqual(len(groups), 2)
        self.assertEqual(groups[0]["regions"], 2)
        self.assertEqual(groups[0]["resident_bytes"], 8192)
        self.assertEqual(groups[1]["kind"], "mapped_section")

    def test_identified_image_group_keeps_evidence_in_region(self):
        item = region()
        item["art_image"] = memory.identify_image(header(), 0x60000000,
            {memory.image_identity(memory.parse_art_header(header())): ["boot.art"]})
        groups = memory.aggregate_regions([item])
        self.assertEqual(groups[0]["kind"], "art_image")
        self.assertEqual(groups[0]["name"], "boot.art")

    def test_closes_handle_when_capture_fails(self):
        with patch.object(memory, "WindowsProcess") as process_type:
            process = process_type.return_value
            process.regions.side_effect = OSError("VirtualQueryEx failed")
            with self.assertRaisesRegex(OSError, "VirtualQueryEx"):
                memory.capture(123, [])
            process.close.assert_called_once()

    def test_counters_bound_identification_as_well_as_sampling(self):
        with patch.object(memory, "WindowsProcess") as process_type:
            process = process_type.return_value
            process.page_size = 4096
            process.regions.return_value = []
            process.counters.side_effect = [{"phase": "before"}, {"phase": "sampled"}, {"phase": "identified"}]
            result = memory.capture(123, [])
            self.assertEqual(result["process_counters_before"], {"phase": "before"})
            self.assertEqual(result["process_counters_after_sampling"], {"phase": "sampled"})
            self.assertEqual(result["process_counters_after_identification"], {"phase": "identified"})
            self.assertEqual(process.counters.call_count, 3)
            process.close.assert_called_once()

    def test_parser_import_does_not_require_windows_dlls(self):
        self.assertEqual(memory.aggregate_regions([]), [])
        self.assertEqual(memory.parse_art_header(header())["version"], "118")


if __name__ == "__main__":
    unittest.main()
