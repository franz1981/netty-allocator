# ffm-allocator

An experimental Netty `ByteBufAllocator` whose **direct** buffers are plain `malloc`'d blocks obtained
through the Java **FFM** API ([JEP 454](https://openjdk.org/jeps/454)) with
[`Linker.Option.critical`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/Linker.Option.html#critical(boolean))
linking. Two back ends: glibc `malloc` (`FFM_GLIBC`) and
[mimalloc](https://github.com/microsoft/mimalloc)'s `mi_malloc` (`FFM_MI`).

It is the native counterpart of this repository's `mimalloc` module, which is a Java **port** of the
mimalloc algorithm: same allocator, one reimplemented on the Java heap, the other called through FFM.
`RESULTS.md` has them measured against each other and against Netty's `AdaptiveByteBufAllocator`.

> Standalone, self-contained copy of this module (same sources, released Netty dependency, raw results
> and the profile against the Java port): **https://github.com/franz1981/netty-ffm-allocator**

Heap buffers are out of scope: they are delegated to `UnpooledByteBufAllocator`.

## Build

The module is **not** part of the default build. The rest of the repository targets Java 8/11 and is built
with JDK 21; this module needs JDK 25, so it sits behind the `ffm` profile, which enforces the JDK version:

```
JAVA_HOME=~/.sdkman/candidates/java/25.0.3-tem mvn -Pffm -Dnetty.version=4.2.17.Final-SNAPSHOT package
```

The `-Dnetty.version` override is a property of this branch, not of this module: the `benchmark` module here
imports `io.netty.buffer.CycleArenaAllocator`, which exists only in the locally installed
`4.2.17.Final-SNAPSHOT` and not in the released `4.2.17.Final`, so a plain `mvn -Pffm package` fails in
`benchmark` before it reaches this module. Building this module alone needs no override:
`mvn -Pffm -pl ffm-allocator package`.

`mvn package` on JDK 21 does not build it (the reactor is `netty-allocator`, `mimalloc`, `benchmark`), and
`mvn -Pffm package` on JDK 21 fails with
`-Pffm needs JDK 25 or later (final FFM API and Linker.Option.critical)`.

Artifacts:

| file | what |
| --- | --- |
| `target/ffm-allocator-<version>.jar` | the allocator; goes on the harness classpath |
| `target/ffm-microbench.jar` | runnable JMH jar with `MallocRoundTripBenchmark` and `AllocFreeVerifier` |

mimalloc is built from source by `native/build-mimalloc.sh` (pinned commit, no binary committed). See
[`native/README.md`](native/README.md).

## Running it in the benchmark harness

`ByteBufAllocatorAllocPatternBenchmark` resolves `io.netty.buffer.FfmMallocByteBufAllocator` reflectively,
so the `benchmark` module keeps building on JDK 21 without this one. Put both jars on the classpath:

```
java -cp benchmark/target/benchmarks.jar:ffm-allocator/target/ffm-allocator-1.2.4.Final-SNAPSHOT.jar \
     org.openjdk.jmh.Main ByteBufAllocatorAllocPatternBenchmark.directAllocation \
     -p allocatorType=ADAPTIVE,MIMALLOC,FFM_GLIBC,FFM_MI \
     -p sizePattern=E_COMMERCE -p enableReadWrite=true -p MAX_LIVE_BUFFERS=1024 -t 32 -f 3 -wi 10 -i 10 \
     -jvmArgsAppend "--enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow \
                     -Dffm.mimalloc.lib=<path>/libmimalloc.so"
```

`-jar benchmarks.jar` will **not** work: in jar mode the class path is the jar alone and the allocator is
not found.

## Required JVM flags

```
--enable-native-access=ALL-UNNAMED        # downcallHandle, libraryLookup, MemorySegment.reinterpret
--sun-misc-unsafe-memory-access=allow     # see below
```

`PlatformDependent0.explicitNoUnsafeCause0()` sets `noUnsafe = true` **by default on Java 25+** unless
`io.netty.noUnsafe` is set explicitly or the JVM was started with `--sun-misc-unsafe-memory-access=...`
(anything other than `allow` also disables it). Every accessor in this buffer goes through
`UnsafeByteBufUtil` on a raw address and there is no `VarHandle` fallback for that, so without the flag the
allocator's constructor fails fast with that message. With the flag, Netty reports `hasUnsafe=true`,
`useVarHandleForMultiByteAccess=false` — the same configuration as a default JDK 21 JVM, which is what the
reference runs used. The flag is passed to *every* allocator in the comparison, so the playing field is
level.

## `-Dffm.*` knobs

| property | default | effect |
| --- | --- | --- |
| `ffm.mimalloc.lib` | - | full path of `libmimalloc.so` |
| `ffm.mimalloc.dir` | - | directory holding `libmimalloc.so` |
| `ffm.localCache` | `true` | `false` sends every wrapper through Netty's `Recycler` |
| `ffm.localCacheSize` | `4096` | owner-only array stack size, per thread |
| `ffm.platformCache` | `true` | `false` gives platform threads no per-thread cache |
| `ffm.handBackCapacity` | `4096` | bounded MPSC hand-back queue, per thread |
| `ffm.platformRecycler` | `default` | `shared` uses a second `Recycler` built with the shared-pool constructor on platform threads |
| `ffm.sharedRecyclerCapacity` | `65536` | capacity of that shared `Recycler` |
| `ffm.metrics` | `false` | maintain the counters printed by `FfmMallocByteBufAllocator.stats()` |

All of them are read once, at class-initialization time.

## Design

* **Memory**: `malloc`/`free`/`realloc` bound as `static final MethodHandle`s with
  `Linker.Option.critical(false)`, pointers modelled as `JAVA_LONG` and invoked with `invokeExact`, so a
  call is a leaf call with no thread-state transition, no `MemorySegment` wrapper and no allocation.
  One `Binding` subclass per back end, each calling its own class's `static final` handles, so the JIT sees
  a constant handle and inlines the downcall stub (`RESULTS.md` §2 has the `PrintInlining` log).
* **Buffer**: `FfmByteBuf extends AbstractReferenceCountedByteBuf` — Netty's own refcount. All accessors are
  `UnsafeByteBufUtil.<op>(memoryAddress + index)`, modelled on `PooledUnsafeDirectByteBuf`.
  `hasMemoryAddress()` and `isContiguous()` are `true`. `capacity(int)` is `realloc` (contents preserved,
  indices trimmed on shrink, the cached `ByteBuffer` view dropped). `nioBuffer`/`internalNioBuffer` build
  the `ByteBuffer` lazily with `MemorySegment.ofAddress(a).reinterpret(len).asByteBuffer()` and cache it per
  buffer instance — `PlatformDependent.directBuffer(long,int)` is not used because it needs the private
  `DirectByteBuffer(long,int)` constructor, which is closed without `--add-opens java.base/java.nio`.
* **Wrapper recycling**: every thread (`FastThreadLocalThread` or platform) gets a `LocalCache`: an
  owner-only array stack, no atomics, bounded by `ffm.localCacheSize`, plus a bounded MPSC hand-back queue
  that foreign threads push released wrappers into and the owner drains in batches of 64 when its stack runs
  dry. The release path is one `cache.owner == Thread.currentThread()` identity compare. Overflow at either
  end falls back to Netty's `Recycler` — never to the floor — and is counted under `-Dffm.metrics=true`.
  `-Dffm.localCache=false` turns the stack off so everything goes through the `Recycler`; note that Netty's
  default (FastThreadLocal) `Recycler` does **not** pool on a platform thread, `get()` returns
  `newObject(NOOP_HANDLE)` there, which is what `-Dffm.platformRecycler=shared` works around, at a cost
  measured in `RESULTS.md`.
* **Back ends**: `Backend.GLIBC` (`malloc` from the default lookup) and `Backend.MIMALLOC` (`mi_malloc` from
  `libmimalloc.so` via `SymbolLookup.libraryLookup`; FFM cannot link a static archive). A third mode needs
  no code: run with `LD_PRELOAD=libmimalloc.so` and `Backend.GLIBC` — but that replaces `malloc` for the
  whole process, the JVM included.

## Lifetime of the raw views: read this before using it for anything real

`memoryAddress()` and the `ByteBuffer` from `nioBuffer(int, int)` are raw pointers into a `malloc`'d block
that `release()` frees. `MemorySegment.ofAddress(long)` returns a zero-length segment in the **global**
scope and `reinterpret(long)` keeps that scope, so the `ByteBuffer` is *never* invalidated: after
`release()` it still reads and writes, into memory that belongs to somebody else. That is the same contract
as Netty's own no-cleaner direct buffers, and the unit test `nioBufferViewIsBigEndianAndNotInvalidatedByRelease`
asserts it so it cannot change silently. The buffer itself never uses a stale view: the cached one is
dropped in `deallocate()` and in `capacity(int)`.

## Split package

The allocator lives in `io.netty.buffer` so that it can use the package-private `UnsafeByteBufUtil`
accessors and `AbstractByteBuf` internals (`_memoryAddress`, `setIndex0`, `trimIndicesToCapacity`). On the
class path that is a split package with `netty-buffer.jar`, which is fine for an experiment but would not be
acceptable for a JPMS module (it would be rejected). The JMH classes live in
`io.github.neoionet.netty.ffm` and are not in the split package.

## Verification

`mvn -Pffm test` runs 16 unit tests: write/read/release, realloc grow and shrink, `ensureWritable`, nio
views, bulk copy, wrapper reuse on a `FastThreadLocalThread`, cross-thread release, the hand-back queue and
its overflow, zero-capacity buffers, and the release-then-access checks.

`AllocFreeVerifier` runs a steady-state alloc/write/read/release loop and reports
`ThreadMXBean.getThreadAllocatedBytes` for the worker thread, GC counts and the number of wrapper objects
created:

```
java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow \
     -cp target/ffm-microbench.jar io.github.neoionet.netty.ffm.AllocFreeVerifier MIMALLOC 1024 20000000
```

`MallocRoundTripBenchmark` measures one `malloc`+`free` pair with and without `critical` linking, for both
back ends:

```
java -jar target/ffm-microbench.jar MallocRoundTripBenchmark -f 3 -wi 5 -i 5
```

Numbers for both are in `RESULTS.md`.

## References

* [JEP 454: Foreign Function & Memory API](https://openjdk.org/jeps/454)
* [`Linker.Option.critical(boolean)`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/Linker.Option.html#critical(boolean))
* [microsoft/mimalloc](https://github.com/microsoft/mimalloc) and the
  [mimalloc technical report](https://www.microsoft.com/en-us/research/publication/mimalloc-free-list-sharding-in-action/)
* the Java port this repository is built around: [neoionet/netty-allocator](https://github.com/neoionet/netty-allocator)
* Netty [PR #17151](https://github.com/netty/netty/pull/17151), the `AdaptiveByteBufAllocator` work the
  `ADAPTIVE` column measures
