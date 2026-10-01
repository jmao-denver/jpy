package org.jpy.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Array;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The buffer protocol on Java primitive arrays (memoryview(java_array), np.frombuffer, ...),
 * with the C jpy's semantics (JArray_GetBufferProc / JArray_ReleaseBufferProc /
 * JArray_ReleaseJavaArrayElements), measured on the C jpy before porting:
 *
 * - The first export copies the Java array into native memory; every later export shares that
 *   copy. The C jpy gets the same effect from Get<Type>ArrayElements, which copies on HotSpot.
 *   So a view is a snapshot: Java writes after the first export are not visible through it.
 * - Releasing a view copies nothing.
 * - When the Python wrapper is deallocated, the copy is written back to the Java array if any
 *   export was writable (JNI's mode 0 versus JNI_ABORT), then freed.
 *
 * No pinning and no JNI: a copy is all the C jpy does, and FFM copies to and from heap arrays.
 */
final class ArrayExports {

    private static final class Export {
        final Class<?> component;
        final int count;
        final int itemSize;
        final Arena arena = Arena.ofShared();
        final MemorySegment data;
        /** int64 shape[1], int64 strides[1], shared by every view. */
        final MemorySegment shapeAndStrides;
        boolean allReadonly = true;
        int exportCount;

        Export(Object array, Class<?> component) {
            this.component = component;
            this.count = Array.getLength(array);
            this.itemSize = OverloadSet.primitiveSize(component);
            this.data = arena.allocate(Math.max(1L, (long) count * itemSize), 8);
            Buffers.copyOut(array, data, component, count);
            this.shapeAndStrides = arena.allocate(16, 8);
            shapeAndStrides.set(JAVA_LONG, 0, count);
            shapeAndStrides.set(JAVA_LONG, 8, itemSize);
        }
    }

    private static final Map<Long, Export> EXPORTS = new ConcurrentHashMap<>();

    /** Struct-module format per component type, as in jpy_jarray.c. */
    private static final Map<Class<?>, MemorySegment> FORMATS = Map.of(
            boolean.class, Arena.global().allocateFrom("B"),
            char.class, Arena.global().allocateFrom("H"),
            byte.class, Arena.global().allocateFrom("b"),
            short.class, Arena.global().allocateFrom("h"),
            int.class, Arena.global().allocateFrom("i"),
            long.class, Arena.global().allocateFrom("q"),
            float.class, Arena.global().allocateFrom("f"),
            double.class, Arena.global().allocateFrom("d"));
    private static final MemorySegment FORMAT_UNSIGNED_BYTE = Arena.global().allocateFrom("B");

    /** bf_getbuffer: fills the Py_buffer (layout in CPython.Buffer) and takes a reference to self. */
    static void getBuffer(MemorySegment self, MemorySegment view, int flags, Object array, Class<?> component) {
        Export e;
        synchronized (EXPORTS) {
            e = EXPORTS.computeIfAbsent(self.address(), k -> new Export(array, component));
            boolean readonly = (flags & CPython.PyBUF_WRITABLE) == 0;
            e.allReadonly &= readonly;
            e.exportCount++;
            MemorySegment v = view.reinterpret(CPython.Buffer.SIZE);
            v.set(ADDRESS, 0, e.data);
            v.set(ADDRESS, 8, self);
            v.set(JAVA_LONG, 16, (long) e.count * e.itemSize);
            v.set(JAVA_LONG, 24, e.itemSize);
            v.set(JAVA_INT, 32, readonly ? 1 : 0);
            v.set(JAVA_INT, 36, 1);
            v.set(ADDRESS, 40, (flags & CPython.PyBUF_FORMAT) != 0 ? FORMATS.get(component) : FORMAT_UNSIGNED_BYTE);
            v.set(ADDRESS, 48, e.shapeAndStrides);
            v.set(ADDRESS, 56, e.shapeAndStrides.asSlice(8));
            v.set(ADDRESS, 64, CPython.NULL);
            v.set(ADDRESS, 72, CPython.NULL);
        }
        CPython.incRef(self);
    }

    /** bf_releasebuffer: the copy stays until the wrapper is deallocated, as in the C jpy. */
    static void releaseBuffer(MemorySegment self) {
        synchronized (EXPORTS) {
            Export e = EXPORTS.get(self.address());
            if (e != null) {
                e.exportCount--;
            }
        }
    }

    /** From tp_dealloc: write back if any export was writable, then free the copy. */
    static void onDealloc(MemorySegment self, Object array) {
        Export e = EXPORTS.remove(self.address());
        if (e == null) {
            return;
        }
        try {
            if (!e.allReadonly && array != null) {
                Buffers.copyIn(e.data, array, e.component, e.count);
            }
        } finally {
            e.arena.close();
        }
    }

    private ArrayExports() {
    }
}
