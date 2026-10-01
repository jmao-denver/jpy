package org.jpy.ffm;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Java objects wrapped by Python instances of Java types, keyed by the
 * Python object's address. Replaces the C jpy's JPy_JObj.objectRef field:
 * the entry is added when the instance is created or initialized and removed
 * in its tp_dealloc, so an address is never reused while its entry exists.
 */
final class JObjects {

    private static final ConcurrentHashMap<Long, Object> TABLE = new ConcurrentHashMap<>();

    /** The wrapped Java object, or null if pyObj is not an initialized Java object. */
    static Object get(MemorySegment pyObj) {
        return TABLE.get(pyObj.address());
    }

    static void put(MemorySegment pyObj, Object javaObject) {
        TABLE.put(pyObj.address(), javaObject);
    }

    static void remove(MemorySegment pyObj) {
        TABLE.remove(pyObj.address());
    }

    static int size() {
        return TABLE.size();
    }

    /** Before Py_Finalize; see Bootstrap.uninstall. */
    static void reset() {
        TABLE.clear();
    }

    /** The Java type of pyObj's exact Python type, or null if it is not a Java type. */
    static JavaType javaTypeOf(MemorySegment pyObj) {
        return JTypes.byPyType(CPython.typeAddress(pyObj));
    }

    /**
     * JObj_FromType: a new Python instance of jt wrapping javaObject (new
     * reference), passed through jpy.type_translations[jt.name] if present.
     */
    static MemorySegment wrap(Object javaObject, JavaType jt) {
        return translate(newWrapper(javaObject, jt), jt);
    }

    /** A new Python instance of jt wrapping javaObject, before any type translation (new reference). */
    static MemorySegment newWrapper(Object javaObject, JavaType jt) {
        MemorySegment py = CPython.allocInstance(jt.pyType);
        TABLE.put(py.address(), javaObject);
        return py;
    }

    /** Applies jpy.type_translations[jt.name] to a fresh wrapper; steals py's reference. */
    static MemorySegment translate(MemorySegment py, JavaType jt) {
        MemorySegment translation = JpyModule.typeTranslation(jt.name);
        if (translation == null) {
            return py;
        }
        CPython.incRef(jt.pyType);
        MemorySegment args = CPython.newTuple(jt.pyType, py);
        try {
            return CPython.call(translation, args, CPython.NULL);
        } finally {
            CPython.decRef(args);
        }
    }

    private JObjects() {
    }
}
