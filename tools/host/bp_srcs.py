#!/usr/bin/env python3
"""Prints a module's expanded source files, as CMake lists them.

    bp_srcs.py <aosp dir> <directory of the module's Android.bp> <module>

Reads only that directory's Android.bp (and the filegroups it names in the
same file), evaluating variables and globs; prints absolute paths joined
with ';' for CMake.
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'framework'))

from blueprint import Config, parse_file  # noqa: E402
from tree import as_list, glob_regex  # noqa: E402
import re  # noqa: E402


def module_sources(base, modules, name):
    """Expand all globs in a module in one directory traversal.

    Only keep matched paths, rather than retaining a second inventory of the
    whole source tree. Filegroup references and literal paths retain their
    existing interpretation; the caller still receives sorted unique paths.
    """
    patterns = {}
    out = []
    def expand(mod):
        for e in as_list(modules[mod].get('srcs')):
            if e.startswith(':'):
                expand(e[1:])
            elif any(c in e for c in '*?['):
                if e not in patterns:
                    patterns[e] = re.compile(glob_regex(e))
            else:
                out.append(os.path.join(base, e))
    expand(name)
    if patterns:
        for dp, _, fs in os.walk(base):
            for f in fs:
                rel = os.path.relpath(os.path.join(dp, f), base).replace(os.sep, '/')
                if any(regex.fullmatch(rel) for regex in patterns.values()):
                    out.append(os.path.join(base, rel))
    return sorted(dict.fromkeys(out))


def main():
    aosp, directory, name = sys.argv[1:4]
    base = os.path.join(aosp, directory)
    modules = {p.get('name'): p for _, p in parse_file(os.path.join(base, 'Android.bp'), Config())}
    print(';'.join(module_sources(base, modules, name)), end='')


if __name__ == '__main__':
    main()
