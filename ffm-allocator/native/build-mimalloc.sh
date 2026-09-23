#!/bin/bash
# Build the mimalloc shared library the FFM_MI back end links against.
#
# No binary is committed: this clones microsoft/mimalloc at the exact commit that was measured and builds it
# with cmake. Output: native/mimalloc/out/release/libmimalloc.so(.3.5), which is where
# FfmNative.mimallocLibrary() looks by default when the JVM is started from the module directory.
#
# Usage: ./native/build-mimalloc.sh [commit]
set -euo pipefail

# microsoft/mimalloc, MI_MALLOC_VERSION 30503 (v3.5.3), "Merge pull request #1407 from microsoft/dev3".
COMMIT="${1:-31d034d94cdb8e22f7d7ed55967f581a2d6e831d}"
REPO="https://github.com/microsoft/mimalloc"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$HERE/mimalloc"
OUT="$SRC/out/release"

if [ ! -d "$SRC/.git" ]; then
    git clone "$REPO" "$SRC"
fi
git -C "$SRC" fetch --all --tags
git -C "$SRC" checkout --detach "$COMMIT"

cmake -S "$SRC" -B "$OUT" -DCMAKE_BUILD_TYPE=Release \
      -DMI_BUILD_SHARED=ON -DMI_BUILD_STATIC=ON -DMI_BUILD_OBJECT=OFF -DMI_BUILD_TESTS=OFF
cmake --build "$OUT" -j "$(nproc)"

echo
echo "commit:  $(git -C "$SRC" rev-parse HEAD)"
echo "version: $(grep -m1 'define MI_MALLOC_VERSION' "$SRC/include/mimalloc.h")"
ls -la "$OUT"/libmimalloc.so*
echo
echo "Pass it with -Dffm.mimalloc.lib=$OUT/libmimalloc.so, or run the JVM from this module directory."
