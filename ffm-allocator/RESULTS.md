# Results

## Environment

| | |
| --- | --- |
| CPU | AMD Ryzen 9 7950X, 16 cores / 32 threads, 2 NUMA nodes; **node 0 is 8 cores / 16 logical CPUs** (`0-7,16-23`) |
| pinning | `numactl --cpunodebind=0 --preferred=0` for every reported run, so `-t 32` is 2x oversubscribed |
| frequency | `scaling_max_freq` pinned to **2300000** for every reported run, restored to 4300000 afterwards |
| kernel | `7.1.13-100.fc43.x86_64` |
| JDK | `~/.sdkman/candidates/java/25.0.3-tem` (Temurin 25.0.3+9) for **all** allocators in every run |
| Netty | `4.2.17.Final-SNAPSHOT` from `~/.m2`, shaded into the harness jar |
| mimalloc | `microsoft/mimalloc` @ `31d034d94cdb8e22f7d7ed55967f581a2d6e831d`, `MI_MALLOC_VERSION 30503` (**v3.5.3**), cmake Release, shared + static |
| harness | `benchmark/target/benchmarks.jar` built with `-Dnetty.version=4.2.17.Final-SNAPSHOT`, plus `ffm-allocator-1.2.4.Final-SNAPSHOT.jar` on the class path |

**Which `ADAPTIVE` is this?** Verified by unzipping the harness jar, not assumed: every
`io/netty/buffer/AdaptivePoolingAllocator*.class` in `benchmarks.jar` is **byte-identical** (`cmp`) to the
one in `netty-buffer-4.2.19.Final-SNAPSHOT.jar`, the snapshot installed from `netty-sc-queues`
(PR #17151 head). The only differing Adaptive class files are `AdaptiveByteBufAllocator*`, where
4.2.19 has a private constructor plus a synthetic bridge (`AdaptiveByteBufAllocator$1`) and 4.2.17 has a
package-private one — a javac artifact of a visibility change, with identical bytecode otherwise
(`javap -c` diff). So `ADAPTIVE` in these tables is PR #17151's allocator.

Note that the released `netty-buffer-4.2.17.Final` is **not** that code (it still has `Magazine`,
`ChunkCache`, `ConcurrentSkipListChunkCache`); the harness jar must be built against the snapshot.

The mimalloc shared library used for the measurements was built from the pinned commit with the same
cmake flags as `native/build-mimalloc.sh`, in a different build directory. The two `.so` files are the
same size but not byte-identical (the build path is embedded); I did not verify that the emitted
instructions are identical.

JVM flags shared by every allocator in every run:

```
-server -dsa -da -XX:InitialRAMPercentage=40.0 -XX:MaxRAMPercentage=40.0 -XX:MaxRAM=60g
-Dio.netty.leakDetection.level=disabled
--enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow
```

`--sun-misc-unsafe-memory-access=allow` is required: on Java 25 Netty defaults to
`io.netty.noUnsafe=true`, which makes `PlatformDependent.hasUnsafe()` false and every address-based
`UnsafeByteBufUtil` accessor throw `NullPointerException` (`PlatformDependent0.UNSAFE is null`).
Measured with the flag: `hasUnsafe=true`, `useVarHandleForMultiByteAccess=false`,
`hasDirectBufferNoCleanerConstructor=false` — the same three values a default JDK 21 JVM reports, so the
Netty allocators are in the configuration the earlier JDK 21 runs used.

## 1. Hot path is GC-free (measured)

`microbench` `AllocFreeVerifier`: 1024 live buffers, 20 M alloc+release ops after 5 M warm-up ops,
`com.sun.management.ThreadMXBean.getThreadAllocatedBytes` on the worker thread, GC counts from the GC
MXBeans, wrapper count from the allocator's own counter. Raw output:
`netty-bench/results/h2h-merged-x86-2026-09-22/ffm/gcfree-verify.txt`.

| backend | thread | heap bytes allocated by worker | B/op | GCs in window | wrappers created |
| --- | --- | ---: | ---: | ---: | ---: |
| GLIBC | FastThreadLocalThread | 0 | 0.0000 | 0 | 0 |
| MIMALLOC | FastThreadLocalThread | 0 | 0.0000 | 0 | 0 |
| GLIBC | platform thread | 1 760 000 000 | 88.0 | 3 | 20 000 000 |
| MIMALLOC | platform thread | 1 760 000 000 | 88.0 | 3 | 20 000 000 |

On a `FastThreadLocalThread` the steady state allocates **exactly zero** bytes on the Java heap per
operation. On a platform thread it allocates one wrapper per operation (88 B). That is not a property of
this allocator: `Recycler.get()` (netty-common 4.2, line 329) reads

```java
if (!FastThreadLocalThread.currentThreadWillCleanupFastThreadLocals()) {
    return newObject((Handle<T>) NOOP_HANDLE);
}
```

so on a platform thread Netty's `Recycler`, built with the default (FastThreadLocal) constructor, does
not pool at all — it returns a fresh object with the no-op handle. ADAPTIVE is *not* in that position:
`AdaptivePoolingAllocator.AdaptiveRecycler` is built with `Recycler(int maxCapacity, boolean unguarded)`
and `Recycler(int, boolean, boolean)` — the constructors whose own comment says "doesn't use fast thread
local, shared MPMC" — so it takes the `localPool` branch of `get()` on any thread. That matches the
measured platform-thread GC counts below (ADAPTIVE 0, FFM 2-4).

## 2. The downcall is inlined (PrintInlining)

`-XX:-TieredCompilation -XX:CICompilerCount=1 -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining`
(single compiler thread so the log is not interleaved). Full log:
`netty-bench/results/h2h-merged-x86-2026-09-22/ffm/printinlining-glibc.txt`. Allocation path:

```
@ 21   io.netty.buffer.FfmMallocByteBufAllocator::newDirectBuffer (140 bytes)   inline (hot)
  @ 16   io.netty.buffer.FfmNative$Libc::malloc (5 bytes)   inline (hot)
         \-> TypeProfile (5190/5190 counts) = io/netty/buffer/FfmNative$Libc
    @ 1   io.netty.buffer.FfmNative$Libc::doMalloc (14 bytes)   inline (hot)
      @ 14   jdk.internal.foreign.abi.DowncallStub/0x...::invoke (95 bytes)   force inline by annotation
        @ 14   java.lang.invoke.MethodHandle::linkToNative(JJL)J (0 bytes)   failed to inline: native call
  @ 54   io.netty.buffer.FfmMallocByteBufAllocator$LocalCache::pop (34 bytes)   inline (hot)
```

Release path:

```
@ 5   io.netty.buffer.FfmByteBuf::deallocate (72 bytes)   inline (hot)
  @ 28   io.netty.buffer.FfmNative$Libc::free (5 bytes)   inline (hot)
         \-> TypeProfile (12011/12011 counts) = io/netty/buffer/FfmNative$Libc
    @ 1   io.netty.buffer.FfmNative$Libc::doFree (17 bytes)   inline (hot)
      @ 14   jdk.internal.foreign.abi.DowncallStub/0x...::invoke (91 bytes)   force inline by annotation
        @ 14   java.lang.invoke.MethodHandle::linkToNative(JJL)V (0 bytes)   failed to inline: native call
  @ 68   io.netty.buffer.FfmMallocByteBufAllocator::recycleWrapper (28 bytes)   inline (hot)
    @ 16   io.netty.buffer.FfmMallocByteBufAllocator$LocalCache::push (32 bytes)   inline (hot)
```

The `Binding` virtual call is monomorphic (`TypeProfile 5190/5190`), the downcall stub is force-inlined,
and the only thing not inlined is `MethodHandle::linkToNative`, which *is* the call instruction into
native code. The thread-local array stack `push`/`pop` inline into the alloc/release path.


## 3. E_COMMERCE `directAllocation`, `enableReadWrite=true`, 3 forks, 10+10 x 1 s

`FFM_PRELOAD` is `FFM_GLIBC` run with `LD_PRELOAD=libmimalloc.so` (mimalloc replaces malloc for the whole
process, JVM included). Raw JSON/logs in `netty-bench/results/h2h-merged-x86-2026-09-22/ffm/`.

### ns/op — event-loop executor (`FastThreadLocalThreadHarnessExecutor`), 1 thread

| live | ADAPTIVE | MIMALLOC | FFM_GLIBC | FFM_MI | FFM_PRELOAD |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 128 | 64.13 ±0.28 | 67.33 ±0.09 | 94.96 ±0.24 | 69.48 ±3.89 | 94.93 ±0.54 |
| 1024 | 76.18 ±1.29 | 75.83 ±1.84 | 106.28 ±0.18 | 77.09 ±4.49 | 109.37 ±0.26 |
| 4096 | 89.41 ±0.39 | 80.35 ±1.31 | 120.29 ±1.73 | 79.92 ±2.82 | 124.15 ±1.21 |

per-fork means (128/1024/4096): ADAPTIVE `64.4 63.8 64.2` / `75.2 78.8 74.5` / `88.8 89.3 90.2`;
MIMALLOC `67.4 67.4 67.2` / `79.6 73.6 74.2` / `78.8 83.1 79.1`;
FFM_GLIBC `95.0 95.2 94.7` / `106.6 106.1 106.1` / `123.8 119.0 118.1`;
FFM_MI `65.2 77.6 65.6` / `71.3 73.6 86.3` / `76.2 77.8 85.7`;
FFM_PRELOAD `96.0 94.5 94.3` / `109.1 109.9 109.2` / `126.3 122.9 123.3`.

### ns/op — event-loop executor, 32 threads

| live | ADAPTIVE | MIMALLOC | FFM_GLIBC | FFM_MI | FFM_PRELOAD |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 128 | 237.03 ±6.7 | 260.22 ±10.1 | 322.08 ±11.3 | 245.29 ±8.1 | 323.65 ±8.6 |
| 1024 | 279.65 ±18.2 | 262.95 ±6.9 | 355.18 ±12.5 | 258.60 ±8.5 | 367.69 ±11.6 |
| 4096 | 334.13 ±10.8 | 310.48 ±9.3 | 460.71 ±16.1 | 343.20 ±10.1 | 482.97 ±21.0 |

per-fork means (128/1024/4096): ADAPTIVE `235.7 236.0 239.4` / `269.0 306.0 264.0` / `323.9 333.7 344.8`;
MIMALLOC `258.0 248.8 273.9` / `263.2 264.1 261.6` / `311.3 302.7 317.3`;
FFM_GLIBC `325.4 324.7 316.1` / `358.2 345.8 361.5` / `460.6 456.1 465.4`;
FFM_MI `249.2 245.3 241.3` / `260.3 256.8 258.7` / `334.8 346.0 348.8`.

### ns/op — platform executor (`-Djmh.executor=PLATFORM`), 32 threads

| live | ADAPTIVE | MIMALLOC | FFM_GLIBC | FFM_MI |
| ---: | ---: | ---: | ---: | ---: |
| 128 | 364.81 ±11.4 | 308.45 ±9.3 | 379.18 ±25.2 | 290.39 ±19.9 |
| 1024 | 412.92 ±27.5 | 325.14 ±9.9 | 405.59 ±26.8 | 313.38 ±23.7 |
| 4096 | 459.62 ±16.8 | 386.94 ±12.5 | 510.72 ±30.6 | 392.09 ±19.6 |

### peak RSS (MiB, max over the 3 forks, from the harness `cRSS-pRSS` lines)

| cell | ADAPTIVE | MIMALLOC | FFM_GLIBC | FFM_MI | FFM_PRELOAD |
| --- | ---: | ---: | ---: | ---: | ---: |
| eventloop t1, 128/1024/4096 | 1067/1100/1106 | 1062/1068/1076 | 1054/1052/1076 | 1065/1071/1111 | 1160/1169/1182 |
| eventloop t32, 128/1024/4096 | 1811/2236/2643 | 1957/2094/2619 | 1622/1804/2325 | 1810/2030/2608 | 1743/1916/2394 |
| platform t32, 128/1024/4096 | 1862/2276/2655 | 1955/2174/2960 | **15509/15687/16217** | **15706/15907/16482** | — |

### GC counts (separate 1-fork, 5+5 run with `-prof gc`; `gc.count` over the measured iterations)

Event-loop executor, 1 and 32 threads: **0** for all four allocators at all three live counts.
Platform executor, 32 threads: ADAPTIVE 0, MIMALLOC 0, FFM_GLIBC 3/3/2, FFM_MI 4/3/2 (128/1024/4096).

### Platform threads: the Recycler mode matters

`-Dffm.platformRecycler=shared` makes the platform-thread path take wrappers from a second `Recycler`
built with the shared-pool constructor (`Recycler(maxCapacity, unguarded)`) instead of the default
FastThreadLocal one. Same cells, 3 forks, platform executor, 32 threads:

| live | FFM_GLIBC ns/op | FFM_MI ns/op | FFM_GLIBC RSS | FFM_MI RSS |
| ---: | ---: | ---: | ---: | ---: |
| 128 | 11920 ±437 | 11604 ±426 | 1623 | 1796 |
| 1024 | 11724 ±483 | 11579 ±424 | 1787 | 2032 |
| 4096 | 12510 ±845 | 12030 ±549 | 2203 | 2565 |

RSS drops from ~15.5–16.5 GiB to ~1.6–2.6 GiB and the single-thread verifier goes to 0 B/op / 0 GCs /
0 wrappers created, but throughput at 32 threads is 25–30x worse than the default. Both are measurements;
I did not profile the shared-pool run, so I am not claiming what the 11.6 µs/op is spent on.

## 4. perfnorm (1 fork, 1024 live, 1 thread, event-loop executor)

| | ns/op | insns/op | cycles/op | IPC | branches/op | branch-miss/op | L1d-loads/op | L1d-miss/op | dTLB-loads/op | dTLB-miss/op | stalled-FE/op |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| ADAPTIVE | 78.44 | 579.8 | 177.9 | 3.258 | 115.5 | 0.555 | 245.2 | 5.76 | 1.79 | 0.024 | 5.95 |
| MIMALLOC | 81.77 | 496.2 | 185.6 | 2.673 | 95.9 | 0.659 | 211.1 | 6.52 | 1.59 | 0.014 | 7.09 |
| FFM_GLIBC | 107.35 | 604.2 | 243.3 | 2.484 | 126.3 | 2.771 | 257.8 | 6.61 | 4.14 | 0.048 | 29.82 |
| FFM_MI | 78.93 | 506.2 | 178.8 | 2.831 | 101.0 | 0.747 | 199.4 | 8.83 | 0.28 | 0.008 | 12.63 |

Sanity: 177.9 cycles/op at 2300 MHz = 77.3 ns/op, matching the 78.44 ns/op score.
FFM_GLIBC carries +24 instructions but +65 cycles over ADAPTIVE, with 5.0x its branch misses and 5.0x its
front-end stalls. FFM_MI is within noise of ADAPTIVE on cycles while executing 74 fewer instructions.

## 5. perfasm (`event=cycles`, 1 fork, 1024 live, 1 thread)

Distribution by source:

| source | FFM_GLIBC | FFM_MI |
| --- | ---: | ---: |
| C2-compiled Java | 42.19% | 56.95% |
| the C allocator | 51.79% (`libc.so.6`) | 37.92% (shown as `<unknown>`) |
| `[kernel.kallsyms]` | 2.49% | 2.11% |
| `libjvm.so` | 0.98% | 0.96% |
| other | 2.55% | 2.06% |

JMH could not name the mimalloc DSO. A standalone `perf record -e cycles` on the `AllocFreeVerifier`
loop resolves it: `libmimalloc.so.3.5` 31.77% (GLIBC control run: `libc.so.6` 37.42%), so the 37.92%
`<unknown>` is mimalloc's code. Top symbols there: `operator delete[]` 10.9%, `_mi_malloc_generic` 9.7%,
`mi_page_queue_find_free_ex` 2.5%, `mi_page_queue_remove` 2.4%, `_mi_page_malloc_zero` 1.3%.

Hottest regions, FFM_GLIBC: `readAndRelease` 15.5% + 6.1%, `allocateDirect` 13.9% + 1.7%,
`performDirectAllocation` 3.9%, then `cfree` 6.4%, `__libc_malloc2` 5.5%, `_int_malloc` 5.2%/2.6%/2.6%/1.9%,
`_int_free_create_chunk` 3.0%, `malloc_consolidate` 2.9%, `_int_free_chunk` 2.8%, `unlink_chunk` 2.7%.
Hottest methods after inlining, FFM_MI: `<unknown>` (mimalloc) 37.9%, `readAndRelease` 19.8%,
`performDirectAllocation` 18.1%, `allocateDirect` 17.1%, `MethodHandle::linkToNative` 1.0%.

**There is no separate downcall-stub region in either profile.** The only stub frame that shows up at all
is `MethodHandle::linkToNative` at 1.01% in FFM_MI (and below the cut in FFM_GLIBC); everything else in
the Java column is the C2 code of the harness methods with the inlined stub inside them.

## 6. Raw malloc/free round trip (3 forks, 5+5, 2300 MHz)

`microbench` `MallocRoundTripBenchmark`: one `malloc(size)` + `free(ptr)` per op, nothing else.

| binding | 64 B | 1024 B |
| --- | ---: | ---: |
| glibc, `critical(false)` | 10.68 ±0.20 | 10.58 ±0.01 |
| glibc, plain downcall | 14.58 ±0.00 | 15.75 ±0.92 |
| mimalloc, `critical(false)` | 10.70 ±0.07 | 11.89 ±0.03 |
| mimalloc, plain downcall | 16.71 ±4.23 | 15.15 ±0.02 |

Critical linking is worth **3.9–5.2 ns per malloc+free pair** (≈2–2.6 ns per call) here. This is
alloc-then-immediately-free, the friendliest case for any allocator, so 10.6 ns is a floor, not the cost
the harness pays.

---

# v2: a real per-thread wrapper cache for platform threads

Same `LocalCache` (bounded owner-only array stack, 4096) is now used for platform threads too, behind a
plain `ThreadLocal`; every cache also owns a bounded MPSC hand-back queue
(`PlatformDependent.newFixedMpscQueue`, 4096) that foreign threads push released wrappers into, drained
by the owner in batches of up to 64 when its stack runs dry. Owner check is the `Thread` reference stored
in the cache. Overflow at either end drops the wrapper to GC and is counted
(`handBackOverflowToRecycler`, `ownerStackOverflowToRecycler`; at the time of the v2 runs the overflow
policy was to drop the wrapper to GC instead of handing it to the `Recycler` — see "Changes since the
measurements" at the end). Two new unit tests cover the hand-back path
(256 foreign releases offered, drained by the owner, 0 new wrappers) and its overflow counter.

### `getThreadAllocatedBytes` on the worker thread, 20 M ops after 5 M warm-up

| backend | thread | B/op | GCs | wrappers created |
| --- | --- | ---: | ---: | ---: |
| GLIBC | FastThreadLocalThread | 0.0000 | 0 | 0 |
| GLIBC | **platform thread** | **0.0000** | **0** | **0** |
| MIMALLOC | FastThreadLocalThread | 0.0000 | 0 | 0 |
| MIMALLOC | **platform thread** | **0.0000** | **0** | **0** |
| GLIBC | platform, `ffm.platformCache=false` + `io.netty.recycler.batchFastThreadLocalOnly=false` | 88.0 | 3 | 20 000 000 |

`batchFastThreadLocalOnly=false` changes nothing, as the source says it should not: `Recycler.get()`
takes `if (!FastThreadLocalThread.currentThreadWillCleanupFastThreadLocals()) return newObject(NOOP_HANDLE)`
before any batching decision; `BATCH_FAST_TL_ONLY` is only read in `LocalPool`'s constructor.

### ns/op, peak RSS (MiB), gc.count — 3 forks, 10+10 x 1 s, 32 threads, same jar for both executors

| live | executor | ADAPTIVE | MIMALLOC | FFM_GLIBC | FFM_MI |
| ---: | --- | ---: | ---: | ---: | ---: |
| 128 | platform | 368.5 ±17.6 / 1863 / 0 | 308.0 ±8.2 / 1957 / 0 | **329.0 ±7.1 / 1628 / 0** | **253.5 ±10.0 / 1802 / 0** |
| 1024 | platform | 405.5 ±22.4 / 2265 / 0 | 328.3 ±7.7 / 2153 / 0 | **366.5 ±11.9 / 1811 / 0** | **287.5 ±9.7 / 2022 / 0** |
| 4096 | platform | 462.4 ±18.2 / 2658 / 0 | 395.8 ±18.0 / 2651 / 0 | **461.1 ±18.7 / 2329 / 0** | **358.7 ±11.8 / 2600 / 0** |
| 128 | eventloop | 235.6 ±7.9 / 1848 / 0 | 257.7 ±9.9 / 1957 / 0 | 319.6 ±9.1 / 1633 / 0 | 239.1 ±8.0 / 1798 / 0 |
| 1024 | eventloop | 288.2 ±23.6 / 2285 / 0 | 265.5 ±7.5 / 2093 / 0 | 353.0 ±11.9 / 1805 / 0 | 260.3 ±9.6 / 2045 / 0 |
| 4096 | eventloop | 318.3 ±7.7 / 2653 / 0 | 309.7 ±10.8 / 2616 / 0 | 454.6 ±20.1 / 2330 / 0 | 344.9 ±12.0 / 2602 / 0 |

Peak RSS is the max over the 3 forks; `gc.count` is from a separate 1-fork `-prof gc` run at the same
cells. The event-loop numbers reproduce v1 within error (v1: 237.0 / 279.7 / 334.1 ADAPTIVE,
322.1 / 355.2 / 460.7 FFM_GLIBC), so the new cache did not disturb the FastThreadLocal path.

Platform t32 with the wrappers back on the `Recycler`
(`-Dffm.platformCache=false -Dio.netty.recycler.batchFastThreadLocalOnly=false`), 3 forks:
FFM_GLIBC 355.1 / 398.2 / 495.8 ns/op at **15523 / 15698 / 16205 MiB** RSS and gc.count 3/3/2;
FFM_MI 298.2 / 306.3 / 396.1 ns/op at **15704 / 15932 / 16480 MiB** and gc.count 4/3/3.

### Why the shared-pool `Recycler` cost 12 us/op (measured, platform t32, 1024 live, 1 fork)

perfnorm: 12398 ns/op, **11480 cycles/op with only 1060 instructions/op — IPC 0.092**, CPI 10.83,
L1-dcache-load-misses **56.1/op** (0.1 in the healthy platform run), branch-misses 6.1/op,
stalled-cycles-frontend 158/op.

perfasm (`event=cycles`): 95.11% of cycles in C2 code, 2.31% in `libc.so.6`. `allocateDirect` alone is
89.79%, and **one instruction carries 65.89% of all cycles**:

```
      0x...4278:  mov  0x1a0(%rcx),%r11   ;*getfield consumerIndex
                    ; - MpmcArrayQueueConsumerIndexField::lvConsumerIndex@1 (line 112)
                    ; - MpmcArrayQueue::relaxedPoll@11 (line 357)
65.89% 0x...427f:  mov  %rdx,%r8          ;*invokevirtual getLongVolatile
                    ; - UnsafeLongArrayAccess::lvLongElement@5 (line 83)
                    ; - MpmcArrayQueue::relaxedPoll@27 (line 359)
                    ; - Recycler$LocalPool::acquire@21 (line 594)
                    ; - Recycler$UnguardedLocalPool::getWith@1 (line 520)
                    ; - Recycler::get@12 (line 327)
                    ; - FfmMallocByteBufAllocator::newDirectBuffer@82 (line 214)
```

The cycles are on the volatile read of the shared `MpmcArrayQueue`'s consumer index / element slot inside
`Recycler.get()`, with 56 L1-dcache misses per op. I am not asserting a mechanism beyond that: what is
measured is where the cycles land, how few instructions retire, and how many L1d misses there are.

---

# v3: why FFM_MI wins on platform threads and ties on event loops

All of this section: **1 fork**, `MAX_LIVE_BUFFERS=1024`, `E_COMMERCE`, `enableReadWrite=true`, **32 threads**,
`numactl --cpunodebind=0` (16 logical CPUs, so 2x oversubscribed), 2300 MHz, same JVM and flags for all
three allocators. Script: `netty-bench/results/h2h-merged-x86-2026-09-22/ffm/prof/run-why.sh`,
raw output next to it and copied into this module's `results/`.

One-fork scores are noisy (the 99.9% CI is ±30-90 ns/op); the *scores* to trust are the 3-fork v2 tables
above. What this section is for is the counters and the profiles, which are far less noisy than the score.

## 1. perfnorm: the platform-thread penalty is instructions, and FFM_MI does not pay it

| | executor | ADAPTIVE | MIMALLOC | FFM_MI |
| --- | --- | ---: | ---: | ---: |
| ns/op | platform | 487.1 ±54.5 | 354.6 ±55.0 | 281.5 ±75.6 |
| ns/op | event loop | 272.1 ±30.6 | 272.3 ±38.3 | 271.6 ±40.1 |
| **instructions/op** | **platform** | **851.7** | **748.2** | **512.8** |
| **instructions/op** | **event loop** | **558.3** | **505.0** | **514.6** |
| cycles/op | platform | 491.9 | 363.7 | 277.9 |
| cycles/op | event loop | 283.5 | 273.1 | 267.0 |
| IPC | platform | 1.731 | 2.057 | 1.845 |
| IPC | event loop | 1.969 | 1.849 | 1.927 |
| L1d loads/op | platform | 331.9 | 288.2 | 197.2 |
| L1d loads/op | event loop | 235.1 | 215.6 | 196.4 |
| L1d load-misses/op | platform | 14.88 | 7.58 | 10.11 |
| L1d load-misses/op | event loop | 9.49 | 7.19 | 9.89 |
| branches/op | platform | 161.2 | 137.3 | 102.9 |
| branch-misses/op | platform | 1.06 | 1.13 | 0.80 |
| branch-misses/op | event loop | 0.49 | 0.68 | 0.78 |
| dTLB-loads/op | platform | 8.51 | 3.55 | 1.76 |
| dTLB-load-misses/op | platform | 0.106 | 0.068 | 0.055 |
| stalled-cycles-frontend/op | platform | 17.31 | 14.14 | 15.98 |

Going from the event-loop executor to platform threads, at the same 32 threads and the same live set:

| | ADAPTIVE | MIMALLOC | FFM_MI |
| --- | ---: | ---: | ---: |
| Δ instructions/op | **+293.4 (+52.6%)** | **+243.2 (+48.2%)** | **-1.8 (-0.3%)** |
| Δ cycles/op | +208.4 (+73.5%) | +90.6 (+33.2%) | +10.9 (+4.1%) |

That is the whole shape of the result: **FFM_MI executes the same number of instructions per operation on a
platform thread as on an event loop; the two Java allocators execute about 250-300 more.** It is not that
FFM_MI got faster on platform threads — it did not change — it is that the other two got slower.

Counters that are *not* the explanation: IPC moves little and in no consistent direction, L1d misses are
lowest for MIMALLOC (7.6/op) which is nevertheless slower than FFM_MI (10.1/op), branch-misses are around
1/op everywhere, and front-end stalls are 14-17/op for all three. Nothing here says "cache" or
"speculation"; the extra time tracks the extra instructions.

(JMH's perfnorm event set on this box does not include LLC or `cache-misses` counters; they were asked for
explicitly in a second run, see below.)

## 2. perfasm (`event=cycles`, never `cycles:P`): the extra instructions are a StampedLock

Distribution by source, platform executor:

| source | ADAPTIVE | MIMALLOC | FFM_MI |
| --- | ---: | ---: | ---: |
| C2-compiled Java | 96.47% | 96.84% | 67.72% |
| `libmimalloc.so.3.5` | - | - | 24.97% |
| unresolved DSO | 0.23% | 0.12% | 3.78% |
| `[kernel.kallsyms]` | 2.12% | 1.87% | 2.31% |
| `libjvm.so` | 0.63% | 0.57% | 0.29% |
| `libc.so.6` | 0.42% | 0.49% | 0.43% |

Hottest methods after inlining, platform executor:

| ADAPTIVE | | MIMALLOC | | FFM_MI | |
| --- | ---: | --- | ---: | --- | ---: |
| `AdaptivePoolingAllocator::allocateShared` | 38.43% | `MiMallocByteBufAllocator::allocate` | 31.35% | harness `readAndRelease` | 25.48% |
| `AbstractReferenceCountedByteBuf::handleRelease` | 26.63% | `AbstractReferenceCountedByteBuf::release` | 28.01% | harness `allocateDirect` | 24.03% |
| harness `readAndRelease` | 10.71% | `MiMallocByteBufAllocator::allocateGeneric` | 9.33% | harness `performDirectAllocation` | 13.61% |
| harness `performDirectAllocation` | 9.09% | harness `performDirectAllocation` | 9.17% | `mi_malloc` | 6.56% |
| harness `allocateDirect` | 6.14% | harness `readAndRelease` | 8.86% | `operator delete[]` (= `mi_free`) | 3.73% |
| `SizeClassMagazine::allocateSlow` | 1.20% | `ArrayDeque::inc` | 2.31% | `_mi_malloc_generic` | 3.25% |

`StampedLock` frames in the perfasm annotation, by run:

| run | lines mentioning `StampedLock` | score |
| --- | ---: | ---: |
| platform ADAPTIVE | 52 | 405.6 ns/op |
| platform MIMALLOC | 101 | 347.7 ns/op |
| platform FFM_MI | **0** | 335.0 ns/op |
| event loop ADAPTIVE | **0** | 280.0 ns/op |
| event loop MIMALLOC | **0** | 269.1 ns/op |
| event loop FFM_MI | **0** | 379.5 ns/op (±161, perfasm overhead) |

Attributing each instruction perfasm printed to the classes in its inline chain (ADAPTIVE, platform). The
three hottest regions carry 74.9% of all cycles; the individual instructions printed inside them sum to
59.3%, of which 32.0% sit on instructions with no inline annotation and are left unattributed, so the
percentages below are lower bounds:

```
  15.58%  io.netty.buffer.AdaptivePoolingAllocator
  14.93%  io.netty.buffer.AdaptivePoolingAllocator$StripedHeap
  12.97%  java.util.concurrent.locks.StampedLock
  11.81%  io.netty.buffer.AbstractReferenceCountedByteBuf
   9.48%  io.netty.buffer.AdaptivePoolingAllocator$SizeClassedChunk
   5.97%  io.netty.buffer.AdaptivePoolingAllocator$SizeClassMagazine
   2.00%  io.netty.util.Recycler
```

The single hottest instruction of the whole ADAPTIVE platform run, 5.46% of all cycles, is the load right
after the lock release inside `StripedHeap.tryAllocate`:

```
   0.11%   lock addl $0x0,-0x40(%rsp)     ;*putfield state
                                          ; - StampedLock::releaseWrite@6 (line 462)
                                          ; - StampedLock::unlockWrite@29 (line 656)
                                          ; - AdaptivePoolingAllocator$StripedHeap::tryAllocate@75 (line 739)
                                          ; - AdaptivePoolingAllocator::allocateShared@82 (line 331)
   5.46%   mov  0x18(%r12,%r10,8),%ebp    ;*getfield head
                                          ; - StampedLock::releaseWrite@11 (line 463)
                                          ; - StampedLock::unlockWrite@29 (line 656)
                                          ; - AdaptivePoolingAllocator$StripedHeap::tryAllocate@75 (line 739)
                                          ; - AdaptivePoolingAllocator::allocateShared@82 (line 331)
```

The Java mimalloc port takes its write lock on the **release** side instead:

```
   ; - StampedLock::unlockWriteState@4 (line 458)
   ; - StampedLock::releaseWrite@2 (line 462)
   ; - StampedLock::unlockWrite@29 (line 656)
   ; - MiMallocByteBufAllocator::free@107 (line 2041)
   ; - MiMallocByteBufAllocator$MiByteBuf::deallocate@52 (line 2477)
   ; - AbstractReferenceCountedByteBuf::handleRelease@5 (line 93)
```

On the event-loop executor neither profile contains a single `StampedLock` sample, and ADAPTIVE's hottest
method changes from `allocateShared` (38.43%) to `AdaptiveByteBufAllocator::newDirectBuffer` (33.45%), with
`FastThreadLocal` / `InternalThreadLocalMap` frames appearing in the inline chains instead.

What this shows, and only this: **on platform threads both Java allocators run a `StampedLock`
acquire/release in the hot path and FFM_MI runs none; on event loops none of the three does.** I am not
claiming that the lock is the *only* difference, nor that it is uncontended or contended — what is measured
is which frames the cycles land in.

## 3. async-profiler (`cpu`, 1 ms, platform t32): self time by bucket

Leaf-frame (self) time, one fork each, ~82000 samples per allocator:

| bucket (leaf frame) | ADAPTIVE | MIMALLOC | FFM_MI |
| --- | ---: | ---: | ---: |
| `java.util.concurrent.locks.StampedLock` | **16.98%** | **13.71%** | **0.00%** |
| the allocator's own Java code | 28.21% (`AdaptivePoolingAllocator*`) | 34.66% (`MiMallocByteBufAllocator*`) | 11.67% (`Ffm*`) |
| native allocator code (`mi_*`, `__libc_*` aliases) | 0.00% | 0.00% | **36.30%** |
| FFM downcall stub (`nep_invoker_blob`) | - | - | 1.40% |
| `Recycler` / jctools queues | 8.60% | 0.07% | 0.00% |
| `RefCnt` / `AbstractReferenceCountedByteBuf` | 3.06% | 0.52% | 1.36% |
| `Unsafe` / `UnsafeByteBufUtil` accessors | 21.32% | 16.89% | 11.05% |
| harness + JMH | 15.60% | 20.18% | 31.46% |
| everything else | 6.23% | 13.98% | 6.75% |
| samples | 82636 | 82161 | 82292 |

"everything else" is per-allocator bookkeeping that did not fit a bucket, and is worth one line each:
ADAPTIVE spends 2.15% in `FastThreadLocalThread.currentThreadWillCleanupFastThreadLocals` +
`isFastThreadLocalVirtualThread` + `FallbackThreadSet.contains`, i.e. in deciding what kind of thread this
is; FFM_MI spends 1.42% in `ThreadLocal$ThreadLocalMap.getEntry`/`ThreadLocal.get` (its platform-thread
cache lookup) and 1.15% in `LambdaForm$MH.invoke` on the way to the downcall; MIMALLOC spends 4.61% in
`java.util.ArrayDeque` (`pollFirst`/`addFirst`/`elementAt`) and 2.32% in `AtomicInteger.get` +
`AtomicLong.get`.

Everything that is not Java, by leaf frame: **ADAPTIVE 0.24%, MIMALLOC 0.15%, FFM_MI 38.05%.** The two Java
allocators do all their work in Java; FFM_MI does a bit more than a third of its work inside libmimalloc.

Top self-time frames:

| ADAPTIVE | | MIMALLOC | | FFM_MI | |
| --- | ---: | --- | ---: | --- | ---: |
| harness `readAndRelease` | 6.67% | `MiMallocByteBufAllocator.allocate` | 11.49% | harness `readAndRelease` | 13.53% |
| `Unsafe.putByte` | 6.60% | harness `readAndRelease` | 6.91% | `__libc_malloc` (= `mi_malloc`) | 10.52% |
| `StampedLock.casState` | 6.51% | `UnsafeByteBufUtil.getInt` | 5.70% | harness `performDirectAllocation` | 6.23% |
| `Unsafe.putOrderedInt` | 6.17% | harness `performDirectAllocation` | 5.60% | `Unsafe.getInt` | 5.76% |
| `StampedLock.releaseWrite` | 4.41% | `StampedLock.casState` | 4.98% | `_mi_malloc_generic` | 5.18% |
| `StripedHeap.tryAllocate` | 4.03% | `Unsafe.putInt` | 4.38% | `__libc_cfree` (= `mi_free`) | 4.67% |
| `StampedLock.tryAcquireWrite` | 3.98% | `StampedLock.releaseWrite` | 4.08% | `FfmByteBuf.init` | 4.00% |
| `AdaptiveByteBuf.init` | 2.99% | `StampedLock.tryAcquireWrite` | 2.71% | `FfmByteBuf.deallocate` | 2.43% |
| `Recycler.get` | 2.72% | `MiByteBuf.init` | 2.29% | `LocalCache.pop` | 2.41% |

**`__libc_malloc` and `__libc_cfree` in the FFM_MI column are mimalloc, not glibc.** Verified from the
symbol table, not assumed: in `libmimalloc.so.3.5`, `nm -D` gives `mi_malloc` and `__libc_malloc` the same
address `0x29a0`, and `mi_free`, `__libc_free` and `__libc_cfree` all resolve to `0x19c0`. perfasm names
those addresses `mi_malloc` / `operator delete[]`, async-profiler names them `__libc_malloc` /
`__libc_cfree`; it is one function with several exported names. perfasm's own per-DSO split for the same
cell puts `libc.so.6` at 0.43% and `libmimalloc.so.3.5` at 24.97%.

The two profilers do **not** agree on how much of the time is native: perfasm (cycles) says 24.97%,
async-profiler (cpu samples) says 38.05%. I did not chase that difference down; the qualitative split
(ADAPTIVE/MIMALLOC ~0% native, FFM_MI a large minority) is the same in both.

The FFM downcall itself is 1.40% of self time in `nep_invoker_blob`, the native-entry-point invoker stub.
That is the whole visible cost of going through FFM here, consistent with §2 of the v1 results, where the
stub is force-inlined into the caller and only `MethodHandle::linkToNative` — the call instruction —
remains.

## 4. What this does and does not answer

Measured:

* the platform-thread gap is **+293 instructions/op for ADAPTIVE and +243 for MIMALLOC, +0 for FFM_MI**,
  relative to the same allocator on an event loop;
* on platform threads, `StampedLock` frames carry 16.98% (ADAPTIVE) and 13.71% (MIMALLOC) of self time and
  12.97% (ADAPTIVE) of annotated perfasm cycles; FFM_MI has none;
* on event loops, no allocator has a `StampedLock` frame and all three land at the same 272 ns/op;
* FFM_MI spends 25% (perfasm) to 38% (async-profiler) of its time inside libmimalloc, and 1.4% in the FFM
  downcall stub.

Not measured, so not claimed: whether the `StampedLock` is contended or merely executed; whether the
remaining instruction delta after the lock is thread-local-cache bookkeeping; whether a per-platform-thread
magazine would close the gap for ADAPTIVE. The v1 §3 result that FFM's own platform-thread path needed a
real per-thread cache (v2) shows the same class of problem from the other side.

---

# Which build produced which table

* **v1** and **v2** were measured with the standalone prototype jar (`netty-ffm-allocator`), before the
  sources were moved into this module.
* **v3** (the profile section) was measured with `ffm-allocator/target/ffm-allocator-1.2.4.Final-SNAPSHOT.jar`,
  i.e. the code in this module, against the same harness jar.

Between the two, three things changed in the allocator, none of them on the hot path:

1. overflow of the owner stack or of the hand-back queue now hands the wrapper to the `Recycler` instead of
   dropping it to GC;
2. the recycling counters are only maintained with `-Dffm.metrics=true` (they are `LongAdder`s, and the
   foreign-release path used to increment one unconditionally); the reported runs never enable them, and in
   the reported cells (1024 live buffers per thread, 4096-deep stack) neither overflow path is reached;
3. `isContiguous()` returns `true`, `libmimalloc.so` is resolved through a property/search path instead of a
   hardcoded one, and the error messages carry the back end.

The alloc/release instruction sequence (`malloc` downcall, `LocalCache.pop`, `init`, `deallocate`, `free`
downcall, `LocalCache.push`) is unchanged, and the v3 event-loop scores (272 ns/op at 1024 live, 32 threads)
land on top of the v2 3-fork numbers (260.3 ns/op) within the 1-fork error bars.

# Raw data

Under `results/` in this module, and in
`netty-bench/results/h2h-merged-x86-2026-09-22/ffm/` (v1, v2) and `.../ffm/prof/` (v3):

| file | what |
| --- | --- |
| `run-why.sh`, `run-why2.sh` | the v3 scripts, including the wait-for-idle loop |
| `perfnorm-platform.txt`, `perfnorm-eventloop.txt` | JMH perfnorm, 3 allocators x 2 executors |
| `perfasm-{platform,eventloop}-{ADAPTIVE,MIMALLOC,FFM_MI}.txt` | perfasm, `event=cycles` |
| `async-{ADAPTIVE,MIMALLOC,FFM_MI}/**/collapsed-cpu.csv` | async-profiler `cpu`, 1 ms, collapsed |
| `gcfree-verify*.txt`, `roundtrip.json`, `*-t32*.json` | the v1/v2 verifier, round trip and score runs |

# References

* [JEP 454: Foreign Function & Memory API](https://openjdk.org/jeps/454)
* [`Linker.Option.critical(boolean)`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/Linker.Option.html#critical(boolean)) -
  "the linker may adopt a more efficient... the downcall method handle... no transition"
* [microsoft/mimalloc](https://github.com/microsoft/mimalloc);
  [mimalloc: Free List Sharding in Action](https://www.microsoft.com/en-us/research/publication/mimalloc-free-list-sharding-in-action/)
* the Java port measured as `MIMALLOC` here: [neoionet/netty-allocator](https://github.com/neoionet/netty-allocator)
* Netty [PR #17151](https://github.com/netty/netty/pull/17151), the `AdaptiveByteBufAllocator` work measured
  as `ADAPTIVE`
* [JEP 498: Warn upon Use of Memory-Access Methods in sun.misc.Unsafe](https://openjdk.org/jeps/498), the
  background for `--sun-misc-unsafe-memory-access=allow`
