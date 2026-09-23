# native/

`FFM_MI` links `mi_malloc`/`mi_free`/`mi_realloc` out of `libmimalloc.so` with
`SymbolLookup.libraryLookup`. FFM cannot link a static archive, so a shared library is required.

Nothing binary is committed here. `build-mimalloc.sh` clones
[microsoft/mimalloc](https://github.com/microsoft/mimalloc) at the exact commit that was measured and
builds it:

| | |
| --- | --- |
| repository | `https://github.com/microsoft/mimalloc` |
| commit | `31d034d94cdb8e22f7d7ed55967f581a2d6e831d` ("Merge pull request #1407 from microsoft/dev3") |
| version | `MI_MALLOC_VERSION 30503`, i.e. **v3.5.3** |
| cmake | `-DCMAKE_BUILD_TYPE=Release -DMI_BUILD_SHARED=ON -DMI_BUILD_STATIC=ON -DMI_BUILD_OBJECT=OFF -DMI_BUILD_TESTS=OFF` |
| output | `native/mimalloc/out/release/libmimalloc.so -> libmimalloc.so.3.5` |

```
./native/build-mimalloc.sh                 # pinned commit
./native/build-mimalloc.sh <other-commit>  # anything else
```

`native/mimalloc/` is git-ignored.

Resolution order used by `FfmNative.mimallocLibrary()`:

1. `-Dffm.mimalloc.lib=<file>`
2. `-Dffm.mimalloc.dir=<dir>`
3. `<user.dir>/native/mimalloc/out/release/libmimalloc.so`, and the same under `<user.dir>/ffm-allocator/`
4. every entry of `java.library.path`
5. `/usr/local/lib`, `/usr/lib64`, `/usr/lib/x86_64-linux-gnu`

It never falls back to the default lookup: a run that silently used glibc's `malloc` while reporting
itself as mimalloc would be a measurement bug.

## The third mode: LD_PRELOAD

`LD_PRELOAD=<path>/libmimalloc.so` with `FFM_GLIBC` makes the default lookup's `malloc` *be* mimalloc,
for the whole process including the JVM's own C++ allocations. That is a different experiment
(`FFM_PRELOAD` in `RESULTS.md`), not the same thing as `FFM_MI`.
