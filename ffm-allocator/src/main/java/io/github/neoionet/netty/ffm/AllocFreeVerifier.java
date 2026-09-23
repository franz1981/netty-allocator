package io.github.neoionet.netty.ffm;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.FfmMallocByteBufAllocator;
import io.netty.util.concurrent.FastThreadLocalThread;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * Steady-state alloc/release loop used to verify that the hot path allocates nothing on the Java heap.
 *
 * <p>Reports bytes allocated by the worker thread ({@code com.sun.management.ThreadMXBean#getThreadAllocatedBytes})
 * and the JVM's GC counts around the measured window. Run with {@code -XX:+UnlockDiagnosticVMOptions
 * -XX:+PrintInlining} to see whether the downcall stub is inlined into the allocation path.
 *
 * <p>Usage: {@code AllocFreeVerifier <GLIBC|MIMALLOC> <liveBuffers> <ops> [platform]}
 */
public final class AllocFreeVerifier {

    public static void main(String[] args) throws Exception {
        String backend = args.length > 0 ? args[0] : "GLIBC";
        int live = args.length > 1 ? Integer.parseInt(args[1]) : 1024;
        long ops = args.length > 2 ? Long.parseLong(args[2]) : 20_000_000L;
        boolean platform = args.length > 3 && "platform".equals(args[3]);

        FfmMallocByteBufAllocator allocator = new FfmMallocByteBufAllocator(backend);
        System.out.println(allocator);

        Runnable body = () -> run(allocator, live, ops);
        Thread t = platform ? new Thread(body, "worker-platform")
                            : new FastThreadLocalThread(body, "worker-ftlt");
        t.start();
        t.join();
    }

    private static void run(ByteBufAllocator allocator, int live, long ops) {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        com.sun.management.ThreadMXBean sun = (com.sun.management.ThreadMXBean) bean;
        long tid = Thread.currentThread().threadId();

        int[] sizes = new int[256];
        for (int i = 0; i < sizes.length; i++) {
            sizes[i] = 64 + ((i * 137) & 4095);
        }
        ByteBuf[] buffers = new ByteBuf[live];
        for (int i = 0; i < live; i++) {
            buffers[i] = allocator.directBuffer(sizes[i & (sizes.length - 1)]);
            buffers[i].writeByte(i);
        }

        // warm up
        loop(allocator, buffers, sizes, 5_000_000L);

        long gcBefore = gcCount();
        long allocBefore = sun.getThreadAllocatedBytes(tid);
        long wrappersBefore = FfmMallocByteBufAllocator.wrappersCreated();
        long start = System.nanoTime();
        loop(allocator, buffers, sizes, ops);
        long elapsed = System.nanoTime() - start;
        long allocAfter = sun.getThreadAllocatedBytes(tid);
        long gcAfter = gcCount();
        long wrappersAfter = FfmMallocByteBufAllocator.wrappersCreated();

        for (ByteBuf b : buffers) {
            if (b != null && b.refCnt() > 0) {
                b.release();
            }
        }
        System.out.printf("ops=%d ns/op=%.2f heapBytesAllocatedByThread=%d (%.4f B/op) youngAndOldGCs=%d wrappersCreated=%d%n",
                ops, (double) elapsed / ops, allocAfter - allocBefore,
                (double) (allocAfter - allocBefore) / ops, gcAfter - gcBefore, wrappersAfter - wrappersBefore);
        System.out.println(FfmMallocByteBufAllocator.stats());
    }

    private static void loop(ByteBufAllocator allocator, ByteBuf[] buffers, int[] sizes, long ops) {
        int mask = buffers.length - 1;
        int sizeMask = sizes.length - 1;
        int idx = 0;
        for (long i = 0; i < ops; i++) {
            int slot = (int) (i & mask);
            ByteBuf old = buffers[slot];
            if (old != null) {
                old.readByte();
                old.release();
            }
            int size = sizes[idx++ & sizeMask];
            ByteBuf b = allocator.directBuffer(size);
            b.writeByte(size);
            buffers[slot] = b;
        }
    }

    private static long gcCount() {
        long n = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            long c = b.getCollectionCount();
            if (c > 0) {
                n += c;
            }
        }
        return n;
    }

    private AllocFreeVerifier() {
    }
}
