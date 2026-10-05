package org.jpy.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Optional;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * POSIX dlopen/dlclose/dlerror through FFM: the C jpy's 'jdl' library (org_jpy_DL.c) without
 * compiled code. Windows has no dlopen, so every call is a no-op there, as in the C jpy.
 */
public final class Dl {

    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("windows");
    private static final boolean MAC = System.getProperty("os.name").toLowerCase().contains("mac");

    // <dlfcn.h> values differ by platform: Linux has RTLD_LOCAL == 0 and RTLD_GLOBAL == 0x100.
    private static final int RTLD_LAZY = 0x1;
    private static final int RTLD_NOW = 0x2;
    private static final int RTLD_LOCAL = MAC ? 0x4 : 0x0;
    private static final int RTLD_GLOBAL = MAC ? 0x8 : 0x100;

    private static final MethodHandle DLOPEN;
    private static final MethodHandle DLCLOSE;
    private static final MethodHandle DLERROR;
    private static final MethodHandle DLSYM;

    static {
        if (WINDOWS) {
            DLOPEN = DLCLOSE = DLERROR = DLSYM = null;
        } else {
            Linker linker = Linker.nativeLinker();
            SymbolLookup libc = linker.defaultLookup();
            // glibc before 2.34 keeps dlopen in libdl, not in libc.
            SymbolLookup lookup = name -> {
                Optional<MemorySegment> s = libc.find(name);
                return s.isPresent() ? s : SymbolLookup.libraryLookup("libdl.so.2", Arena.global()).find(name);
            };
            DLOPEN = linker.downcallHandle(lookup.find("dlopen").orElseThrow(), FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
            DLCLOSE = linker.downcallHandle(lookup.find("dlclose").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS));
            DLERROR = linker.downcallHandle(lookup.find("dlerror").orElseThrow(), FunctionDescriptor.of(ADDRESS));
            DLSYM = linker.downcallHandle(lookup.find("dlsym").orElseThrow(), FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        }
    }

    public static long dlopen(String filename, boolean lazy, boolean now, boolean local, boolean global) {
        if (WINDOWS || filename == null) {
            return 0;
        }
        int flags = (lazy ? RTLD_LAZY : 0) | (now ? RTLD_NOW : 0) | (local ? RTLD_LOCAL : 0) | (global ? RTLD_GLOBAL : 0);
        try (Arena a = Arena.ofConfined()) {
            return ((MemorySegment) DLOPEN.invokeExact(a.allocateFrom(filename), flags)).address();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int dlclose(long handle) {
        if (WINDOWS) {
            return 0;
        }
        try {
            return (int) DLCLOSE.invokeExact(MemorySegment.ofAddress(handle));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static String dlerror() {
        if (WINDOWS) {
            return null;
        }
        try {
            MemorySegment m = (MemorySegment) DLERROR.invokeExact();
            return m.equals(MemorySegment.NULL) ? null : m.reinterpret(Long.MAX_VALUE).getString(0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * Finds symbols in everything the process has loaded, the executable included: dlsym(RTLD_DEFAULT).
     * A Python with libpython built into its executable (Ubuntu's python3, uv's Linux builds) has no
     * libpython file to open, but exports the Python API from the executable itself.
     * Not available on Windows, where Python always runs from a python3XX.dll.
     */
    public static SymbolLookup processLookup() {
        if (WINDOWS) {
            throw new UnsupportedOperationException("no process-wide symbol lookup on Windows");
        }
        // <dlfcn.h>: RTLD_DEFAULT is ((void *) 0) on Linux and ((void *) -2) on macOS.
        MemorySegment rtldDefault = MemorySegment.ofAddress(MAC ? -2L : 0L);
        return name -> {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment s = (MemorySegment) DLSYM.invokeExact(rtldDefault, a.allocateFrom(name));
                return s.equals(MemorySegment.NULL) ? Optional.empty() : Optional.of(s);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        };
    }

    /**
     * setenv(name, value, 1), or _putenv_s on Windows. Used before Py_Initialize, where PYTHONHOME
     * does what the removed Py_SetPythonHome did.
     */
    public static void setenv(String name, String value) {
        Linker linker = Linker.nativeLinker();
        try (Arena a = Arena.ofConfined()) {
            int rc;
            if (WINDOWS) {
                MethodHandle putenv = linker.downcallHandle(
                        SymbolLookup.libraryLookup("ucrtbase", a).find("_putenv_s").orElseThrow(),
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
                rc = (int) putenv.invokeExact(a.allocateFrom(name), a.allocateFrom(value));
            } else {
                MethodHandle setenv = linker.downcallHandle(linker.defaultLookup().find("setenv").orElseThrow(),
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
                rc = (int) setenv.invokeExact(a.allocateFrom(name), a.allocateFrom(value), 1);
            }
            if (rc != 0) {
                throw new IllegalStateException("cannot set environment variable " + name);
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // Not CPython.rethrow: that would initialize CPython, which needs libpython, which may not be loaded yet.
    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) return r;
        if (t instanceof Error e) throw e;
        return new RuntimeException(t);
    }

    private Dl() {
    }
}
