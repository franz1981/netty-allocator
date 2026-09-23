/*
 * Experimental: a Netty ByteBufAllocator whose direct buffers are plain malloc'd memory reached
 * through the Java FFM API with critical linking.
 */
package io.netty.buffer;

import io.netty.util.Recycler;
import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.concurrent.FastThreadLocalThread;
import io.netty.util.internal.ObjectPool.Handle;

import io.netty.util.internal.PlatformDependent;

import java.util.Queue;
import java.util.concurrent.atomic.LongAdder;

/**
 * A {@link ByteBufAllocator} that serves direct buffers from {@code malloc}/{@code free} called through the
 * FFM API ({@link java.lang.foreign.Linker.Option#critical(boolean) critical} downcalls, pointers as
 * {@code long}). Heap buffers are delegated to {@link UnpooledByteBufAllocator} and are out of scope.
 *
 * <p>The wrapper objects are recycled:
 * <ul>
 *   <li>On a {@link FastThreadLocalThread}, a plain array stack owned by that thread (no atomics, bounded by
 *       {@code -Dffm.localCacheSize}, default 4096).</li>
 *   <li>If a buffer is released on a thread other than the one that allocated it, or on a platform thread,
 *       Netty's {@link Recycler} (MPSC queue) is used instead.</li>
 * </ul>
 * {@code -Dffm.localCache=false} disables the array stack so that everything goes through the
 * {@link Recycler}; that is the knob used to measure the two paths against each other.
 *
 * <p>System properties, all read once at class-initialization time:
 * <table>
 *   <caption>knobs</caption>
 *   <tr><th>property</th><th>default</th><th>effect</th></tr>
 *   <tr><td>{@code ffm.mimalloc.lib}</td><td>-</td><td>full path of {@code libmimalloc.so}</td></tr>
 *   <tr><td>{@code ffm.mimalloc.dir}</td><td>-</td><td>directory holding {@code libmimalloc.so}</td></tr>
 *   <tr><td>{@code ffm.localCache}</td><td>{@code true}</td><td>{@code false} sends every wrapper through the
 *       {@link Recycler}</td></tr>
 *   <tr><td>{@code ffm.localCacheSize}</td><td>4096</td><td>owner-only array stack size, per thread</td></tr>
 *   <tr><td>{@code ffm.platformCache}</td><td>{@code true}</td><td>{@code false} gives platform threads no
 *       per-thread cache</td></tr>
 *   <tr><td>{@code ffm.handBackCapacity}</td><td>4096</td><td>bounded MPSC hand-back queue, per thread</td></tr>
 *   <tr><td>{@code ffm.platformRecycler}</td><td>{@code default}</td><td>{@code shared} uses a second
 *       {@link Recycler} built with the shared-pool constructor for platform threads</td></tr>
 *   <tr><td>{@code ffm.sharedRecyclerCapacity}</td><td>65536</td><td>capacity of that shared Recycler</td></tr>
 *   <tr><td>{@code ffm.metrics}</td><td>{@code false}</td><td>maintain the counters read by {@link #stats()}</td></tr>
 * </table>
 *
 * <p>Required JVM flags: {@code --enable-native-access=ALL-UNNAMED} (the FFM downcall handles and
 * {@code MemorySegment.reinterpret} are restricted methods) and, on Java 25+,
 * {@code --sun-misc-unsafe-memory-access=allow}: {@code PlatformDependent0.explicitNoUnsafeCause0()} sets
 * {@code noUnsafe=true} by default on Java 25+ unless {@code io.netty.noUnsafe} is set explicitly or that flag
 * is passed, and without {@code Unsafe} every {@link UnsafeByteBufUtil} accessor used here fails.
 *
 * <p>This class lives in {@code io.netty.buffer} on purpose: it uses the package-private
 * {@link UnsafeByteBufUtil} accessors and {@link AbstractByteBuf} internals. On the classpath this is a split
 * package with {@code netty-buffer.jar}, which is fine for an experiment but would not be for a module.
 */
public final class FfmMallocByteBufAllocator extends AbstractByteBufAllocator {

    public enum Backend {
        /** {@code malloc}/{@code free}/{@code realloc} from the default lookup (glibc, or whatever is LD_PRELOADed). */
        GLIBC,
        /** {@code mi_malloc}/{@code mi_free}/{@code mi_realloc} from libmimalloc.so. */
        MIMALLOC
    }

    static final boolean LOCAL_CACHE_ENABLED = !"false".equals(System.getProperty("ffm.localCache"));
    static final int LOCAL_CACHE_SIZE = Integer.getInteger("ffm.localCacheSize", 4096);
    static final boolean METRICS = Boolean.getBoolean("ffm.metrics");
    /**
     * Netty's plain {@link Recycler} does not pool at all on a platform thread: {@code Recycler.get()}
     * returns {@code newObject(NOOP_HANDLE)} when
     * {@code FastThreadLocalThread.currentThreadWillCleanupFastThreadLocals()} is false. With
     * {@code -Dffm.platformRecycler=shared} the wrappers handed out on platform threads come from a
     * second {@link Recycler} built with the shared-pool constructor instead, which pools on any thread.
     */
    static final boolean SHARED_PLATFORM_RECYCLER = "shared".equals(System.getProperty("ffm.platformRecycler"));
    static final int SHARED_RECYCLER_CAPACITY = Integer.getInteger("ffm.sharedRecyclerCapacity", 64 * 1024);
    /**
     * With {@code -Dffm.platformCache=false} a platform thread gets no per-thread cache and falls back to
     * the {@link Recycler}, which is the configuration measured against
     * {@code -Dio.netty.recycler.batchFastThreadLocalOnly=false}.
     */
    static final boolean PLATFORM_CACHE_ENABLED = !"false".equals(System.getProperty("ffm.platformCache"));
    /** Bounded MPSC hand-back queue per owner thread, for releases that happen on a foreign thread. */
    static final int HAND_BACK_CAPACITY = Integer.getInteger("ffm.handBackCapacity", 4096);
    /** How many wrappers to move from the hand-back queue into the owner stack in one go. */
    private static final int DRAIN_BATCH = 64;

    private static final LongAdder WRAPPERS_CREATED = new LongAdder();
    private static final LongAdder LOCAL_HITS = new LongAdder();
    private static final LongAdder RECYCLER_HITS = new LongAdder();
    private static final LongAdder HAND_BACK_OFFERED = new LongAdder();
    private static final LongAdder HAND_BACK_OVERFLOW = new LongAdder();
    private static final LongAdder STACK_OVERFLOW = new LongAdder();
    private static final LongAdder HAND_BACK_DRAINED = new LongAdder();

    private static final Recycler<FfmByteBuf> RECYCLER = new Recycler<FfmByteBuf>() {
        @SuppressWarnings("unchecked")
        @Override
        protected FfmByteBuf newObject(Handle<FfmByteBuf> handle) {
            WRAPPERS_CREATED.increment();
            return new FfmByteBuf((Recycler.EnhancedHandle<FfmByteBuf>) handle);
        }
    };

    private static final Recycler<FfmByteBuf> PLATFORM_RECYCLER =
            new Recycler<FfmByteBuf>(SHARED_RECYCLER_CAPACITY, true) {
                @SuppressWarnings("unchecked")
                @Override
                protected FfmByteBuf newObject(Handle<FfmByteBuf> handle) {
                    WRAPPERS_CREATED.increment();
                    return new FfmByteBuf((Recycler.EnhancedHandle<FfmByteBuf>) handle);
                }
            };

    /**
     * Per-thread wrapper cache: an owner-only array stack (no atomics) plus a bounded MPSC queue that
     * foreign threads hand released wrappers back through. Used for {@link FastThreadLocalThread}s and,
     * with the same code, for platform threads.
     */
    static final class LocalCache {
        final Thread owner;
        private final FfmByteBuf[] stack;
        private int size;
        private final Queue<FfmByteBuf> handBack;

        LocalCache(Thread owner, int capacity) {
            this.owner = owner;
            this.stack = new FfmByteBuf[capacity];
            this.handBack = PlatformDependent.newFixedMpscQueue(HAND_BACK_CAPACITY);
        }

        /** Owner thread only. */
        boolean push(FfmByteBuf buf) {
            int s = size;
            if (s == stack.length) {
                return false;
            }
            stack[s] = buf;
            size = s + 1;
            return true;
        }

        /** Owner thread only. */
        FfmByteBuf pop() {
            int s = size - 1;
            if (s < 0) {
                return drainAndPop();
            }
            FfmByteBuf buf = stack[s];
            stack[s] = null;
            size = s;
            return buf;
        }

        /** Owner thread only: refill the stack from the hand-back queue. */
        private FfmByteBuf drainAndPop() {
            FfmByteBuf first = handBack.poll();
            if (first == null) {
                return null;
            }
            int drained = 1;
            int room = Math.min(DRAIN_BATCH, stack.length);
            for (int i = 0; i < room; i++) {
                FfmByteBuf b = handBack.poll();
                if (b == null) {
                    break;
                }
                stack[size++] = b;
                drained++;
            }
            if (METRICS) {
                HAND_BACK_DRAINED.add(drained);
            }
            return first;
        }

        /** Any thread but the owner. */
        boolean offer(FfmByteBuf buf) {
            return handBack.offer(buf);
        }
    }

    private static final FastThreadLocal<LocalCache> CACHE = new FastThreadLocal<LocalCache>() {
        @Override
        protected LocalCache initialValue() {
            return new LocalCache(Thread.currentThread(), LOCAL_CACHE_SIZE);
        }
    };

    /** The same cache for platform threads, behind a plain {@link ThreadLocal}. */
    private static final ThreadLocal<LocalCache> PLATFORM_CACHE = ThreadLocal.withInitial(
            () -> new LocalCache(Thread.currentThread(), LOCAL_CACHE_SIZE));

    private final Backend backend;
    private final FfmNative.Binding nat;
    private final LongAdder usedDirectMemory = new LongAdder();

    public FfmMallocByteBufAllocator() {
        this(Backend.GLIBC);
    }

    public FfmMallocByteBufAllocator(String backend) {
        this(Backend.valueOf(backend));
    }

    public FfmMallocByteBufAllocator(Backend backend) {
        super(true);
        if (!io.netty.util.internal.PlatformDependent.hasUnsafe()) {
            // The accessors go through UnsafeByteBufUtil on a raw address; there is no VarHandle fallback
            // for that. On Java 25 Netty defaults to io.netty.noUnsafe=true, so the JVM must be started
            // with --sun-misc-unsafe-memory-access=allow (or -Dio.netty.noUnsafe=false).
            throw new IllegalStateException("FfmMallocByteBufAllocator needs PlatformDependent.hasUnsafe(); "
                    + "start the JVM with --sun-misc-unsafe-memory-access=allow. Cause: "
                    + io.netty.util.internal.PlatformDependent.getUnsafeUnavailabilityCause());
        }
        this.backend = backend;
        this.nat = backend == Backend.MIMALLOC ? FfmNative.Mi.INSTANCE : FfmNative.Libc.INSTANCE;
    }

    @Override
    protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
        // Out of scope: heap buffers are delegated to the unpooled allocator.
        return UnpooledByteBufAllocator.DEFAULT.heapBuffer(initialCapacity, maxCapacity);
    }

    @Override
    protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
        FfmNative.Binding nat = this.nat;
        // malloc first: if it fails, no wrapper has been taken out of the cache yet.
        long address = nat.malloc(initialCapacity == 0 ? 1 : initialCapacity);
        if (address == 0) {
            throw new OutOfMemoryError(backend + " malloc(" + initialCapacity + ") returned NULL");
        }
        LocalCache cache = localCache();
        FfmByteBuf buf = cache != null ? cache.pop() : null;
        if (buf == null) {
            buf = cache == null && SHARED_PLATFORM_RECYCLER ? PLATFORM_RECYCLER.get() : RECYCLER.get();
            if (METRICS) {
                RECYCLER_HITS.increment();
            }
        } else if (METRICS) {
            LOCAL_HITS.increment();
        }
        buf.init(this, nat, cache, address, initialCapacity, maxCapacity);
        if (METRICS) {
            usedDirectMemory.add(initialCapacity);
        }
        return toLeakAwareBuffer(buf);
    }

    private static LocalCache localCache() {
        if (!LOCAL_CACHE_ENABLED) {
            return null;
        }
        if (Thread.currentThread() instanceof FastThreadLocalThread) {
            return CACHE.get();
        }
        return PLATFORM_CACHE_ENABLED ? PLATFORM_CACHE.get() : null;
    }

    /**
     * Called from {@link FfmByteBuf#deallocate()} once the native block is already freed.
     * Owner-thread releases land in the plain array stack; everything else goes to the {@link Recycler}.
     */
    void recycleWrapper(FfmByteBuf buf, LocalCache cache) {
        if (cache == null) {
            buf.recycleToRecycler();
            return;
        }
        if (cache.owner == Thread.currentThread()) {
            if (cache.push(buf)) {
                return;
            }
            // Owner stack full: keep the wrapper pooled through the Recycler rather than dropping it.
            if (METRICS) {
                STACK_OVERFLOW.increment();
            }
            buf.recycleToRecycler();
            return;
        }
        // Released on a foreign thread: hand it back to the owner, or, if that queue is full, put it in the
        // Recycler. Either way the native block is already freed, so neither branch leaks memory.
        if (cache.offer(buf)) {
            if (METRICS) {
                HAND_BACK_OFFERED.increment();
            }
            return;
        }
        if (METRICS) {
            HAND_BACK_OVERFLOW.increment();
        }
        buf.recycleToRecycler();
    }

    @Override
    public boolean isDirectBufferPooled() {
        return false;
    }

    public Backend backend() {
        return backend;
    }

    public long usedDirectMemory() {
        return usedDirectMemory.sum();
    }

    public long usedHeapMemory() {
        return 0;
    }

    /**
     * Wrapper-recycling counters. Everything but {@code wrappersCreated} is only counted with
     * {@code -Dffm.metrics=true}: the counters are {@link LongAdder}s and the release path must not pay for
     * them when they are not being read.
     */
    public static String stats() {
        return "FFM wrappersCreated=" + WRAPPERS_CREATED.sum()
                + " localStackHits=" + LOCAL_HITS.sum()
                + " recyclerGets=" + RECYCLER_HITS.sum()
                + " handBackOffered=" + HAND_BACK_OFFERED.sum()
                + " handBackDrained=" + HAND_BACK_DRAINED.sum()
                + " handBackOverflowToRecycler=" + HAND_BACK_OVERFLOW.sum()
                + " ownerStackOverflowToRecycler=" + STACK_OVERFLOW.sum();
    }

    public static long handBackOffered() {
        return HAND_BACK_OFFERED.sum();
    }

    public static long handBackDrained() {
        return HAND_BACK_DRAINED.sum();
    }

    public static long handBackOverflowToRecycler() {
        return HAND_BACK_OVERFLOW.sum();
    }

    public static long ownerStackOverflowToRecycler() {
        return STACK_OVERFLOW.sum();
    }

    /** Where {@code libmimalloc.so} was resolved from, without loading it. */
    public static String mimallocLibraryPath() {
        return FfmNative.mimallocLibrary().toString();
    }

    public static long wrappersCreated() {
        return WRAPPERS_CREATED.sum();
    }

    @Override
    public String toString() {
        return "FfmMallocByteBufAllocator(" + backend + ", " + nat.describe()
                + ", localCache=" + (LOCAL_CACHE_ENABLED ? LOCAL_CACHE_SIZE : "off")
                + ", platformCache=" + (PLATFORM_CACHE_ENABLED ? LOCAL_CACHE_SIZE + "+mpsc" + HAND_BACK_CAPACITY : "off")
                + ", platformRecycler=" + (SHARED_PLATFORM_RECYCLER ? "shared" : "default") + ')';
    }
}
