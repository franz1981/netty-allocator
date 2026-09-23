/*
 * Experimental: a direct ByteBuf whose memory is a plain malloc'd block reached through a long address.
 */
package io.netty.buffer;

import io.netty.util.Recycler;
import io.netty.util.internal.PlatformDependent;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ScatteringByteChannel;

/**
 * Direct {@link ByteBuf} backed by a raw {@code malloc}'d block.
 *
 * <p>All accessors go through {@link UnsafeByteBufUtil} on {@code memoryAddress + index}, exactly like
 * {@link PooledUnsafeDirectByteBuf}. Reference counting is Netty's own
 * ({@link AbstractReferenceCountedByteBuf}, atomic updater). No {@link ByteBuffer} is created unless the
 * caller asks for one; it is then cached for the lifetime of this buffer instance.
 */
final class FfmByteBuf extends AbstractReferenceCountedByteBuf {

    private final Recycler.EnhancedHandle<FfmByteBuf> handle;

    private long memoryAddress;
    private int length;
    private FfmMallocByteBufAllocator alloc;
    private FfmNative.Binding nat;
    private FfmMallocByteBufAllocator.LocalCache cache;
    private ByteBuffer tmpNioBuf;

    FfmByteBuf(Recycler.EnhancedHandle<FfmByteBuf> handle) {
        super(0);
        this.handle = handle;
    }

    void init(FfmMallocByteBufAllocator alloc, FfmNative.Binding nat,
              FfmMallocByteBufAllocator.LocalCache cache,
              long address, int length, int maxCapacity) {
        this.alloc = alloc;
        this.nat = nat;
        this.cache = cache;
        this.memoryAddress = address;
        this.length = length;
        this.tmpNioBuf = null;
        maxCapacity(maxCapacity);
        setIndex0(0, 0);
        resetRefCnt();
    }

    @Override
    protected void deallocate() {
        long address = memoryAddress;
        memoryAddress = 0;
        tmpNioBuf = null;
        FfmNative.Binding binding = nat;
        if (address != 0) {
            binding.free(address);
        }
        FfmMallocByteBufAllocator a = alloc;
        alloc = null;
        nat = null;
        FfmMallocByteBufAllocator.LocalCache c = cache;
        cache = null;
        if (a != null) {
            a.recycleWrapper(this, c);
        }
    }

    void recycleToRecycler() {
        handle.unguardedRecycle(this);
    }

    // ---------------------------------------------------------------- capacity

    @Override
    public int capacity() {
        return length;
    }

    /**
     * {@code realloc}: contents are preserved, the block may move, and on a shrink the indices are trimmed.
     * Any {@link ByteBuffer} handed out earlier by {@link #nioBuffer(int, int)} or
     * {@link #internalNioBuffer(int, int)} points at the old block and must not be used afterwards; the
     * cached one is dropped here. {@code checkNewCapacity} calls {@code ensureAccessible()}, so this cannot
     * run on a released buffer, and a failing {@code realloc} leaves the current block intact.
     */
    @Override
    public ByteBuf capacity(int newCapacity) {
        checkNewCapacity(newCapacity);
        if (newCapacity == length) {
            return this;
        }
        long newAddress = nat.realloc(memoryAddress, newCapacity == 0 ? 1 : newCapacity);
        if (newAddress == 0) {
            // realloc returning NULL leaves the original block allocated and unchanged: this buffer is still
            // usable at its current capacity.
            throw new OutOfMemoryError("realloc(" + newCapacity + ") failed, capacity is still " + length);
        }
        memoryAddress = newAddress;
        tmpNioBuf = null;
        boolean shrink = newCapacity < length;
        length = newCapacity;
        if (shrink) {
            trimIndicesToCapacity(newCapacity);
        }
        return this;
    }

    @Override
    public ByteBufAllocator alloc() {
        return alloc;
    }

    @SuppressWarnings("deprecation")
    @Override
    public ByteOrder order() {
        return ByteOrder.BIG_ENDIAN;
    }

    @Override
    public ByteBuf unwrap() {
        return null;
    }

    @Override
    public boolean isDirect() {
        return true;
    }

    @Override
    boolean _isDirect() {
        return true;
    }

    @Override
    public boolean isContiguous() {
        // One malloc'd block, no composition: the same answer PooledByteBuf and UnpooledDirectByteBuf give.
        return true;
    }

    @Override
    public boolean hasArray() {
        return false;
    }

    @Override
    public byte[] array() {
        throw new UnsupportedOperationException("direct buffer");
    }

    @Override
    public int arrayOffset() {
        throw new UnsupportedOperationException("direct buffer");
    }

    @Override
    public boolean hasMemoryAddress() {
        return true;
    }

    @Override
    public long memoryAddress() {
        ensureAccessible();
        return memoryAddress;
    }

    @Override
    long _memoryAddress() {
        return memoryAddress;
    }

    private long addr(int index) {
        return memoryAddress + index;
    }

    // ---------------------------------------------------------------- nio buffers

    @Override
    public int nioBufferCount() {
        return 1;
    }

    @Override
    public ByteBuffer nioBuffer(int index, int length) {
        checkIndex(index, length);
        return directByteBuffer(addr(index), length);
    }

    @Override
    public ByteBuffer[] nioBuffers(int index, int length) {
        return new ByteBuffer[] { nioBuffer(index, length) };
    }

    /**
     * A direct {@link ByteBuffer} view over raw memory, built with FFM rather than with
     * {@code PlatformDependent.directBuffer}, which needs the private {@code DirectByteBuffer(long,int)}
     * constructor and is therefore unavailable without {@code --add-opens java.base/java.nio}.
     *
     * <p><b>Lifetime.</b> {@link MemorySegment#ofAddress(long)} returns a zero-length segment in the
     * <em>global</em> scope and {@link MemorySegment#reinterpret(long)} keeps that scope, so the returned
     * {@link ByteBuffer} is never invalidated: it stays readable and writable after the block has been
     * {@code free}d, and reading it then is a use-after-free. That is the same contract as
     * {@link #memoryAddress()} and as Netty's own no-cleaner direct buffers
     * ({@code PlatformDependent.directBuffer(long, int)}), and it is why this class does not hand the view out
     * from anywhere but {@link #nioBuffer(int, int)}: the cached {@link #tmpNioBuf} is dropped in
     * {@link #deallocate()} and in {@link #capacity(int)}, so the buffer itself never uses a stale view.
     * A caller that keeps the result of {@code nioBuffer(int, int)} past {@code release()} is on its own.
     * {@code FfmMallocByteBufAllocatorTest.nioBufferViewOutlivesTheBlock} pins this behaviour down.
     */
    static ByteBuffer directByteBuffer(long address, int length) {
        return MemorySegment.ofAddress(address).reinterpret(length).asByteBuffer();
    }

    private ByteBuffer internalNioBuffer() {
        ByteBuffer tmp = tmpNioBuf;
        if (tmp == null) {
            tmpNioBuf = tmp = directByteBuffer(memoryAddress, length);
        }
        tmp.clear();
        return tmp;
    }

    @Override
    public ByteBuffer internalNioBuffer(int index, int length) {
        checkIndex(index, length);
        ByteBuffer tmp = internalNioBuffer();
        tmp.position(index).limit(index + length);
        return tmp;
    }

    // ---------------------------------------------------------------- primitive accessors

    @Override
    protected byte _getByte(int index) {
        return UnsafeByteBufUtil.getByte(addr(index));
    }

    @Override
    protected short _getShort(int index) {
        return UnsafeByteBufUtil.getShort(addr(index));
    }

    @Override
    protected short _getShortLE(int index) {
        return UnsafeByteBufUtil.getShortLE(addr(index));
    }

    @Override
    protected int _getUnsignedMedium(int index) {
        return UnsafeByteBufUtil.getUnsignedMedium(addr(index));
    }

    @Override
    protected int _getUnsignedMediumLE(int index) {
        return UnsafeByteBufUtil.getUnsignedMediumLE(addr(index));
    }

    @Override
    protected int _getInt(int index) {
        return UnsafeByteBufUtil.getInt(addr(index));
    }

    @Override
    protected int _getIntLE(int index) {
        return UnsafeByteBufUtil.getIntLE(addr(index));
    }

    @Override
    protected long _getLong(int index) {
        return UnsafeByteBufUtil.getLong(addr(index));
    }

    @Override
    protected long _getLongLE(int index) {
        return UnsafeByteBufUtil.getLongLE(addr(index));
    }

    @Override
    protected void _setByte(int index, int value) {
        UnsafeByteBufUtil.setByte(addr(index), (byte) value);
    }

    @Override
    protected void _setShort(int index, int value) {
        UnsafeByteBufUtil.setShort(addr(index), value);
    }

    @Override
    protected void _setShortLE(int index, int value) {
        UnsafeByteBufUtil.setShortLE(addr(index), value);
    }

    @Override
    protected void _setMedium(int index, int value) {
        UnsafeByteBufUtil.setMedium(addr(index), value);
    }

    @Override
    protected void _setMediumLE(int index, int value) {
        UnsafeByteBufUtil.setMediumLE(addr(index), value);
    }

    @Override
    protected void _setInt(int index, int value) {
        UnsafeByteBufUtil.setInt(addr(index), value);
    }

    @Override
    protected void _setIntLE(int index, int value) {
        UnsafeByteBufUtil.setIntLE(addr(index), value);
    }

    @Override
    protected void _setLong(int index, long value) {
        UnsafeByteBufUtil.setLong(addr(index), value);
    }

    @Override
    protected void _setLongLE(int index, long value) {
        UnsafeByteBufUtil.setLongLE(addr(index), value);
    }

    // ---------------------------------------------------------------- bulk accessors

    @Override
    public ByteBuf getBytes(int index, ByteBuf dst, int dstIndex, int length) {
        UnsafeByteBufUtil.getBytes(this, addr(index), index, dst, dstIndex, length);
        return this;
    }

    @Override
    public ByteBuf getBytes(int index, byte[] dst, int dstIndex, int length) {
        UnsafeByteBufUtil.getBytes(this, addr(index), index, dst, dstIndex, length);
        return this;
    }

    @Override
    public ByteBuf getBytes(int index, ByteBuffer dst) {
        UnsafeByteBufUtil.getBytes(this, addr(index), index, dst);
        return this;
    }

    @Override
    public ByteBuf getBytes(int index, OutputStream out, int length) throws IOException {
        UnsafeByteBufUtil.getBytes(this, addr(index), index, out, length);
        return this;
    }

    @Override
    public int getBytes(int index, GatheringByteChannel out, int length) throws IOException {
        return getBytes(index, out, length, false);
    }

    private int getBytes(int index, GatheringByteChannel out, int length, boolean internal) throws IOException {
        ensureAccessible();
        if (length == 0) {
            return 0;
        }
        ByteBuffer tmpBuf = internal ? internalNioBuffer(index, length) : nioBuffer(index, length);
        return out.write(tmpBuf);
    }

    @Override
    public int getBytes(int index, FileChannel out, long position, int length) throws IOException {
        ensureAccessible();
        if (length == 0) {
            return 0;
        }
        return out.write(internalNioBuffer(index, length), position);
    }

    @Override
    public int readBytes(GatheringByteChannel out, int length) throws IOException {
        checkReadableBytes(length);
        int readBytes = getBytes(readerIndex, out, length, true);
        readerIndex += readBytes;
        return readBytes;
    }

    @Override
    public ByteBuf setBytes(int index, ByteBuf src, int srcIndex, int length) {
        UnsafeByteBufUtil.setBytes(this, addr(index), index, src, srcIndex, length);
        return this;
    }

    @Override
    public ByteBuf setBytes(int index, byte[] src, int srcIndex, int length) {
        UnsafeByteBufUtil.setBytes(this, addr(index), index, src, srcIndex, length);
        return this;
    }

    @Override
    public ByteBuf setBytes(int index, ByteBuffer src) {
        UnsafeByteBufUtil.setBytes(this, addr(index), index, src);
        return this;
    }

    @Override
    public int setBytes(int index, InputStream in, int length) throws IOException {
        return UnsafeByteBufUtil.setBytes(this, addr(index), index, in, length);
    }

    @Override
    public int setBytes(int index, ScatteringByteChannel in, int length) throws IOException {
        ensureAccessible();
        try {
            return in.read(internalNioBuffer(index, length));
        } catch (java.nio.channels.ClosedChannelException ignored) {
            return -1;
        }
    }

    @Override
    public int setBytes(int index, FileChannel in, long position, int length) throws IOException {
        ensureAccessible();
        try {
            return in.read(internalNioBuffer(index, length), position);
        } catch (java.nio.channels.ClosedChannelException ignored) {
            return -1;
        }
    }

    @Override
    public ByteBuf copy(int index, int length) {
        return UnsafeByteBufUtil.copy(this, addr(index), index, length);
    }

    @Override
    public ByteBuf setZero(int index, int length) {
        checkIndex(index, length);
        UnsafeByteBufUtil.setZero(addr(index), length);
        return this;
    }

    @Override
    public ByteBuf writeZero(int length) {
        ensureWritable(length);
        int wIndex = writerIndex;
        UnsafeByteBufUtil.setZero(addr(wIndex), length);
        writerIndex = wIndex + length;
        return this;
    }

    @Override
    protected SwappedByteBuf newSwappedByteBuf() {
        if (PlatformDependent.isUnaligned()) {
            return new UnsafeDirectSwappedByteBuf(this);
        }
        return super.newSwappedByteBuf();
    }
}
