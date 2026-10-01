package org.jpy.ffm;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/**
 * Copies between a Python buffer's memory and a Java primitive array (the C jpy's memcpy calls
 * around GetPrimitiveArrayCritical). Copies need no pinning: FFM copies into heap arrays directly.
 * Unaligned layouts, because a Python buffer's start address carries no alignment guarantee.
 */
final class Buffers {

    static ValueLayout layout(Class<?> component) {
        if (component == byte.class) return JAVA_BYTE;
        if (component == char.class) return ValueLayout.JAVA_CHAR_UNALIGNED;
        if (component == short.class) return ValueLayout.JAVA_SHORT_UNALIGNED;
        if (component == int.class) return ValueLayout.JAVA_INT_UNALIGNED;
        if (component == long.class) return ValueLayout.JAVA_LONG_UNALIGNED;
        if (component == float.class) return ValueLayout.JAVA_FLOAT_UNALIGNED;
        if (component == double.class) return ValueLayout.JAVA_DOUBLE_UNALIGNED;
        throw new IllegalArgumentException("not a primitive array component: " + component);
    }

    /** Python buffer -> Java array (count items). */
    static void copyIn(MemorySegment src, Object array, Class<?> component, int count) {
        if (component == boolean.class) {
            boolean[] a = (boolean[]) array;
            for (int i = 0; i < count; i++) {
                a[i] = src.get(JAVA_BYTE, i) != 0;
            }
        } else {
            MemorySegment.copy(src, layout(component), 0, array, 0, count);
        }
    }

    /** Java array -> Python buffer (count items). */
    static void copyOut(Object array, MemorySegment dst, Class<?> component, int count) {
        if (component == boolean.class) {
            boolean[] a = (boolean[]) array;
            for (int i = 0; i < count; i++) {
                dst.set(JAVA_BYTE, i, (byte) (a[i] ? 1 : 0));
            }
        } else {
            MemorySegment.copy(array, 0, dst, layout(component), 0, count);
        }
    }

    private Buffers() {
    }
}
