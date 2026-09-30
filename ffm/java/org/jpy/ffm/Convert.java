package org.jpy.ffm;

import java.lang.foreign.MemorySegment;
import java.lang.reflect.Array;

import static org.jpy.ffm.CPython.Py_False;
import static org.jpy.ffm.CPython.Py_None;
import static org.jpy.ffm.CPython.Py_True;

/**
 * Python <-> Java value conversion, ported rule for rule from the C jpy
 * (jpy_conv.h macros, JType_ConvertPythonToJavaObject,
 * JType_ConvertJavaToPythonObject, JType_ConvertPyArgTo*Arg,
 * JType_CreateJavaArray). Quirks are kept on purpose; see DESIGN.md.
 * The GIL must be held.
 */
final class Convert {

    // ------------------------------------------------------------------
    // jpy_conv.h JPy_AS_J* macros
    // ------------------------------------------------------------------

    /** JPy_AS_JBOOLEAN: True -> true, False/None -> false, else truthiness. */
    static boolean asJBoolean(MemorySegment o) {
        if (o.equals(Py_True)) return true;
        if (o.equals(Py_False) || o.equals(Py_None)) return false;
        return CPython.isTrue(o);
    }

    /** JPy_AS_JLONG and friends: None -> 0, else PyLong_AsLongLong, truncated by the caller's cast. */
    static long asJLong(MemorySegment o) {
        return o.equals(Py_None) ? 0 : CPython.asLongUnchecked(o);
    }

    /** JPy_AS_JDOUBLE: None -> 0, else PyFloat_AsDouble (error not checked, as in C). */
    static double asJDouble(MemorySegment o) {
        if (o.equals(Py_None)) return 0;
        try {
            return CPython.asDouble(o);
        } catch (CPython.PyErrAlreadySet e) {
            CPython.errClear();
            return -1.0;
        }
    }

    // ------------------------------------------------------------------
    // Method arguments: JType_ConvertPyArgTo*Arg, selected by parameter type
    // ------------------------------------------------------------------

    static Object toJavaArg(MemorySegment pyArg, Class<?> paramType) {
        if (paramType.isPrimitive()) {
            if (paramType == boolean.class) return asJBoolean(pyArg);
            if (paramType == byte.class) return (byte) asJLong(pyArg);
            if (paramType == char.class) return (char) asJLong(pyArg);
            if (paramType == short.class) return (short) asJLong(pyArg);
            if (paramType == int.class) return (int) asJLong(pyArg);
            if (paramType == long.class) return asJLong(pyArg);
            if (paramType == float.class) return (float) asJDouble(pyArg);
            if (paramType == double.class) return asJDouble(pyArg);
            throw CPython.runtimeError("internal error: illegal parameter type " + paramType.getName());
        }
        if (paramType == String.class) {
            return pyArg.equals(Py_None) ? null : CPython.toJavaString(pyArg);
        }
        if (paramType == Constants.PY_OBJECT_CLASS) {
            throw CPython.runtimeError("org.jpy.PyObject parameters are not supported yet by the FFM bridge");
        }
        // JType_ConvertPyArgToJObjectArg
        if (pyArg.equals(Py_None)) return null;
        Object wrapped = JObjects.get(pyArg);
        if (wrapped != null) return wrapped;
        // TODO(session 5): primitive-array parameter from a Python buffer object
        return toJavaObject(pyArg, paramType, false);
    }

    // ------------------------------------------------------------------
    // JType_ConvertPythonToJavaObject
    // ------------------------------------------------------------------

    static Object toJavaObject(MemorySegment pyArg, Class<?> target, boolean allowObjectWrapping) {
        if (pyArg.equals(Py_None)) {
            return null;
        }
        Object wrapped = JObjects.get(pyArg);
        if (wrapped != null && target.isInstance(wrapped)) {
            return wrapped;
        }
        JavaType asType = JTypes.byPyType(pyArg.address());
        if (asType != null && target.isAssignableFrom(Class.class)) {
            return asType.clazz;
        }
        if (target.isArray()) {
            return toJavaArray(pyArg, target.getComponentType(), allowObjectWrapping);
        }
        if (target == boolean.class || target == Boolean.class) {
            return asJBoolean(pyArg);
        }
        if (target == char.class || target == Character.class) {
            requireInt(pyArg, target);
            return (char) CPython.asLongUnchecked(pyArg);
        }
        if (target == byte.class || target == Byte.class) {
            requireInt(pyArg, target);
            return (byte) CPython.asLongUnchecked(pyArg);
        }
        if (target == short.class || target == Short.class) {
            requireInt(pyArg, target);
            return (short) CPython.asLongUnchecked(pyArg);
        }
        if (target == int.class || target == Integer.class) {
            requireInt(pyArg, target);
            return (int) CPython.asLongUnchecked(pyArg);
        }
        if (target == long.class || target == Long.class) {
            requireInt(pyArg, target);
            return CPython.asLongUnchecked(pyArg);
        }
        if (target == float.class || target == Float.class) {
            if (CPython.isLong(pyArg)) return (float) CPython.asLongUnchecked(pyArg);
            if (CPython.isFloat(pyArg)) return (float) asJDouble(pyArg);
            throw conversionError(pyArg, target.getName());
        }
        if (target == double.class || target == Double.class) {
            if (CPython.isLong(pyArg)) return (double) CPython.asLongUnchecked(pyArg);
            if (CPython.isFloat(pyArg)) return asJDouble(pyArg);
            throw conversionError(pyArg, target.getName());
        }
        if (target == Constants.PY_OBJECT_CLASS) {
            throw CPython.runtimeError("org.jpy.PyObject targets are not supported yet by the FFM bridge");
        }
        if (CPython.isStr(pyArg) && target.isAssignableFrom(String.class)) {
            return CPython.toJavaString(pyArg);
        }
        if (CPython.isBool(pyArg) && target.isAssignableFrom(Boolean.class)) {
            return asJBoolean(pyArg);
        }
        boolean isInt = CPython.isLong(pyArg);
        if (isInt && target.isAssignableFrom(Number.class)) {
            return smallestIntBox(CPython.asLongUnchecked(pyArg));
        }
        if (isInt && target.isAssignableFrom(Integer.class)) {
            return (int) CPython.asLongUnchecked(pyArg);
        }
        if (isInt && target.isAssignableFrom(Long.class)) {
            return CPython.asLongUnchecked(pyArg);
        }
        boolean isFloat = CPython.isFloat(pyArg);
        if (isFloat && target.isAssignableFrom(Double.class)) {
            return asJDouble(pyArg);
        }
        if (isFloat && target.isAssignableFrom(Float.class)) {
            return (float) asJDouble(pyArg);
        }
        if (target == Object.class && allowObjectWrapping) {
            throw CPython.runtimeError("wrapping Python objects as org.jpy.PyObject is not supported yet by the FFM bridge");
        }
        throw conversionError(pyArg, target.getName());
    }

    /** JType_CreateJavaNumberFromPythonInt: the smallest box that holds the value. */
    static Object smallestIntBox(long j) {
        int i = (int) j;
        short s = (short) j;
        byte b = (byte) j;
        if (i != j) return j;
        if (s != i) return i;
        if (b != s) return s;
        return b;
    }

    /** JType_CreateJavaArray: any sequence (or None, giving an empty array) to a Java array. */
    static Object toJavaArray(MemorySegment pyArg, Class<?> component, boolean allowObjectWrapping) {
        long n;
        if (pyArg.equals(Py_None)) {
            n = 0;
        } else if (CPython.isSequence(pyArg)) {
            n = CPython.sequenceSize(pyArg);
        } else {
            throw CPython.valueError("cannot convert a Python '" + CPython.typeName(pyArg)
                    + "' to a Java array of type '" + component.getName() + "'");
        }
        Object array = Array.newInstance(component, (int) n);
        for (int i = 0; i < n; i++) {
            MemorySegment item = CPython.sequenceGet(pyArg, i);
            try {
                if (component.isPrimitive()) {
                    Array.set(array, i, primitiveArrayItem(item, component));
                } else {
                    Array.set(array, i, toJavaObject(item, component, allowObjectWrapping));
                }
            } finally {
                CPython.decRef(item);
            }
        }
        return array;
    }

    /** Array items use the JPy_AS_J* macros, but the C code checks PyErr_Occurred after each one. */
    static Object primitiveArrayItem(MemorySegment item, Class<?> component) {
        Object v;
        if (component == boolean.class) {
            v = asJBoolean(item);
        } else if (component == float.class || component == double.class) {
            double d = item.equals(Py_None) ? 0 : CPython.asDouble(item);
            v = component == float.class ? (Object) (float) d : (Object) d;
        } else {
            long l = item.equals(Py_None) ? 0 : CPython.asLong(item);
            if (component == byte.class) v = (byte) l;
            else if (component == char.class) v = (char) l;
            else if (component == short.class) v = (short) l;
            else if (component == int.class) v = (int) l;
            else v = l;
        }
        return v;
    }

    private static void requireInt(MemorySegment pyArg, Class<?> target) {
        if (!CPython.isLong(pyArg)) {
            throw conversionError(pyArg, target.getName());
        }
    }

    /** JType_PythonToJavaConversionError. */
    static CPython.PyRaise conversionError(MemorySegment pyArg, String javaName) {
        return CPython.valueError("cannot convert a Python '" + CPython.typeName(pyArg) + "' to a Java '" + javaName + "'");
    }

    // ------------------------------------------------------------------
    // Java -> Python: JType_ConvertJavaToPythonObject (new reference)
    // ------------------------------------------------------------------

    static MemorySegment toPython(Object v, Class<?> declared) {
        if (declared == void.class) {
            return CPython.none();
        }
        if (v == null) {
            return CPython.none();
        }
        if (declared == boolean.class || declared == Boolean.class) {
            return CPython.newBool((Boolean) v);
        }
        if (declared == char.class || declared == Character.class) {
            return CPython.newLong((Character) v);
        }
        if (declared == byte.class || declared == short.class || declared == int.class || declared == long.class
                || declared == Byte.class || declared == Short.class || declared == Integer.class || declared == Long.class) {
            return CPython.newLong(((Number) v).longValue());
        }
        if (declared == float.class || declared == double.class || declared == Float.class || declared == Double.class) {
            return CPython.newFloat(((Number) v).doubleValue());
        }
        if (declared == String.class) {
            return CPython.newStr((String) v);
        }
        if (declared == Object.class) {
            Class<?> runtime = v.getClass();
            if (runtime != Object.class) {
                return toPython(v, runtime);
            }
        }
        // TODO(session 6/7): org.jpy.PyObject values and createProxy() unwrapping
        return JObjects.wrap(v, JTypes.getType(declared, false));
    }

    private Convert() {
    }
}
