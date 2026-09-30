package ffm;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;

import org.jpy.PyLib;
import org.jpy.PyObject;

/**
 * Milestone 8: conversion parity with jpy (jpy_conv.h / jpy_jtype.c rules),
 * including the quirks a naive design would get wrong.
 */
public class M8 {
    public static void main(String[] args) {
        Py.start();

        // ---- untyped: getObjectValue (no wrapping) ----
        check("None -> null", val("None") == null);
        check("True -> Boolean", val("True").equals(Boolean.TRUE));
        Object v5 = val("5");
        check("int 5 -> Byte (smallest box), got " + v5.getClass().getSimpleName(),
                v5.equals((byte) 5));
        check("int 200 -> Short", val("200").equals((short) 200));
        check("int 70000 -> Integer", val("70000").equals(70000));
        check("int 2**40 -> Long", val("2**40").equals(1L << 40));
        check("float -> Double", val("3.5").equals(3.5d));
        check("str -> String", val("'abc'").equals("abc"));
        check("list does NOT convert untyped", failsConversion("[1, 2]"));
        check("dict does NOT convert untyped", failsConversion("{'a': 1}"));
        check("bytes does NOT convert untyped", failsConversion("b'ab'"));

        // ---- untyped with wrapping allowed (callAndReturnObject path) ----
        Object wrapped = evalThen(o -> PyLib.getObjectValueWrapped(o.address()), "[1, 2]");
        check("wrapping=true -> org.jpy.PyObject", wrapped instanceof PyObject p && p.str().equals("[1, 2]"));
        ((PyObject) wrapped).close();

        // ---- typed targets ----
        check("byte target truncates silently: 300 -> " + typed("300", byte.class),
                typed("300", byte.class).equals((byte) 44));
        check("char from int: 65 -> 'A'", typed("65", char.class).equals('A'));
        check("char from str is an error", failsTyped("'x'", char.class));
        check("int target rejects float", failsTyped("1.5", int.class));
        check("float target accepts int", typed("7", float.class).equals(7.0f));
        check("bool truthiness: [] -> false", typed("[]", boolean.class).equals(false));
        check("bool truthiness: [0] -> true", typed("[0]", boolean.class).equals(true));
        check("None -> null even for Boolean target", typed("None", Boolean.class) == null);
        check("PyObject target wraps",
                evalThen(o -> {
                    Object p = PyLib.convertPythonToJava(o.address(), PyObject.class);
                    boolean ok = p instanceof PyObject py && py.str().equals("{'k': 1}");
                    ((PyObject) p).close();
                    return ok;
                }, "{'k': 1}").equals(true));

        // ---- arrays (typed) ----
        check("list -> int[]", Arrays.equals((int[]) typed("[1, 2, 3]", int[].class), new int[]{1, 2, 3}));
        check("tuple -> double[]", Arrays.equals((double[]) typed("(1.5, 2.5)", double[].class), new double[]{1.5, 2.5}));
        Object[] mixed = (Object[]) typed("[1, 'two']", Object[].class);
        check("mixed list -> Object[] with smallest-box ints",
                mixed[0].equals((byte) 1) && mixed[1].equals("two"));
        check("non-sequence -> array is an error", failsTyped("5", int[].class));

        // ---- scalar accessors ----
        check("getIntValue(None) == 0", evalThen(o -> PyLib.getIntValue(o.address()), "None").equals(0));
        check("getLongValue(True) == 1", evalThen(o -> PyLib.getLongValue(o.address()), "True").equals(1L));
        check("getBooleanValue('') == false", evalThen(o -> PyLib.getBooleanValue(o.address()), "''").equals(false));
        check("getStringValue(None) == null", evalThen(o -> PyLib.getStringValue(o.address()), "None") == null);
        boolean strErr;
        try { evalThen(o -> PyLib.getStringValue(o.address()), "5"); strErr = false; }
        catch (Py.PyException e) { strErr = true; }
        check("getStringValue(int) is an error", strErr);

        // ---- Java -> Python ----
        check("null -> None", pyStr(null).equals("None"));
        check("Boolean -> bool", pyStr(true).equals("True"));
        check("Character -> Python int (jpy quirk)", pyStr('A').equals("65"));
        check("Byte -> int", pyStr((byte) 5).equals("5"));
        check("Long -> int", pyStr(42L).equals("42"));
        check("Double -> float", pyStr(3.5).equals("3.5"));
        check("String -> str", pyStr("hi").equals("hi"));
        try (Py.Gil gil = Py.Gil.lock()) {
            MemorySegment obj = Py.eval("object()");
            PyObject wrappedObj = (PyObject) PyLib.convertPythonToJava(obj.address(), PyObject.class);
            PyObject back = PyLib.convertJavaToPython(wrappedObj);
            check("PyObject round trip is pointer-identical", back.getPointer() == obj.address());
            back.close(); wrappedObj.close(); Py.decRef(obj);
        }
        boolean unsupported = false;
        try { PyLib.convertJavaToPython(new Object()); }
        catch (UnsupportedOperationException e) { unsupported = true; }
        check("arbitrary Java object -> declared prototype boundary", unsupported);

        check("clean finalize", Py.stop() == 0);
        System.out.println("M8 OK");
    }

    interface Fn { Object apply(MemorySegment obj); }

    static Object evalThen(Fn fn, String expr) {
        try (Py.Gil gil = Py.Gil.lock()) {
            MemorySegment obj = Py.eval(expr);
            try {
                return fn.apply(obj);
            } finally {
                Py.decRef(obj);
            }
        }
    }

    static Object val(String expr) {
        return evalThen(o -> PyLib.getObjectValue(o.address()), expr);
    }

    static Object typed(String expr, Class<?> target) {
        return evalThen(o -> PyLib.convertPythonToJava(o.address(), target), expr);
    }

    static boolean failsConversion(String expr) {
        try { val(expr); return false; }
        catch (Py.PyException e) { return e.getMessage().startsWith("cannot convert a Python"); }
    }

    static boolean failsTyped(String expr, Class<?> target) {
        try { typed(expr, target); return false; }
        catch (Py.PyException e) { return e.getMessage().startsWith("cannot convert a Python"); }
    }

    static String pyStr(Object javaValue) {
        try (PyObject p = PyLib.convertJavaToPython(javaValue)) {
            return p.str();
        }
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) System.exit(1);
    }
}
