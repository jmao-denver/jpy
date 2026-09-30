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
        Object v;
        try {
            v = field.get(javaObject);
        } catch (IllegalAccessException | IllegalArgumentException | NullPointerException e) {
            throw JavaErrors.toPython(e);
        }
        return Convert.toPython(v, type);
    }

    /** JObj_setattro: primitives use the unchecked JPy_AS_J* macros, objects the typed conversion. */
    void set(Object javaObject, MemorySegment value) {
        Object v;
        if (type.isPrimitive()) {
            v = Convert.toJavaArg(value, type);
        } else {
            v = Convert.toJavaObject(value, type, false);
        }
        try {
            field.set(javaObject, v);
        } catch (IllegalAccessException | IllegalArgumentException | NullPointerException e) {
            throw JavaErrors.toPython(e);
        }
    }
}
