package io.github.neoionet.netty.microbenchmark;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufAllocatorMetricProvider;
import io.netty.channel.EventLoop;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Where does the memory of a mixed burst go, and does it come back? Event loops AND platform threads allocate in the
 * same process, which no benchmark does (they run one kind of thread or the other).
 *
 * Phases:
 *   baseline  5 s idle
 *   burst     every thread allocates N buffers of each size class (32 B .. 132 KiB) and M buffers of each large size
 *             (192 KiB .. 1 MiB pooled, 2 MiB unpooled), in a random order, and holds them
 *   release   each thread releases half of its own buffers and half of the next thread's (event loops and platform
 *             threads interleaved, so releases cross between the two kinds), then 5 s idle
 *   traffic   every thread allocates and releases one buffer of 1..16 KiB per millisecond for TRAFFIC_S seconds
 * Every second: MEM <phase> <epoch_ms> <rss_kib> <jvm_direct_bytes> <jvm_direct_count> <allocator_used_direct>
 *
 * args: <adaptive|mimalloc> [eventLoops=8] [platformThreads=16] [perSizeClass=32] [perLargeSize=4] [trafficSeconds=120]
 *       [largeEvery=0] [silent|active] [secondBurstAtSeconds=0]
 *   largeEvery: 1 in largeEvery traffic allocations is a 256 KiB buffer (0: none)
 *   silent: the platform threads allocate nothing after the burst; the event loops keep their traffic
 *   secondBurstAtSeconds: a second burst and release this many seconds into the traffic, which then runs on
 * -Dtouch=true writes one byte per 4 KiB page of every buffer at allocation, so the RSS counts what an application
 * that fills its buffers would.
 */
public final class MixedMemoryTest {
    static final int[] SIZE_CLASSES = {32, 64, 128, 256, 512, 640, 1024, 1152, 2048, 2304, 4096, 4352, 8192, 8704,
            16384, 16896, 32768, 33792, 65536, 67584, 131072, 135168};
    static final int[] LARGE = {192 * 1024, 256 * 1024, 384 * 1024, 512 * 1024, 768 * 1024, 1024 * 1024,
            2 * 1024 * 1024};

    static volatile String phase = "start";
    /** -Dtouch=true: write one byte per 4 KiB page of every buffer at allocation. */
    static final boolean TOUCH = Boolean.getBoolean("touch");

    static ByteBuf touch(ByteBuf buf, int size) {
        if (TOUCH) {
            for (int i = 0; i < size; i += 4096) {
                buf.setByte(i, 1);
            }
        }
        return buf;
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1); // the workers are not daemons: never leave a cell hanging
        }
    }

    private static void run(String[] args) throws Exception {
        String kind = args[0];
        int eventLoops = args.length > 1 ? Integer.parseInt(args[1]) : 8;
        int platform = args.length > 2 ? Integer.parseInt(args[2]) : 16;
        int perClass = args.length > 3 ? Integer.parseInt(args[3]) : 32;
        int perLarge = args.length > 4 ? Integer.parseInt(args[4]) : 4;
        int trafficSeconds = args.length > 5 ? Integer.parseInt(args[5]) : 120;
        // 1 in largeEvery traffic allocations is a 256 KiB buffer (0: none): the rare large buffer of a proxy.
        final int largeEvery = args.length > 6 ? Integer.parseInt(args[6]) : 0;
        // "silent": the platform threads allocate nothing after the burst (their heaps go quiet); the event loops
        // keep their traffic.
        final boolean platformSilent = args.length > 7 && "silent".equals(args[7]);
        // A second burst and release this many seconds into the traffic (0: none); the traffic runs on.
        final int secondBurstAt = args.length > 8 ? Integer.parseInt(args[8]) : 0;

        final ByteBufAllocator alloc;
        if ("adaptive".equals(kind)) {
            alloc = new io.netty.buffer.AdaptiveByteBufAllocator(true);
        } else if ("mimalloc".equals(kind)) {
            alloc = new io.github.neoionet.netty.mimalloc.MiByteBufAllocator(true);
        } else {
            throw new IllegalArgumentException(kind);
        }

        // Workers: event loops first, then platform threads, each a single thread for its whole life.
        MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(eventLoops, NioIoHandler.newFactory());
        List<ScheduledExecutorService> workers = new ArrayList<ScheduledExecutorService>();
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < eventLoops; i++) {
            workers.add(group.next());
            names.add("eventloop");
        }
        for (int i = 0; i < platform; i++) {
            workers.add(Executors.newSingleThreadScheduledExecutor());
            names.add("platform");
        }
        int n = workers.size();

        Thread sampler = new Thread(() -> sample(alloc), "mem-sampler");
        sampler.setDaemon(true);
        sampler.start();

        phase = "baseline";
        Thread.sleep(5000);

        burstAndRelease(alloc, workers, perClass, perLarge, 0, "burst", "release");
        Thread.sleep(5000);

        // Light traffic: one small allocation per millisecond per worker.
        phase = "traffic";
        List<ScheduledFuture<?>> ticks = new ArrayList<ScheduledFuture<?>>();
        for (int w = 0; w < (platformSilent ? eventLoops : n); w++) {
            final SplittableRandom rng = new SplittableRandom(1000 + w);
            ticks.add(workers.get(w).scheduleAtFixedRate(() -> {
                int size = largeEvery > 0 && rng.nextInt(largeEvery) == 0 ? 256 * 1024 : 1024 + rng.nextInt(15 * 1024);
                touch(alloc.directBuffer(size, size), size).release();
            }, 1, 1, TimeUnit.MILLISECONDS));
        }
        long trafficStart = System.currentTimeMillis();
        if (secondBurstAt > 0) {
            long wait2 = trafficStart + secondBurstAt * 1000L - System.currentTimeMillis();
            if (wait2 > 0) {
                Thread.sleep(wait2);
            }
            burstAndRelease(alloc, workers, perClass, perLarge, 1000, "burst2", "release2");
            phase = "traffic2";
            trafficStart = System.currentTimeMillis();
        }
        long rest = trafficStart + trafficSeconds * 1000L - System.currentTimeMillis();
        if (rest > 0) {
            Thread.sleep(rest);
        }
        for (ScheduledFuture<?> t : ticks) {
            t.cancel(false);
        }
        phase = "end";
        Thread.sleep(2000);
        System.out.println("DONE " + kind + " workers=" + names);
        for (int w = eventLoops; w < n; w++) {
            ((ExecutorService) workers.get(w)).shutdown();
        }
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        System.exit(0);
    }

    /** One burst (every worker allocates its whole set and keeps it) and its release; returns when both are done. */
    private static void burstAndRelease(final ByteBufAllocator alloc, List<ScheduledExecutorService> workers,
                                        final int perClass, final int perLarge, int seedBase, String burstPhase,
                                        String releasePhase) throws Exception {
        int n = workers.size();
        // Burst: each worker allocates its whole set and keeps it.
        phase = burstPhase;
        List<Future<List<ByteBuf>>> allocated = new ArrayList<Future<List<ByteBuf>>>();
        for (int w = 0; w < n; w++) {
            final int seed = seedBase + w;
            allocated.add(workers.get(w).submit((Callable<List<ByteBuf>>) () -> {
                List<Integer> sizes = new ArrayList<Integer>();
                for (int size : SIZE_CLASSES) {
                    for (int i = 0; i < perClass; i++) {
                        sizes.add(size);
                    }
                }
                for (int size : LARGE) {
                    for (int i = 0; i < perLarge; i++) {
                        sizes.add(size);
                    }
                }
                Collections.shuffle(sizes, new java.util.Random(seed));
                List<ByteBuf> held = new ArrayList<ByteBuf>(sizes.size());
                for (int size : sizes) {
                    ByteBuf buf = touch(alloc.directBuffer(size, size), size);
                    buf.writerIndex(size); // touch nothing, only claim (unless -Dtouch=true)
                    held.add(buf);
                }
                return held;
            }));
        }
        List<List<ByteBuf>> held = new ArrayList<List<ByteBuf>>();
        for (Future<List<ByteBuf>> f : allocated) {
            held.add(f.get());
        }
        Thread.sleep(3000); // a few samples at the peak

        // Release: worker w releases the first half of its own set and the second half of worker (w + 1)'s.
        phase = releasePhase;
        List<Future<?>> released = new ArrayList<Future<?>>();
        for (int w = 0; w < n; w++) {
            final List<ByteBuf> own = held.get(w);
            final List<ByteBuf> next = held.get((w + 1) % n);
            released.add(workers.get(w).submit(() -> {
                for (int i = 0; i < own.size() / 2; i++) {
                    own.get(i).release();
                }
                for (int i = next.size() / 2; i < next.size(); i++) {
                    next.get(i).release();
                }
            }));
        }
        for (Future<?> f : released) {
            f.get();
        }
        held.clear();
    }

    /** Bytes the allocator holds, by its own metric. */
    private static long held(ByteBufAllocator alloc) {
        return ((ByteBufAllocatorMetricProvider) alloc).metric().usedDirectMemory();
    }

    private static void sample(ByteBufAllocator alloc) {
        BufferPoolMXBean direct = null;
        for (BufferPoolMXBean p : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            if ("direct".equals(p.getName())) {
                direct = p;
            }
        }
        for (;;) {
            long rss = -1;
            try {
                for (String line : Files.readAllLines(Paths.get("/proc/self/status"))) {
                    if (line.startsWith("VmRSS:")) {
                        rss = Long.parseLong(line.replaceAll("[^0-9]", ""));
                    }
                }
            } catch (Exception ignore) {
                // keep -1
            }
            long used = held(alloc);
            System.out.println("MEM " + phase + ' ' + System.currentTimeMillis() + ' ' + rss + ' '
                    + direct.getMemoryUsed() + ' ' + direct.getCount() + ' ' + used);
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
