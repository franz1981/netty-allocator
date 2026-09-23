package io.netty.buffer;

import io.netty.util.concurrent.FastThreadLocalThread;
import org.junit.jupiter.api.Test;

import io.netty.util.IllegalReferenceCountException;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FfmMallocByteBufAllocatorTest {

    private static FfmMallocByteBufAllocator allocator() {
        return new FfmMallocByteBufAllocator(FfmMallocByteBufAllocator.Backend.GLIBC);
    }

    @Test
    void basicWriteReadRelease() {
        FfmMallocByteBufAllocator alloc = allocator();
        ByteBuf buf = alloc.directBuffer(64);
        assertTrue(buf.isDirect());
        assertTrue(buf.hasMemoryAddress());
        assertNotEquals(0L, buf.memoryAddress());
        assertEquals(64, buf.capacity());
        for (int i = 0; i < 16; i++) {
            buf.writeInt(i);
        }
        for (int i = 0; i < 16; i++) {
            assertEquals(i, buf.readInt());
        }
        assertTrue(buf.release());
        assertEquals(0, buf.refCnt());
    }

    @Test
    void reallocGrowKeepsContent() {
        FfmMallocByteBufAllocator alloc = allocator();
        ByteBuf buf = alloc.directBuffer(32);
        for (int i = 0; i < 32; i++) {
            buf.writeByte(i);
        }
        buf.capacity(4096);
        assertEquals(4096, buf.capacity());
        assertEquals(32, buf.writerIndex());
        for (int i = 0; i < 32; i++) {
            assertEquals((byte) i, buf.getByte(i));
        }
        buf.release();
    }

    @Test
    void reallocShrinkTrimsIndices() {
        FfmMallocByteBufAllocator alloc = allocator();
        ByteBuf buf = alloc.directBuffer(256);
        for (int i = 0; i < 256; i++) {
            buf.writeByte(i);
        }
        buf.capacity(16);
        assertEquals(16, buf.capacity());
        assertEquals(16, buf.writerIndex());
        for (int i = 0; i < 16; i++) {
            assertEquals((byte) i, buf.getByte(i));
        }
        buf.release();
    }

    @Test
    void ensureWritableGrows() {
        FfmMallocByteBufAllocator alloc = allocator();
        ByteBuf buf = alloc.directBuffer(8);
        buf.writeLong(0x0102030405060708L);
        buf.writeLong(0x1112131415161718L);
        assertTrue(buf.capacity() >= 16);
        assertEquals(0x0102030405060708L, buf.getLong(0));
        assertEquals(0x1112131415161718L, buf.getLong(8));
        buf.release();
    }

    @Test
    void nioBufferViewSharesMemory() {
        FfmMallocByteBufAllocator alloc = allocator();
        ByteBuf buf = alloc.directBuffer(16);
        buf.setZero(0, 16);
        ByteBuffer nio = buf.nioBuffer(0, 16);
        assertTrue(nio.isDirect());
        nio.put(0, (byte) 0x7f);
        assertEquals(0x7f, buf.getByte(0));
        ByteBuffer internal = buf.internalNioBuffer(4, 4);
        assertEquals(4, internal.position());
        assertEquals(8, internal.limit());
        buf.release();
    }

    @Test
    void copyAndBulkTransfer() {
        FfmMallocByteBufAllocator alloc = allocator();
        ByteBuf buf = alloc.directBuffer(128);
        byte[] src = new byte[128];
        for (int i = 0; i < src.length; i++) {
            src[i] = (byte) (i * 7);
        }
        buf.writeBytes(src);
        ByteBuf copy = buf.copy();
        byte[] out = new byte[128];
        copy.getBytes(0, out);
        for (int i = 0; i < src.length; i++) {
            assertEquals(src[i], out[i]);
        }
        copy.release();
        buf.release();
    }

    @Test
    void wrappersAreReusedOnFastThreadLocalThread() throws Exception {
        FfmMallocByteBufAllocator alloc = allocator();
        BlockingQueue<Object> result = new ArrayBlockingQueue<>(1);
        FastThreadLocalThread t = new FastThreadLocalThread(() -> {
            // warm up the cache
            for (int i = 0; i < 100; i++) {
                alloc.directBuffer(64).release();
            }
            long before = FfmMallocByteBufAllocator.wrappersCreated();
            java.util.List<ByteBuf> live = new java.util.ArrayList<>();
            for (int i = 0; i < 50; i++) {
                live.add(alloc.directBuffer(64));
            }
            for (ByteBuf b : live) {
                b.release();
            }
            for (int i = 0; i < 1000; i++) {
                alloc.directBuffer(64).release();
            }
            long after = FfmMallocByteBufAllocator.wrappersCreated();
            result.add(after - before);
        });
        t.start();
        t.join();
        long created = (Long) result.take();
        // 50 live buffers may need up to 50 fresh wrappers the first time round; the following 1000
        // alloc/release pairs must create none.
        assertTrue(created <= 50, "wrappers created in steady state: " + created);
    }

    @Test
    void crossThreadReleaseUsesRecyclerPath() throws Exception {
        FfmMallocByteBufAllocator alloc = allocator();
        BlockingQueue<ByteBuf> handoff = new ArrayBlockingQueue<>(64);
        CountDownLatch done = new CountDownLatch(1);
        FastThreadLocalThread producer = new FastThreadLocalThread(() -> {
            for (int i = 0; i < 64; i++) {
                ByteBuf b = alloc.directBuffer(32);
                b.writeInt(i);
                handoff.add(b);
            }
            done.countDown();
        });
        producer.start();
        done.await();
        for (int i = 0; i < 64; i++) {
            ByteBuf b = handoff.take();
            assertEquals(i, b.readInt());
            assertTrue(b.release());
            assertFalse(b.isReadable());
        }
        producer.join();
    }

    @Test
    void platformThreadAllocAndReleaseRoundTrip() {
        FfmMallocByteBufAllocator alloc = allocator();
        assertFalse(Thread.currentThread() instanceof FastThreadLocalThread);
        for (int i = 0; i < 1000; i++) {
            ByteBuf b = alloc.directBuffer(128);
            b.writeByte(i);
            assertEquals((byte) i, b.readByte());
            b.release();
        }
    }

    @Test
    void foreignReleaseGoesToTheOwnersHandBackQueueAndIsReused() throws Exception {
        FfmMallocByteBufAllocator alloc = allocator();
        BlockingQueue<ByteBuf> handoff = new ArrayBlockingQueue<>(256);
        BlockingQueue<long[]> result = new ArrayBlockingQueue<>(1);
        CountDownLatch produced = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        // The owner is a plain platform thread: this is the ThreadLocal cache path.
        Thread owner = new Thread(() -> {
            try {
                for (int i = 0; i < 256; i++) {
                    handoff.add(alloc.directBuffer(32));
                }
                produced.countDown();
                released.await();
                long offeredBefore = FfmMallocByteBufAllocator.handBackOffered();
                long drainedBefore = FfmMallocByteBufAllocator.handBackDrained();
                long createdBefore = FfmMallocByteBufAllocator.wrappersCreated();
                for (int i = 0; i < 256; i++) {
                    alloc.directBuffer(32).release();
                }
                result.add(new long[] {
                        FfmMallocByteBufAllocator.handBackOffered() - offeredBefore,
                        FfmMallocByteBufAllocator.handBackDrained() - drainedBefore,
                        FfmMallocByteBufAllocator.wrappersCreated() - createdBefore });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "ffm-owner");
        owner.start();
        produced.await();
        long offeredBefore = FfmMallocByteBufAllocator.handBackOffered();
        for (int i = 0; i < 256; i++) {
            assertTrue(handoff.take().release());
        }
        long offeredByForeignThread = FfmMallocByteBufAllocator.handBackOffered() - offeredBefore;
        released.countDown();
        long[] r = result.take();
        owner.join();
        assertEquals(256, offeredByForeignThread, "foreign releases must land in the hand-back queue");
        assertTrue(r[1] > 0, "owner must drain the hand-back queue, drained=" + r[1]);
        assertEquals(0, r[2], "owner must not create new wrappers, created=" + r[2]);
    }

    @Test
    void handBackQueueOverflowFallsBackToTheRecycler() throws Exception {
        FfmMallocByteBufAllocator alloc = allocator();
        int n = FfmMallocByteBufAllocator.HAND_BACK_CAPACITY + 64;
        BlockingQueue<ByteBuf> handoff = new ArrayBlockingQueue<>(n);
        CountDownLatch produced = new CountDownLatch(1);
        Thread owner = new Thread(() -> {
            for (int i = 0; i < n; i++) {
                handoff.add(alloc.directBuffer(16));
            }
            produced.countDown();
        }, "ffm-owner-2");
        owner.start();
        produced.await();
        long overflowBefore = FfmMallocByteBufAllocator.handBackOverflowToRecycler();
        for (int i = 0; i < n; i++) {
            handoff.take().release();
        }
        owner.join();
        assertTrue(FfmMallocByteBufAllocator.handBackOverflowToRecycler() - overflowBefore > 0,
                "overflow beyond the bounded queue must go to the Recycler and be counted");
    }

    @Test
    void nioBufferViewIsBigEndianAndNotInvalidatedByRelease() {
        FfmMallocByteBufAllocator alloc = allocator();
        ByteBuf buf = alloc.directBuffer(16);
        ByteBuffer nio = buf.nioBuffer(0, 16);
        assertEquals(ByteOrder.BIG_ENDIAN, nio.order(), "Netty's accessors assume big-endian views");
        assertEquals(0, nio.position());
        assertEquals(16, nio.limit());
        MemorySegment seg = MemorySegment.ofBuffer(nio);
        assertEquals(buf.memoryAddress(), seg.address());
        assertTrue(buf.release());
        // The view is built on MemorySegment.ofAddress(..).reinterpret(..), which stays in the global scope:
        // freeing the block does NOT invalidate it. Documented hazard, asserted here so it cannot change
        // silently; the memory is deliberately not touched after the free.
        assertTrue(seg.scope().isAlive(), "the raw view outlives the block it points at");
    }

    @Test
    void reallocDropsTheCachedNioBuffer() {
        FfmMallocByteBufAllocator alloc = allocator();
        ByteBuf buf = alloc.directBuffer(64);
        buf.writeZero(64);
        ByteBuffer before = buf.internalNioBuffer(0, 64);
        assertEquals(buf.memoryAddress(), MemorySegment.ofBuffer(before).address());
        buf.capacity(64 * 1024);
        ByteBuffer after = buf.internalNioBuffer(0, 64);
        assertEquals(buf.memoryAddress(), MemorySegment.ofBuffer(after).address(),
                "the cached view must be rebuilt on the reallocated block");
        // MemorySegment.ofBuffer() only covers position..limit, so the view's size is checked on the
        // ByteBuffer itself: capacity() is the length the cached view was built with.
        assertEquals(64 * 1024, after.capacity());
        buf.release();
    }

    @Test
    void contiguousAndSingleNioBuffer() {
        FfmMallocByteBufAllocator alloc = allocator();
        ByteBuf buf = alloc.directBuffer(32);
        assertTrue(buf.isContiguous());
        assertEquals(1, buf.nioBufferCount());
        assertEquals(1, buf.nioBuffers(0, 32).length);
        buf.release();
    }

    @Test
    void releasedBufferRejectsAccess() {
        FfmMallocByteBufAllocator alloc = allocator();
        ByteBuf buf = alloc.directBuffer(32);
        assertTrue(buf.release());
        assertThrows(IllegalReferenceCountException.class, () -> buf.getByte(0));
        assertThrows(IllegalReferenceCountException.class, () -> buf.capacity(64));
        assertThrows(IllegalReferenceCountException.class, buf::memoryAddress);
        assertThrows(IllegalReferenceCountException.class, () -> buf.nioBuffer(0, 1));
    }

    @Test
    void zeroCapacityBuffer() {
        FfmMallocByteBufAllocator alloc = allocator();
        ByteBuf buf = alloc.directBuffer(0);
        assertEquals(0, buf.capacity());
        buf.writeByte(1);
        assertEquals(1, buf.readByte());
        buf.release();
    }
}
