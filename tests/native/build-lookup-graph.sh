#!/usr/bin/env bash
# Build with a host or NDK C compiler. A suffix keeps x64 and ARM64 sonames
# distinct when the CI artifacts share one directory.
set -euo pipefail
compiler=$1
output=$2
suffix=${3:-}
here=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$output"
common=(-shared -fPIC -O2 -fno-stack-protector -Wall -Wextra -Werror
        -Wl,--hash-style=both '-Wl,-rpath,$ORIGIN')
build() {
  local name=$1 role=$2
  shift 2
  "$compiler" "${common[@]}" -DLOOKUP_ROLE="$role" \
    "-DLOOKUP_ROOT_SONAME=\"lookup_root${suffix}.so\"" \
    "-Wl,-soname,lookup_${name}${suffix}.so" \
    -o "$output/lookup_${name}${suffix}.so" "$here/lookup_graph.c" \
    -L"$output" -Wl,--no-as-needed "$@"
}
build leaf 4
build shared 5
build middle 2 "-l:lookup_leaf${suffix}.so" "-l:lookup_shared${suffix}.so"
build right 3 "-l:lookup_shared${suffix}.so"
build root 1 "-l:lookup_middle${suffix}.so" "-l:lookup_right${suffix}.so" -ldl
