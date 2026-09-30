package org.jpy;

import java.lang.foreign.MemorySegment;
import java.lang.reflect.Array;

import ffm.Py;

/**
 * FFM re-implementation of jpy's conversion layer, with the same class and
 * method names as the JNI natives it replaces. Semantics are copied from
 * jpy_conv.h macros, JType_ConvertPythonToJavaObject and
 * JType_ConvertJavaToPythonObject (jpy_jtype.c), including the quirks:
 *
 * - bool: Py_True -> true, Py_False/None -> 0, anything else -> truthiness
 * - numeric targets accept None as 0 and TRUNCATE silently (C casts)
 * - char is an integer type: Python int -> char, and char -> Python int
 * - untyped Python int -> the SMALLEST fitting box (Byte/Short/Integer/Long)
 * - untyped list/dict/tuple/bytes do NOT convert: ValueError, unless object
 *   wrapping is allowed, in which case they become org.jpy.PyObject
 *
 * All methods take the GIL themselves, like the JNI natives did
 * (JPy_BEGIN_GIL_STATE). Pointers are jlong-shaped, as in jpy.
 */
public final class PyLib {

    // ---- typed value accessors (the PyLib natives) ----

    /** JPy_AS_JINT: None -> 0, else PyLong value truncated to 32 bits. */
    public static int getIntValue(long pointer) {
        return (int) getLongValue(pointer);
    }

    /** JPy_AS_JLONG: None -> 0, else PyLong_AsLongLong (bool counts as int). */
    public static long getLongValue(long pointer) {
        try (Py.Gil gil = Py.Gil.lock()) {
            MemorySegment obj = seg(pointer);
            if (obj.equals(Py.Py_None)) return 0;
            return asLongLongTruncating(obj);
        } catch (Py.PyException e) {
            throw e;
        } catch (Throwable t) {
            throw Py.sneaky(t);
        }
    }

    /** JPy_AS_JDOUBLE: None -> 0, int -> value, float -> value, else __float__. */
    public static double getDoubleValue(long pointer) {
        try (Py.Gil gil = Py.Gil.lock()) {
            MemorySegment obj = seg(pointer);
            if (obj.equals(Py.Py_None)) return 0;
            return Py.asDouble(obj);
        }
    }

    /** JPy_AS_JBOOLEAN: identity on True/False, None -> false, else truthiness. */
    public static boolean getBooleanValue(long pointer) {
        try (Py.Gil gil = Py.Gil.lock()) {
            return asBoolean(seg(pointer));
        }
    }

    /** JPy_AsJString: None -> null, str -> String, anything else -> TypeError. */
    public static String getStringValue(long pointer) {
        try (Py.Gil gil = Py.Gil.lock()) {
            MemorySegment obj = seg(pointer);
            if (obj.equals(Py.Py_None)) return null;
            if (!isExactOrSubtype(obj, Py.PyUnicode_Type)) {
                throw new Py.PyException("cannot convert a Python '" + typeName(obj) + "' to a Java 'java.lang.String'");
            }
            try {
                return Py.jstr(Py.utf8(obj));
            } catch (Throwable t) {
                throw Py.sneaky(t);
            }
        }
    }

    /** getObjectValue native: untyped conversion, no object wrapping. */
    public static Object getObjectValue(long pointer) {
        try (Py.Gil gil = Py.Gil.lock()) {
            return convertPythonToJava(seg(pointer), Object.class, false);
        }
    }

    /** Untyped conversion as used by callAndReturnObject: wrapping allowed. */
    public static Object getObjectValueWrapped(long pointer) {
        try (Py.Gil gil = Py.Gil.lock()) {
            return convertPythonToJava(seg(pointer), Object.class, true);
        }
    }

    /** Target-typed conversion (JType_ConvertPythonToJavaObject). */
    public static Object convertPythonToJava(long pointer, Class<?> target) {
        try (Py.Gil gil = Py.Gil.lock()) {
            return convertPythonToJava(seg(pointer), target, false);
        }
    }

    /** Java -> Python (JType_ConvertJavaToPythonObject). Returns a new reference as PyObject. */
    public static PyObject convertJavaToPython(Object value) {
        try (Py.Gil gil = Py.Gil.lock()) {
            return new PyObject(javaToPython(value), false);
        }
    }

    // ---- Python -> Java core (GIL held) ----

    static Object convertPythonToJava(MemorySegment obj, Class<?> target, boolean allowObjectWrapping) {
        try {
            // None converts into null for every target
            if (obj.equals(Py.Py_None)) return null;

            if (target == PyObject.class) {
                return new PyObject(obj, true);
            }
            if (target.isArray()) {
                return toJavaArray(obj, target.getComponentType(), allowObjectWrapping);
            }
            if (target == boolean.class || target == Boolean.class) {
                return asBoolean(obj);
            }
            if (target == char.class || target == Character.class) {
                requireInt(obj, target);
                return (char) asLongLongTruncating(obj);
            }
            if (target == byte.class || target == Byte.class) {
                requireInt(obj, target);
                return (byte) asLongLongTruncating(obj);
            }
            if (target == short.class || target == Short.class) {
                requireInt(obj, target);
                return (short) asLongLongTruncating(obj);
            }
            if (target == int.class || target == Integer.class) {
                requireInt(obj, target);
                return (int) asLongLongTruncating(obj);
            }
            if (target == long.class || target == Long.class) {
                requireInt(obj, target);
                return asLongLongTruncating(obj);
            }
            if (target == float.class || target == Float.class) {
                requireNumber(obj, target);
                return (float) Py.asDouble(obj);
            }
            if (target == double.class || target == Double.class) {
                requireNumber(obj, target);
                return Py.asDouble(obj);
            }

            // reference targets, in jpy_jtype.c order
            if (isExactOrSubtype(obj, Py.PyUnicode_Type)
                    && (target == String.class || target.isAssignableFrom(String.class))) {
                return Py.jstr(Py.utf8(obj));
            }
            if (isExactOrSubtype(obj, Py.PyBool_Type) && target.isAssignableFrom(Boolean.class)) {
                return asBoolean(obj);
            }
            if (isExactOrSubtype(obj, Py.PyLong_Type) && (target == Object.class || target.isAssignableFrom(Number.class))) {
                return smallestIntBox(obj);   // JType_CreateJavaNumberFromPythonInt
            }
            if (isExactOrSubtype(obj, Py.PyLong_Type) && target.isAssignableFrom(Integer.class)) {
                return (int) asLongLongTruncating(obj);
            }
            if (isExactOrSubtype(obj, Py.PyLong_Type) && target.isAssignableFrom(Long.class)) {
                return asLongLongTruncating(obj);
            }
            if (isExactOrSubtype(obj, Py.PyFloat_Type) && (target == Object.class || target.isAssignableFrom(Double.class))) {
                return Py.asDouble(obj);
            }
            if (isExactOrSubtype(obj, Py.PyFloat_Type) && target.isAssignableFrom(Float.class)) {
                return (float) Py.asDouble(obj);
            }
            if (target == Object.class && allowObjectWrapping) {
                return new PyObject(obj, true);
            }
            throw conversionError(obj, target.getName());
        } catch (Py.PyException e) {
            throw e;
        } catch (Throwable t) {
            throw Py.sneaky(t);
        }
    }

    /** JType_CreateJavaArray: any PySequence (or None -> empty) to a Java array. */
    static Object toJavaArray(MemorySegment obj, Class<?> component, boolean allowObjectWrapping) throws Throwable {
        int check = (int) Py.PySequence_Check.invokeExact(obj);
        if (check == 0) {
            throw new Py.PyException("cannot convert a Python '" + typeName(obj)
                    + "' to a Java array of type '" + component.getName() + "'");
        }
        long n = (long) Py.PySequence_Size.invokeExact(obj);
        if (n < 0) throw Py.fetchError();
        Object out = Array.newInstance(component, (int) n);
        for (long i = 0; i < n; i++) {
            MemorySegment item = Py.checked((MemorySegment) Py.PySequence_GetItem.invokeExact(obj, i)); // new ref
            try {
                Array.set(out, (int) i, convertPythonToJava(item, component, allowObjectWrapping));
            } finally {
                Py.decRef(item);
            }
        }
        return out;
    }

    /** JType_CreateJavaNumberFromPythonInt: smallest box that holds the value. */
    static Object smallestIntBox(MemorySegment obj) throws Throwable {
        long j = asLongLongTruncating(obj);
        int i = (int) j;
        short s = (short) j;
        byte b = (byte) j;
        if (i != j) return j;
        if (s != i) return i;
        if (b != s) return s;
        return b;
    }

    // ---- Java -> Python core (GIL held); returns a new reference ----

    static MemorySegment javaToPython(Object v) {
        try {
            if (v == null) {
                Py.incRef(Py.Py_None);
                return Py.Py_None;
            }
            if (v instanceof PyObject p) {          // unwrap, like JPy_JPyObject branch
                MemorySegment ptr = MemorySegment.ofAddress(p.getPointer());
                Py.incRef(ptr);
                return ptr;
            }
            if (v instanceof Boolean b) {
                return Py.checked((MemorySegment) Py.PyBool_FromLong.invokeExact(b ? 1L : 0L));
            }
            if (v instanceof Character c) {         // JPy_FROM_JCHAR: char -> Python int
                return Py.checked((MemorySegment) Py.PyLong_FromLong.invokeExact((long) (char) c));
            }
            if (v instanceof Byte || v instanceof Short || v instanceof Integer || v instanceof Long) {
                return Py.checked((MemorySegment) Py.PyLong_FromLong.invokeExact(((Number) v).longValue()));
            }
            if (v instanceof Float || v instanceof Double) {
                return Py.checked((MemorySegment) Py.PyFloat_FromDouble.invokeExact(((Number) v).doubleValue()));
            }
            if (v instanceof String str) {
                try (java.lang.foreign.Arena a = java.lang.foreign.Arena.ofConfined()) {
                    return Py.checked((MemorySegment) Py.PyUnicode_FromString.invokeExact(Py.cstr(a, str)));
                }
            }
            // jpy wraps any other Java object as a JObj of its runtime type;
            // the prototype has no jtype system, so this is the parity boundary.
            throw new UnsupportedOperationException(
                    "JObj wrapping of " + v.getClass().getName() + " is outside the conversion-parity prototype");
        } catch (Py.PyException | UnsupportedOperationException e) {
            throw e;
        } catch (Throwable t) {
            throw Py.sneaky(t);
        }
    }

    // ---- helpers (GIL held) ----

    static MemorySegment seg(long pointer) {
        return MemorySegment.ofAddress(pointer);
    }

    /** JPy_AS_JBOOLEAN. */
    static boolean asBoolean(MemorySegment obj) {
        try {
            if (obj.equals(Py.Py_True)) return true;
            if (obj.equals(Py.Py_False) || obj.equals(Py.Py_None)) return false;
            int r = (int) Py.PyObject_IsTrue.invokeExact(obj);
            if (r < 0) throw Py.fetchError();
            return r != 0;
        } catch (Throwable t) {
            throw Py.sneaky(t);
        }
    }

    /**
     * PyLong_AsLongLong with jpy's effective semantics. jpy's macros never
     * check the error indicator, so an int > 64 bits ends up as the C cast of
     * -1 while the OverflowError stays pending (a latent bug there). We keep
     * the -1 value for parity but clear the error instead of leaking it.
     */
    static long asLongLongTruncating(MemorySegment obj) throws Throwable {
        long v = (long) Py.PyLong_AsLongLong.invokeExact(obj);
        if (v == -1) {
            MemorySegment err = (MemorySegment) Py.PyErr_Occurred.invokeExact();
            if (!err.equals(Py.NULL)) Py.PyErr_Clear.invokeExact();
        }
        return v;
    }

    /** JPy_IS_CLONG gate used by the numeric creators (bool passes: it is an int). */
    static void requireInt(MemorySegment obj, Class<?> target) {
        if (!isExactOrSubtype(obj, Py.PyLong_Type)) {
            throw conversionError(obj, target.getName());
        }
    }

    static void requireNumber(MemorySegment obj, Class<?> target) {
        if (!isExactOrSubtype(obj, Py.PyLong_Type) && !isExactOrSubtype(obj, Py.PyFloat_Type)) {
            throw conversionError(obj, target.getName());
        }
    }

    static boolean isExactOrSubtype(MemorySegment obj, MemorySegment wantedType) {
        try {
            MemorySegment type = Py.checked((MemorySegment) Py.PyObject_Type.invokeExact(obj));
            try {
                if (type.address() == wantedType.address()) return true;
                int r = (int) Py.PyObject_IsInstance.invokeExact(obj, wantedType);
                if (r < 0) throw Py.fetchError();
                return r != 0;
            } finally {
                Py.decRef(type);
            }
        } catch (Py.PyException e) {
            throw e;
        } catch (Throwable t) {
            throw Py.sneaky(t);
        }
    }

    static String typeName(MemorySegment obj) {
        try {
            MemorySegment type = Py.checked((MemorySegment) Py.PyObject_Type.invokeExact(obj));
            try {
                MemorySegment nameObj = Py.getAttr(type, "__name__");
                try {
                    return Py.jstr(Py.utf8(nameObj));
                } finally {
                    Py.decRef(nameObj);
                }
            } finally {
                Py.decRef(type);
            }
        } catch (Throwable t) {
            return "?";
        }
    }

    /** Same message shape as JType_PythonToJavaConversionError (a ValueError there). */
    static Py.PyException conversionError(MemorySegment obj, String javaName) {
        return new Py.PyException("cannot convert a Python '" + typeName(obj) + "' to a Java '" + javaName + "'");
    }

    private PyLib() {}
}
