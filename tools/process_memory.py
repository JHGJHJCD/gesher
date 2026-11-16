#!/usr/bin/env python3
"""Read-only Windows process memory snapshot (64-bit Python).

python tools/process_memory.py PID --images runtime/framework/x86_64 --output snapshot.json

Byte counts distinguish private commit (GetProcessMemoryInfo) from resident pages
(QueryWorkingSetEx). Region sizes include file views and are not private commit.
ART 118 images are identified by their headers and on-disk checksums, never size
alone. Scanning headers follows page sampling and reads only resident first pages.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
import ctypes as c
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import struct
import sys

ART_HEADER_SIZE = 32


def parse_art_header(data: bytes) -> dict | None:
    # AOSP android-16.0.0_r4/runtime/oat/image.h, ImageHeader's first fields.
    if len(data) < ART_HEADER_SIZE or data[:8] != b"art\n118\0":
        return None
    reservation, components, begin, size, checksum, oat_checksum = struct.unpack_from("<6I", data, 8)
    if size < ART_HEADER_SIZE or begin == 0 or begin + size > 1 << 32:
        return None
    return dict(version="118", reservation=reservation, components=components,
                image_begin=begin, image_size=size, image_checksum=checksum,
                oat_checksum=oat_checksum)


def image_identity(header: dict) -> tuple:
    return tuple(header[key] for key in ("version", "reservation", "components",
                                         "image_size", "image_checksum", "oat_checksum"))


def load_images(directories: list[Path]) -> dict:
    images = defaultdict(list)
    for directory in directories:
        files = sorted(directory.glob("*.art"))
        if not files:
            raise ValueError(f"No .art images found in {directory}")
        for path in files:
            with path.open("rb") as stream:
                header = parse_art_header(stream.read(ART_HEADER_SIZE))
            if header is None:
                raise ValueError(f"Unsupported or invalid ART image header: {path}")
            images[image_identity(header)].append(str(path.resolve()))
    return images


def identify_image(data: bytes, address: int, images: dict) -> dict | None:
    header = parse_art_header(data)
    if header is None or header["image_begin"] != address:
        return None
    candidates = images.get(image_identity(header), [])
    return dict(header=header, matching_files=candidates,
                file=candidates[0] if len(candidates) == 1 else None)



def associate_image_regions(regions: list[dict], discoveries: list[dict], page_size: int):
    """Only classify complete anonymous regions covered by one verified image header."""
    for region in regions:
        if region["type"] != "anonymous":
            continue
        matches = []
        for discovery in discoveries:
            image = discovery["image"]
            header = image["header"]
            begin = header["image_begin"]
            end = (begin + header["image_size"] + page_size - 1) // page_size * page_size
            if (region["allocation"] == discovery["allocation"]
                    and begin <= region["base"] and region["base"] + region["size"] <= end):
                matches.append(image)
        if len(matches) == 1:
            region["art_image"] = matches[0]


def aggregate_regions(regions: list[dict]) -> list[dict]:
    groups = {}
    for region in regions:
        image = region.get("art_image")
        if region.get("file"):
            kind, name = "file", region["file"]
        elif image:
            kind = "art_image"
            name = image["file"] or f"ART image at {image['header']['image_begin']:#x} (unmatched/ambiguous)"
        else:
            kind = region["type"]
            name = f"{kind}@{region['allocation']:#x}"
        key = (kind, name)
        group = groups.setdefault(key, dict(kind=kind, name=name, committed_region_bytes=0,
                                            resident_bytes=0, private_resident_bytes=0, regions=0))
        group["committed_region_bytes"] += region["size"]
        group["resident_bytes"] += region["resident_bytes"]
        group["private_resident_bytes"] += region["private_resident_bytes"]
        group["regions"] += 1
    return sorted(groups.values(), key=lambda group: group["resident_bytes"], reverse=True)


class WindowsProcess:
    def __init__(self, pid: int):
        if os.name != "nt" or c.sizeof(c.c_void_p) != 8:
            raise RuntimeError("Process capture requires 64-bit Python on Windows")
        from ctypes import wintypes as w
        self.kernel = c.WinDLL("kernel32", use_last_error=True)
        self.psapi = c.WinDLL("psapi", use_last_error=True)

        class MemoryInfo(c.Structure):
            _fields_ = [("base", c.c_void_p), ("allocation", c.c_void_p),
                        ("allocation_protect", w.DWORD), ("partition", w.WORD),
                        ("size", c.c_size_t), ("state", w.DWORD),
                        ("protect", w.DWORD), ("type", w.DWORD)]

        class WorkingSet(c.Structure):
            _fields_ = [("address", c.c_void_p), ("flags", c.c_size_t)]

        class Counters(c.Structure):
            _fields_ = [("cb", w.DWORD), ("page_faults", w.DWORD)] + [
                (name, c.c_size_t) for name in ("peak_working_set", "working_set", "peak_paged_pool",
                "paged_pool", "peak_nonpaged_pool", "nonpaged_pool", "pagefile", "peak_pagefile",
                "private_usage")]

        class SystemInfo(c.Structure):
            _fields_ = [("architecture", w.WORD), ("reserved", w.WORD), ("page_size", w.DWORD),
                        ("minimum", c.c_void_p), ("maximum", c.c_void_p), ("mask", c.c_size_t),
                        ("processors", w.DWORD), ("processor_type", w.DWORD),
                        ("allocation_granularity", w.DWORD), ("level", w.WORD), ("revision", w.WORD)]

        self.MemoryInfo, self.WorkingSet, self.Counters = MemoryInfo, WorkingSet, Counters
        self.kernel.OpenProcess.argtypes = [w.DWORD, w.BOOL, w.DWORD]
        self.kernel.OpenProcess.restype = w.HANDLE
        self.kernel.CloseHandle.argtypes = [w.HANDLE]
        self.kernel.CloseHandle.restype = w.BOOL
        self.kernel.VirtualQueryEx.argtypes = [w.HANDLE, c.c_void_p, c.POINTER(MemoryInfo), c.c_size_t]
        self.kernel.VirtualQueryEx.restype = c.c_size_t
        self.kernel.ReadProcessMemory.argtypes = [w.HANDLE, c.c_void_p, c.c_void_p, c.c_size_t,
                                                  c.POINTER(c.c_size_t)]
        self.kernel.ReadProcessMemory.restype = w.BOOL
        self.kernel.GetSystemInfo.argtypes = [c.POINTER(SystemInfo)]
        self.kernel.GetSystemInfo.restype = None
        self.psapi.GetMappedFileNameW.argtypes = [w.HANDLE, c.c_void_p, w.LPWSTR, w.DWORD]
        self.psapi.GetMappedFileNameW.restype = w.DWORD
        self.psapi.QueryWorkingSetEx.argtypes = [w.HANDLE, c.c_void_p, w.DWORD]
        self.psapi.QueryWorkingSetEx.restype = w.BOOL
        self.psapi.GetProcessMemoryInfo.argtypes = [w.HANDLE, c.POINTER(Counters), w.DWORD]
        self.psapi.GetProcessMemoryInfo.restype = w.BOOL
        system = SystemInfo()
        self.kernel.GetSystemInfo(c.byref(system))
        self.page_size, self.maximum = system.page_size, system.maximum
        self.handle = self.kernel.OpenProcess(0x410, False, pid)
        if not self.handle:
            self.fail(f"OpenProcess({pid})")

    @staticmethod
    def fail(operation: str):
        error = c.get_last_error()
        raise OSError(error, f"{operation}: {c.FormatError(error).strip()}")

    def close(self):
        self.kernel.CloseHandle(self.handle)

    def counters(self) -> dict:
        info = self.Counters()
        info.cb = c.sizeof(info)
        if not self.psapi.GetProcessMemoryInfo(self.handle, c.byref(info), info.cb):
            self.fail("GetProcessMemoryInfo")
        return dict(working_set_bytes=info.working_set, private_commit_bytes=info.private_usage,
                    peak_working_set_bytes=info.peak_working_set, page_faults=info.page_faults)

    def regions(self) -> list[dict]:
        regions, address = [], 0
        while address < self.maximum:
            info = self.MemoryInfo()
            if not self.kernel.VirtualQueryEx(self.handle, address, c.byref(info), c.sizeof(info)):
                # Windows can reject the final user address range with ERROR_INVALID_PARAMETER.
                if c.get_last_error() == 87 and address >= self.maximum - 65535:
                    break
                self.fail(f"VirtualQueryEx({address:#x})")
            end = (info.base or 0) + info.size
            if end <= address:
                raise RuntimeError(f"VirtualQueryEx did not advance at {address:#x}")
            if info.state == 0x1000:
                filename = None
                if info.type in (0x40000, 0x1000000):
                    name = c.create_unicode_buffer(32768)
                    c.set_last_error(0)
                    length = self.psapi.GetMappedFileNameW(self.handle, info.base, name, len(name))
                    if length >= len(name):
                        raise RuntimeError(f"Mapped filename truncated at {info.base:#x}")
                    if length:
                        filename = name.value
                    elif c.get_last_error() not in (0, 1006):
                        self.fail(f"GetMappedFileNameW({info.base:#x})")
                regions.append(dict(base=info.base or 0, allocation=info.allocation or 0,
                    size=info.size, protect=info.protect,
                    type={0x20000: "anonymous", 0x40000: "mapped_section", 0x1000000: "image"}.get(
                        info.type, f"unknown_{info.type:#x}"), file=filename,
                    resident_bytes=0, private_resident_bytes=0, first_page_resident=False))
            address = end
        return regions

    def sample_pages(self, regions: list[dict]):
        for region in regions:
            for offset in range(0, region["size"], self.page_size * 16384):
                count = min(16384, (region["size"] - offset + self.page_size - 1) // self.page_size)
                entries = (self.WorkingSet * count)()
                for index, entry in enumerate(entries):
                    entry.address = region["base"] + offset + index * self.page_size
                if not self.psapi.QueryWorkingSetEx(self.handle, entries, c.sizeof(entries)):
                    self.fail(f"QueryWorkingSetEx({region['base'] + offset:#x})")
                for index, entry in enumerate(entries):
                    if entry.flags & 1:
                        region["resident_bytes"] += self.page_size
                        if not entry.flags & (1 << 15):
                            region["private_resident_bytes"] += self.page_size
                        if offset == 0 and index == 0:
                            region["first_page_resident"] = True

    def identify_images(self, regions: list[dict], images: dict):
        discoveries = []
        for region in regions:
            if (region["type"] != "anonymous" or not region["first_page_resident"]
                    or region["protect"] & 0x101 or region["size"] < ART_HEADER_SIZE):
                continue
            data = c.create_string_buffer(ART_HEADER_SIZE)
            read = c.c_size_t()
            if not self.kernel.ReadProcessMemory(self.handle, region["base"], data, len(data), c.byref(read)):
                self.fail(f"ReadProcessMemory({region['base']:#x}, image header)")
            if read.value != ART_HEADER_SIZE:
                raise RuntimeError(f"Short image header read at {region['base']:#x}")
            image = identify_image(data.raw, region["base"], images)
            if image:
                discoveries.append(dict(allocation=region["allocation"], image=image))
        associate_image_regions(regions, discoveries, self.page_size)


def capture(pid: int, image_directories: list[Path]) -> dict:
    images = load_images(image_directories)
    process = WindowsProcess(pid)
    try:
        started = datetime.now(timezone.utc).isoformat()
        before = process.counters()
        regions = process.regions()
        process.sample_pages(regions)
        sampled = process.counters()
        process.identify_images(regions, images)
        identified = process.counters()
        return dict(schema_version=1, pid=pid, started_utc=started,
                    finished_utc=datetime.now(timezone.utc).isoformat(), page_size=process.page_size,
                    process_counters_before=before, process_counters_after_sampling=sampled,
                    process_counters_after_identification=identified,
                    sampled_totals=dict(committed_region_bytes=sum(r["size"] for r in regions),
                        resident_bytes=sum(r["resident_bytes"] for r in regions),
                        private_resident_bytes=sum(r["private_resident_bytes"] for r in regions)),
                    notes=["Live capture is not atomic; process counters bracket page sampling.",
                           "Committed region bytes include file views; private commit is only in process counters.",
                           "Resident pages include shared pages; do not sum processes as unique physical RAM.",
                           "ART identification reads resident first-page headers after page sampling.",
                           "ART ranges include the final page but exclude regions outside the image allocation."],
                    groups=aggregate_regions(regions), regions=regions)
    finally:
        process.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pid", type=int)
    parser.add_argument("--images", type=Path, action="append", default=[],
                        help="Directory of matching .art files (may be repeated)")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        if args.pid <= 0:
            raise ValueError("PID must be positive")
        result = capture(args.pid, args.images)
        if args.output:
            args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
            print(json.dumps({key: value for key, value in result.items() if key not in ("groups", "regions")}, indent=2))
        else:
            print(json.dumps(result, indent=2))
        return 0
    except (OSError, ValueError, RuntimeError) as error:
        print(f"process_memory: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
