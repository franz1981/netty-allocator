/*
 * Copyright 2025 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.github.neoionet.netty.microbenchmark;

import io.github.neoionet.netty.mimalloc.MiByteBufAllocator;
import io.netty.buffer.AdaptiveByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CycleArenaAllocator;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * EXPERIMENT: the event-loop lifecycle - allocate K buffers, use each once, release all K - per invocation.
 * Sizes cycle through a small mixed set; release order is allocation order (FIFO) or reverse (LIFO).
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 1, jvmArgs = {"-server", "-dsa", "-da", "-XX:InitialRAMPercentage=40.0", "-XX:MaxRAMPercentage=40.0",
        "-Dio.netty.leakDetection.level=disabled", "-Djmh.executor=CUSTOM",
        "-Djmh.executor.class=io.github.neoionet.netty.microbenchmark.executor.FastThreadLocalThreadHarnessExecutor"})
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
@Threads(1)
public class CycleScopedAllocBenchmark {
    @Param({"ADAPTIVE", "MIMALLOC", "ARENA"})
    public String allocatorType;
    @Param({"8", "64"})
    public int k;
    @Param({"FIFO", "LIFO"})
    public String releaseOrder;
    @Param({"MIXED", "SMALL"})
    public String sizes;

    private ByteBufAllocator allocator;
    /** Non-null for ARENA: the cycle boundary of this benchmark is the arena's end-of-iteration hook. */
    private CycleArenaAllocator arena;
    private ByteBuf[] bufs;
    private int[] sizeTable;
    private int sizeIdx;

    @Setup
    public void setup() {
        allocator = "MIMALLOC".equals(allocatorType) ? new MiByteBufAllocator()
                : "ARENA".equals(allocatorType) ? new CycleArenaAllocator()
                : new AdaptiveByteBufAllocator();
        arena = allocator instanceof CycleArenaAllocator ? (CycleArenaAllocator) allocator : null;
        bufs = new ByteBuf[k];
        sizeTable = "SMALL".equals(sizes) ? new int[] {64, 128, 256, 512}
                : new int[] {64, 1500, 256, 8192, 128, 4096, 1024, 32768, 512, 2048, 96, 16384};
    }

    @Benchmark
    public void cycleDirect(Blackhole bh) {
        cycle(bh, true);
    }

    @Benchmark
    public void cycleHeap(Blackhole bh) {
        cycle(bh, false);
    }

    private void cycle(Blackhole bh, boolean direct) {
        final ByteBuf[] bufs = this.bufs;
        final int[] sizeTable = this.sizeTable;
        int idx = sizeIdx;
        for (int i = 0; i < bufs.length; i++) {
            int size = sizeTable[idx];
            idx = idx + 1 == sizeTable.length ? 0 : idx + 1;
            ByteBuf b = direct ? allocator.directBuffer(size) : allocator.heapBuffer(size);
            b.writeByte(size);
            bufs[i] = b;
        }
        sizeIdx = idx;
        if ("LIFO".equals(releaseOrder)) {
            for (int i = bufs.length - 1; i >= 0; i--) {
                bh.consume(bufs[i].readByte());
                bufs[i].release();
                bufs[i] = null;
            }
        } else {
            for (int i = 0; i < bufs.length; i++) {
                bh.consume(bufs[i].readByte());
                bufs[i].release();
                bufs[i] = null;
            }
        }
        if (arena != null) {
            arena.endOfIteration();     // this invocation IS one event-loop iteration
        }
    }

    @TearDown
    public void dumpArenaCounters() {
        if (arena != null) {
            System.out.println(CycleArenaAllocator.counters());
            System.out.flush();
        }
    }
}
