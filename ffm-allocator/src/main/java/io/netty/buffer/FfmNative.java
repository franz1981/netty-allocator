/*
 * Experimental: plain malloc/free behind the Java FFM API, bound with Linker.Option.critical.
 */
package io.netty.buffer;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Downcall bindings for {@code malloc}/{@code free}/{@code realloc}.
 *
 * <p>Pointers are modelled as {@code long} (not {@code MemorySegment}) and every handle is bound with
 * {@link Linker.Option#critical(boolean) critical(false)}, so an invocation is a leaf call with no
 * thread-state transition, no {@code MemorySegment} wrapper and therefore no allocation.
 *
 * <p>Each backend keeps its handles in {@code static final} fields of its own class so that the JIT sees a
 * constant {@link MethodHandle} at the call site and can inline the downcall stub.
 */
final class FfmNative {

    private static final String SO_NAME = "libmimalloc.so";

    private FfmNative() {
    }

    /** Abstract dispatch; one implementation per backend, each calling its own static final handles. */
    abstract static class Binding {
        abstract long malloc(long size);

        abstract void free(long address);

        abstract long realloc(long address, long size);

        abstract String describe();
    }

    private static MethodHandle mallocHandle(SymbolLookup lookup, String name) {
        return Linker.nativeLinker().downcallHandle(
                lookup.find(name).orElseThrow(() -> new UnsatisfiedLinkError("symbol not found: " + name)),
                FunctionDescriptor.of(JAVA_LONG, JAVA_LONG),
                Linker.Option.critical(false));
    }

    private static MethodHandle freeHandle(SymbolLookup lookup, String name) {
        return Linker.nativeLinker().downcallHandle(
                lookup.find(name).orElseThrow(() -> new UnsatisfiedLinkError("symbol not found: " + name)),
                FunctionDescriptor.ofVoid(JAVA_LONG),
                Linker.Option.critical(false));
    }

    private static MethodHandle reallocHandle(SymbolLookup lookup, String name) {
        return Linker.nativeLinker().downcallHandle(
                lookup.find(name).orElseThrow(() -> new UnsatisfiedLinkError("symbol not found: " + name)),
                FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG),
                Linker.Option.critical(false));
    }

    /**
     * Where {@code libmimalloc.so} is, without loading it. Resolution order:
     * <ol>
     *   <li>{@code -Dffm.mimalloc.lib=<file>};</li>
     *   <li>{@code -Dffm.mimalloc.dir=<dir>}{@code /libmimalloc.so};</li>
     *   <li>{@code <user.dir>/native/mimalloc/out/release/libmimalloc.so} and the same path under
     *       {@code <user.dir>/ffm-allocator/}, i.e. what {@code native/build-mimalloc.sh} produces when it is
     *       run from the module directory or from the repository root;</li>
     *   <li>every entry of {@code java.library.path};</li>
     *   <li>{@code /usr/local/lib}, {@code /usr/lib64}, {@code /usr/lib/x86_64-linux-gnu}.</li>
     * </ol>
     * There is no fallback to the default lookup on purpose: a run that silently used glibc's {@code malloc}
     * while claiming to be mimalloc would be a measurement bug.
     */
    static Path mimallocLibrary() {
        String explicit = System.getProperty("ffm.mimalloc.lib");
        if (explicit != null) {
            Path p = Paths.get(explicit);
            if (!Files.isReadable(p)) {
                throw new IllegalStateException("-Dffm.mimalloc.lib=" + explicit + " is not readable");
            }
            return p;
        }
        List<Path> tried = new ArrayList<>();
        String dir = System.getProperty("ffm.mimalloc.dir");
        if (dir != null) {
            tried.add(Paths.get(dir, SO_NAME));
        }
        String userDir = System.getProperty("user.dir", ".");
        tried.add(Paths.get(userDir, "native/mimalloc/out/release", SO_NAME));
        tried.add(Paths.get(userDir, "ffm-allocator/native/mimalloc/out/release", SO_NAME));
        String libraryPath = System.getProperty("java.library.path", "");
        for (String entry : libraryPath.split(File.pathSeparator)) {
            if (!entry.isEmpty()) {
                tried.add(Paths.get(entry, SO_NAME));
            }
        }
        tried.add(Paths.get("/usr/local/lib", SO_NAME));
        tried.add(Paths.get("/usr/lib64", SO_NAME));
        tried.add(Paths.get("/usr/lib/x86_64-linux-gnu", SO_NAME));
        for (Path p : tried) {
            if (Files.isReadable(p)) {
                return p;
            }
        }
        throw new IllegalStateException(SO_NAME + " not found; build it with native/build-mimalloc.sh or set "
                + "-Dffm.mimalloc.lib=<path>. Looked at: " + tried);
    }

    /**
     * The process-wide {@code malloc} family, resolved through the default lookup. Under
     * {@code LD_PRELOAD=libmimalloc.so} these symbols are mimalloc's.
     */
    static final class Libc extends Binding {
        static final Libc INSTANCE = new Libc();

        private static final MethodHandle MALLOC;
        private static final MethodHandle FREE;
        private static final MethodHandle REALLOC;

        static {
            SymbolLookup lookup = Linker.nativeLinker().defaultLookup();
            MALLOC = mallocHandle(lookup, "malloc");
            FREE = freeHandle(lookup, "free");
            REALLOC = reallocHandle(lookup, "realloc");
        }

        static long doMalloc(long size) {
            try {
                return (long) MALLOC.invokeExact(size);
            } catch (Throwable t) {
                throw sneaky(t);
            }
        }

        static void doFree(long address) {
            try {
                FREE.invokeExact(address);
            } catch (Throwable t) {
                throw sneaky(t);
            }
        }

        static long doRealloc(long address, long size) {
            try {
                return (long) REALLOC.invokeExact(address, size);
            } catch (Throwable t) {
                throw sneaky(t);
            }
        }

        @Override
        long malloc(long size) {
            return doMalloc(size);
        }

        @Override
        void free(long address) {
            doFree(address);
        }

        @Override
        long realloc(long address, long size) {
            return doRealloc(address, size);
        }

        @Override
        String describe() {
            return "libc malloc/free/realloc (default lookup)";
        }
    }

    /** mimalloc's own entry points, resolved from libmimalloc.so. */
    static final class Mi extends Binding {
        static final Mi INSTANCE = new Mi();

        private static final MethodHandle MI_MALLOC;
        private static final MethodHandle MI_FREE;
        private static final MethodHandle MI_REALLOC;
        private static final String LIB;

        static {
            Path lib = mimallocLibrary();
            LIB = lib.toString();
            SymbolLookup lookup = SymbolLookup.libraryLookup(lib, Arena.global());
            MI_MALLOC = mallocHandle(lookup, "mi_malloc");
            MI_FREE = freeHandle(lookup, "mi_free");
            MI_REALLOC = reallocHandle(lookup, "mi_realloc");
        }

        static long doMalloc(long size) {
            try {
                return (long) MI_MALLOC.invokeExact(size);
            } catch (Throwable t) {
                throw sneaky(t);
            }
        }

        static void doFree(long address) {
            try {
                MI_FREE.invokeExact(address);
            } catch (Throwable t) {
                throw sneaky(t);
            }
        }

        static long doRealloc(long address, long size) {
            try {
                return (long) MI_REALLOC.invokeExact(address, size);
            } catch (Throwable t) {
                throw sneaky(t);
            }
        }

        @Override
        long malloc(long size) {
            return doMalloc(size);
        }

        @Override
        void free(long address) {
            doFree(address);
        }

        @Override
        long realloc(long address, long size) {
            return doRealloc(address, size);
        }

        @Override
        String describe() {
            return "mi_malloc/mi_free/mi_realloc from " + LIB;
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneaky(Throwable t) throws E {
        throw (E) t;
    }
}
