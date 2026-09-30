package ffm;

import java.lang.foreign.MemorySegment;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generic Python -> Java conversion, the FFM take on jpy's getObjectValue.
 * Dispatch is on the Python object's exact type first (pointer compare against
 * the exported type objects), then isinstance for subclasses. GIL must be held.
 *
 * Mapping: None -> null; bool -> Boolean; int -> Long; float -> Double;
 * str -> String; bytes -> byte[]; list/tuple -> Object[] (recursive);
 * dict -> LinkedHashMap (recursive); anything else -> PyHandle (opaque,
 * owns a reference, like org.jpy.PyObject).
 */
public final class Convert {

    /** Opaque wrapper for Python objects with no natural Java mapping. */
    public static final class PyHandle {
        public final MemorySegment ptr;
        PyHandle(MemorySegment ptr) {
            Py.incRef(ptr);
            this.ptr = ptr;
        }
        public String str() { return Py.str(ptr); }
        public void release() { Py.decRef(ptr); }
        @Override public String toString() { return "PyHandle(" + str() + ")"; }
    }

    /** Convert a borrowed PyObject* to the most appropriate Java value. */
    public static Object toJava(MemorySegment obj) {
        try {
            if (obj.equals(Py.Py_None)) return null;

            MemorySegment type = Py.checked((MemorySegment) Py.PyObject_Type.invokeExact(obj));
            try {
                // bool before int: bool subclasses int
                if (is(obj, type, Py.PyBool_Type)) {
                    int v = (int) Py.PyObject_IsTrue.invokeExact(obj);
                    if (v < 0) throw Py.fetchError();
                    return v != 0;
                }
                if (is(obj, type, Py.PyLong_Type)) {
                    long v = (long) Py.PyLong_AsLongLong.invokeExact(obj);
                    if (v == -1) checkErr();   // also traps int > 64 bits (OverflowError)
                    return v;
                }
                if (is(obj, type, Py.PyFloat_Type)) {
                    return Py.asDouble(obj);
                }
                if (is(obj, type, Py.PyUnicode_Type)) {
                    return Py.jstr(Py.utf8(obj));
                }
                if (is(obj, type, Py.PyBytes_Type)) {
                    long n = (long) Py.PyBytes_Size.invokeExact(obj);
                    if (n < 0) throw Py.fetchError();
                    MemorySegment buf = (MemorySegment) Py.PyBytes_AsString.invokeExact(obj);
                    return Py.checked(buf).reinterpret(n).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
                }
                if (is(obj, type, Py.PyTuple_Type)) {
                    long n = (long) Py.PyTuple_Size.invokeExact(obj);
                    Object[] out = new Object[(int) n];
                    for (long i = 0; i < n; i++) {
                        out[(int) i] = toJava(Py.checked(
                                (MemorySegment) Py.PyTuple_GetItem.invokeExact(obj, i))); // borrowed
                    }
                    return out;
                }
                if (is(obj, type, Py.PyList_Type)) {
                    long n = (long) Py.PyList_Size.invokeExact(obj);
                    if (n < 0) throw Py.fetchError();
                    Object[] out = new Object[(int) n];
                    for (long i = 0; i < n; i++) {
                        out[(int) i] = toJava(Py.checked(
                                (MemorySegment) Py.PyList_GetItem.invokeExact(obj, i))); // borrowed
                    }
                    return out;
                }
                if (is(obj, type, Py.PyDict_Type)) {
                    MemorySegment items = Py.checked((MemorySegment) Py.PyDict_Items.invokeExact(obj)); // new ref
                    try {
                        long n = (long) Py.PyList_Size.invokeExact(items);
                        Map<Object, Object> out = new LinkedHashMap<>((int) n * 2);
                        for (long i = 0; i < n; i++) {
                            MemorySegment kv = Py.checked(
                                    (MemorySegment) Py.PyList_GetItem.invokeExact(items, i)); // borrowed
                            Object k = toJava(Py.checked((MemorySegment) Py.PyTuple_GetItem.invokeExact(kv, 0L)));
                            Object v = toJava(Py.checked((MemorySegment) Py.PyTuple_GetItem.invokeExact(kv, 1L)));
                            out.put(k, v);
                        }
                        return out;
                    } finally {
                        Py.decRef(items);
                    }
                }
                return new PyHandle(obj);
            } finally {
                Py.decRef(type);
            }
        } catch (Py.PyException e) {
            throw e;
        } catch (Throwable t) {
            throw Py.sneaky(t);
        }
    }

    /** Call a Python callable (no args) and convert the result generically. */
    public static Object callToJava(MemorySegment callable, long... args) {
        MemorySegment r = Py.call(callable, args);
        try {
            return toJava(r);
        } finally {
            Py.decRef(r);
        }
    }

    /** Exact type match (pointer equality) or subclass (isinstance). */
    static boolean is(MemorySegment obj, MemorySegment actualType, MemorySegment wantedType) throws Throwable {
        if (actualType.address() == wantedType.address()) return true;
        int r = (int) Py.PyObject_IsInstance.invokeExact(obj, wantedType);
        if (r < 0) throw Py.fetchError();
        return r != 0;
    }

    static void checkErr() throws Throwable {
        MemorySegment err = (MemorySegment) Py.PyErr_Occurred.invokeExact();
        if (!err.equals(Py.NULL)) throw Py.fetchError();
    }

    private Convert() {}
}
