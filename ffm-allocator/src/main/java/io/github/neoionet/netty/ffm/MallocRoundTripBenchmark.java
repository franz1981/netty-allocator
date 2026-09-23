package io.github.neoionet.netty.ffm;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import io.netty.buffer.FfmMallocByteBufAllocator;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Cost of one {@code malloc(size)} + {@code free(ptr)} round trip through an FFM downcall, with and
 * without {@link Linker.Option#critical(boolean)}, for glibc and for mimalloc.
 *
 * <p>This isolates the FFM overhead from everything Netty adds on top of it.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgs = {
        "-server", "-dsa", "-da",
        "--enable-native-access=ALL-UNNAMED"
})
public class MallocRoundTripBenchmark {

    private static final Linker LINKER = Linker.nativeLinker();

    private static MethodHandle bind(SymbolLookup lookup, String name, FunctionDescriptor fd, boolean critical) {
        return critical
                ? LINKER.downcallHandle(lookup.find(name).orElseThrow(), fd, Linker.Option.critical(false))
                : LINKER.downcallHandle(lookup.find(name).orElseThrow(), fd);
    }

    private static final FunctionDescriptor ALLOC_FD = FunctionDescriptor.of(JAVA_LONG, JAVA_LONG);
    private static final FunctionDescriptor FREE_FD = FunctionDescriptor.ofVoid(JAVA_LONG);

    private static final SymbolLookup LIBC = LINKER.defaultLookup();

    private static final MethodHandle MALLOC_CRIT = bind(LIBC, "malloc", ALLOC_FD, true);
    private static final MethodHandle FREE_CRIT = bind(LIBC, "free", FREE_FD, true);
    private static final MethodHandle MALLOC_PLAIN = bind(LIBC, "malloc", ALLOC_FD, false);
    private static final MethodHandle FREE_PLAIN = bind(LIBC, "free", FREE_FD, false);

    private static final class MiHolder {
        // Same resolution order as the allocator: -Dffm.mimalloc.lib, -Dffm.mimalloc.dir, the module's
        // native/mimalloc/out/release, java.library.path, then the usual system directories.
        static final SymbolLookup LOOKUP = SymbolLookup.libraryLookup(
                Paths.get(FfmMallocByteBufAllocator.mimallocLibraryPath()), Arena.global());
        static final MethodHandle MALLOC_CRIT = bind(LOOKUP, "mi_malloc", ALLOC_FD, true);
        static final MethodHandle FREE_CRIT = bind(LOOKUP, "mi_free", FREE_FD, true);
        static final MethodHandle MALLOC_PLAIN = bind(LOOKUP, "mi_malloc", ALLOC_FD, false);
        static final MethodHandle FREE_PLAIN = bind(LOOKUP, "mi_free", FREE_FD, false);
    }

    @Param({"64", "1024"})
    public int size;

    @Benchmark
    public long glibcCritical() throws Throwable {
        long p = (long) MALLOC_CRIT.invokeExact((long) size);
        FREE_CRIT.invokeExact(p);
        return p;
    }

    @Benchmark
    public long glibcNonCritical() throws Throwable {
        long p = (long) MALLOC_PLAIN.invokeExact((long) size);
        FREE_PLAIN.invokeExact(p);
        return p;
    }

    @Benchmark
    public long mimallocCritical() throws Throwable {
        long p = (long) MiHolder.MALLOC_CRIT.invokeExact((long) size);
        MiHolder.FREE_CRIT.invokeExact(p);
        return p;
    }

    @Benchmark
    public long mimallocNonCritical() throws Throwable {
        long p = (long) MiHolder.MALLOC_PLAIN.invokeExact((long) size);
        MiHolder.FREE_PLAIN.invokeExact(p);
        return p;
    }
}
