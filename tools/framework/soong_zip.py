#!/usr/bin/env python3
"""The part of Soong's soong_zip that genrules use to make source jars.

    soong_zip.py [-jar|-srcjar] [-write_if_changed] -o <out> (-C <dir> -D <dir> | -f <file>)...

Zips the files under each -D directory, stored under their paths relative
to the preceding -C directory, in sorted order with fixed timestamps.
"""
import os
import shutil
import stat
import sys
import tempfile
import zipfile


def main():
    args = sys.argv[1:]
    out, base, entries = None, None, []
    write_if_changed = False
    i = 0
    while i < len(args):
        a = args[i]
        if a == '-write_if_changed':
            write_if_changed = True
            i += 1
            continue
        if a in ('-jar', '-srcjar', '-symlinks=false', '-d'):
            i += 1
            continue
        if a == '-o':
            out = args[i + 1]
        elif a == '-C':
            base = args[i + 1]
        elif a == '-D':
            root = args[i + 1]
            for dp, _, fs in os.walk(root):
                for f in fs:
                    path = os.path.join(dp, f)
                    entries.append((os.path.relpath(path, base or root), path))
        elif a == '-f':
            path = args[i + 1]
            entries.append((os.path.relpath(path, base) if base else os.path.basename(path), path))
        else:
            raise SystemExit(f'soong_zip.py: unsupported argument {a}')
        i += 2
    temporary = None
    if write_if_changed:
        descriptor, temporary = tempfile.mkstemp(prefix='.soong_zip-', dir=os.path.dirname(out) or '.')
        os.close(descriptor)
    try:
        with zipfile.ZipFile(temporary or out, 'w', zipfile.ZIP_DEFLATED) as z:
            for name, path in sorted(entries):
                info = zipfile.ZipInfo(name.replace(os.sep, '/'), (2008, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                with open(path, 'rb') as f:
                    # Generated sources can be large. Preserve the deterministic
                    # ZipInfo while bounding input storage and selecting ZIP64 from
                    # the known size, as writestr did for a complete bytes object.
                    info.file_size = os.fstat(f.fileno()).st_size
                    with z.open(info, 'w') as entry:
                        shutil.copyfileobj(f, entry, length=64 * 1024)
        if temporary:
            if os.path.exists(out):
                if same_contents(out, temporary):
                    return  # Keep its timestamp: dependent compilations need no work.
                shutil.copymode(out, temporary)
            os.replace(temporary, out)
    finally:
        if temporary and os.path.exists(temporary):
            try:
                os.unlink(temporary)
            except PermissionError:
                # A failed replacement may leave the old output's read-only
                # mode on our temporary. Windows requires it writable to delete.
                os.chmod(temporary, stat.S_IREAD | stat.S_IWRITE)
                os.unlink(temporary)


def same_contents(left, right):
    if os.path.getsize(left) != os.path.getsize(right):
        return False
    with open(left, 'rb') as a, open(right, 'rb') as b:
        while True:
            chunk = a.read(64 * 1024)
            if chunk != b.read(64 * 1024):
                return False
            if not chunk:
                return True


if __name__ == '__main__':
    main()
