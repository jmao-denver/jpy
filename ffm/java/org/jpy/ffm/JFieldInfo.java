package org.jpy.ffm;

import java.lang.foreign.MemorySegment;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One jpy.JField: an instance field accessor stored in its declaring type's
 * dict. Reads and writes happen in the instance's getattro/setattro slots,
 * as in JObj_getattro/JObj_setattro.
 */
final class JFieldInfo {

    /** Python jpy.JField objects, keyed by address. */
    static final ConcurrentHashMap<Long, JFieldInfo> BY_PYOBJ = new ConcurrentHashMap<>();

    final JavaType declaringType;
    final Field field;
    final String name;
    final Class<?> type;
    MemorySegment pyObj;

    JFieldInfo(JavaType declaringType, Field field) {
        this.declaringType = declaringType;
        this.field = field;
        this.name = field.getName();
        this.type = field.getType();
    }

    /** New reference to the field's value on javaObject. */
    MemorySegment get(Object javaObject) {
        return read(field, javaObject);
    }

    /** Instance fields keep the C jpy's lenient write (JPy_AS_J* macros) for parity. */
    void set(Object javaObject, MemorySegment value) {
        write(field, javaObject, value);
    }

    /**
     * Static non-final fields are new in the FFM jpy, with no C behavior to match, so writes use
     * the strict typed conversion: a wrong Python type raises ValueError instead of storing -1.
     */
    static void writeStatic(Field field, MemorySegment value) {
        Object v = Convert.toJavaObject(value, field.getType(), false);
        if (v == null && field.getType().isPrimitive()) {
            throw Convert.conversionError(value, field.getType().getName());
        }
        try {
            field.set(null, v);
        } catch (IllegalAccessException | IllegalArgumentException e) {
            throw JavaErrors.toPython(e);
        }
    }

    /** New reference to a field's value; target is null for static fields. */
    static MemorySegment read(Field field, Object target) {
        Object v;
        try {
            v = field.get(target);
        } catch (IllegalAccessException | IllegalArgumentException | NullPointerException e) {
            throw JavaErrors.toPython(e);
        }
        return Convert.toPython(v, field.getType());
    }

    /**
     * JObj_setattro's conversion: primitives use the unchecked JPy_AS_J* macros, objects the
     * typed conversion. target is null for static fields.
     */
    static void write(Field field, Object target, MemorySegment value) {
        Class<?> type = field.getType();
        Object v = type.isPrimitive() ? Convert.toJavaArg(value, type) : Convert.toJavaObject(value, type, false);
        try {
            field.set(target, v);
        } catch (IllegalAccessException | IllegalArgumentException | NullPointerException e) {
            throw JavaErrors.toPython(e);
        }
    }
}
