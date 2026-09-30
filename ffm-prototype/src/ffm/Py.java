package ffm;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.*;

/**
 * Minimal FFM bindings to libpython3.12, enough for the jpy-rewrite prototype.
 * Opaque-handle rule: PyObject* is only ever a MemorySegment address; no struct
 * field of a PyObject is ever read directly. The only structs built here are the
 * ones the C API defines for input (PyMethodDef, PyType_Spec, PyType_Slot).
 */
public final class Py {
    public static final String LIBPYTHON =
            System.getProperty("libpython",
                    "/Users/jianfengmao/.local/share/uv/python/cpython-3.12.12-macos-aarch64-none/lib/libpython3.12.dylib");

    static final Linker LINKER = Linker.nativeLinker();
    static final Arena GLOBAL = Arena.global();
    static final SymbolLookup LIB = SymbolLookup.libraryLookup(Path.of(LIBPYTHON), GLOBAL);

    public static final MemorySegment NULL = MemorySegment.NULL;

    // ---- constants from CPython 3.12 headers ----
    public static final int Py_file_input = 257;
    public static final int Py_eval_input = 258;
    public static final int METH_VARARGS = 0x0001;
    public static final int Py_tp_call = 50;
    public static final int Py_tp_init = 60;
    public static final long Py_TPFLAGS_DEFAULT = 0;
    public static final long Py_TPFLAGS_BASETYPE = 1L << 10;

    static MethodHandle dc(String name, FunctionDescriptor fd) {
        return LINKER.downcallHandle(
                LIB.find(name).orElseThrow(() -> new UnsatisfiedLinkError("no symbol " + name)), fd);
    }

    // ---- downcall handles ----
    static final MethodHandle Py_InitializeEx = dc("Py_InitializeEx", FunctionDescriptor.ofVoid(JAVA_INT));
    static final MethodHandle Py_FinalizeEx = dc("Py_FinalizeEx", FunctionDescriptor.of(JAVA_INT));
    static final MethodHandle Py_IsInitialized = dc("Py_IsInitialized", FunctionDescriptor.of(JAVA_INT));
    static final MethodHandle Py_GetVersion = dc("Py_GetVersion", FunctionDescriptor.of(ADDRESS));
    static final MethodHandle PyEval_SaveThread = dc("PyEval_SaveThread", FunctionDescriptor.of(ADDRESS));
    static final MethodHandle PyEval_RestoreThread = dc("PyEval_RestoreThread", FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle PyGILState_Ensure = dc("PyGILState_Ensure", FunctionDescriptor.of(JAVA_INT));
    static final MethodHandle PyGILState_Release = dc("PyGILState_Release", FunctionDescriptor.ofVoid(JAVA_INT));

    static final MethodHandle PyRun_SimpleString = dc("PyRun_SimpleString", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle PyRun_String = dc("PyRun_String",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));

    static final MethodHandle Py_IncRef = dc("Py_IncRef", FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle Py_DecRef = dc("Py_DecRef", FunctionDescriptor.ofVoid(ADDRESS));

    static final MethodHandle PyImport_ImportModule = dc("PyImport_ImportModule", FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle PyImport_AddModule = dc("PyImport_AddModule", FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle PyModule_GetDict = dc("PyModule_GetDict", FunctionDescriptor.of(ADDRESS, ADDRESS));

    static final MethodHandle PyObject_GetAttrString = dc("PyObject_GetAttrString", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle PyObject_SetAttrString = dc("PyObject_SetAttrString", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle PyObject_CallObject = dc("PyObject_CallObject", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle PyObject_Str = dc("PyObject_Str", FunctionDescriptor.of(ADDRESS, ADDRESS));

    static final MethodHandle PyUnicode_FromString = dc("PyUnicode_FromString", FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle PyUnicode_AsUTF8 = dc("PyUnicode_AsUTF8", FunctionDescriptor.of(ADDRESS, ADDRESS));

    static final MethodHandle PyLong_FromLong = dc("PyLong_FromLong", FunctionDescriptor.of(ADDRESS, JAVA_LONG));
    static final MethodHandle PyLong_AsLong = dc("PyLong_AsLong", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    static final MethodHandle PyFloat_AsDouble = dc("PyFloat_AsDouble", FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS));

    static final MethodHandle PyTuple_New = dc("PyTuple_New", FunctionDescriptor.of(ADDRESS, JAVA_LONG));
    static final MethodHandle PyTuple_SetItem = dc("PyTuple_SetItem", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS));
    static final MethodHandle PyTuple_GetItem = dc("PyTuple_GetItem", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
    static final MethodHandle PyTuple_Size = dc("PyTuple_Size", FunctionDescriptor.of(JAVA_LONG, ADDRESS));

    static final MethodHandle PyObject_Type = dc("PyObject_Type", FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle PyObject_IsInstance = dc("PyObject_IsInstance", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    static final MethodHandle PyObject_IsTrue = dc("PyObject_IsTrue", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle PyLong_AsLongLong = dc("PyLong_AsLongLong", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    static final MethodHandle PyBytes_Size = dc("PyBytes_Size", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    static final MethodHandle PyBytes_AsString = dc("PyBytes_AsString", FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle PyList_Size = dc("PyList_Size", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    static final MethodHandle PyList_GetItem = dc("PyList_GetItem", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
    static final MethodHandle PyDict_Items = dc("PyDict_Items", FunctionDescriptor.of(ADDRESS, ADDRESS));

    /** Exported type-object / singleton data symbols: the symbol IS the object. */
    static MemorySegment sym(String name) {
        return LIB.find(name).orElseThrow(() -> new UnsatisfiedLinkError("no symbol " + name));
    }
    public static final MemorySegment Py_None = sym("_Py_NoneStruct");
    public static final MemorySegment PyBool_Type = sym("PyBool_Type");
    public static final MemorySegment PyLong_Type = sym("PyLong_Type");
    public static final MemorySegment PyFloat_Type = sym("PyFloat_Type");
    public static final MemorySegment PyUnicode_Type = sym("PyUnicode_Type");
    public static final MemorySegment PyBytes_Type = sym("PyBytes_Type");
    public static final MemorySegment PyList_Type = sym("PyList_Type");
    public static final MemorySegment PyTuple_Type = sym("PyTuple_Type");
    public static final MemorySegment PyDict_Type = sym("PyDict_Type");

    static final MethodHandle PyErr_Occurred = dc("PyErr_Occurred", FunctionDescriptor.of(ADDRESS));
    static final MethodHandle PyErr_Clear = dc("PyErr_Clear", FunctionDescriptor.ofVoid());
    static final MethodHandle PyErr_Print = dc("PyErr_Print", FunctionDescriptor.ofVoid());
    static final MethodHandle PyErr_SetString = dc("PyErr_SetString", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    static final MethodHandle PyErr_GetRaisedException = dc("PyErr_GetRaisedException", FunctionDescriptor.of(ADDRESS));

    static final MethodHandle PyCFunction_NewEx = dc("PyCFunction_NewEx", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle PyType_FromSpec = dc("PyType_FromSpec", FunctionDescriptor.of(ADDRESS, ADDRESS));

    /** PyExc_RuntimeError is a global PyObject* variable: the symbol is the address of the pointer. */
    public static final MemorySegment PyExc_RuntimeError =
            LIB.find("PyExc_RuntimeError").orElseThrow().reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0);

    // ---- helpers ----

    /** Exception carrying a rendered Python error. */
    public static final class PyException extends RuntimeException {
        public PyException(String msg) { super(msg); }
    }

    static RuntimeException sneaky(Throwable t) {
        return t instanceof RuntimeException r ? r : new RuntimeException(t);
    }

    public static MemorySegment cstr(Arena a, String s) { return a.allocateFrom(s); }

    public static String jstr(MemorySegment cstr) {
        return cstr.reinterpret(Long.MAX_VALUE).getString(0);
    }

    /** After a downcall returned NULL/-1: fetch, render, clear the Python error; throw. */
    public static PyException fetchError() {
        try {
            MemorySegment exc = (MemorySegment) PyErr_GetRaisedException.invokeExact();
            if (exc.equals(NULL)) {
                return new PyException("Python signalled an error but no exception is set");
            }
            MemorySegment str = (MemorySegment) PyObject_Str.invokeExact(exc);
            String msg = str.equals(NULL) ? "<unprintable Python exception>" : jstr(utf8(str));
            if (!str.equals(NULL)) Py_DecRef.invokeExact(str);
            Py_DecRef.invokeExact(exc);
            return new PyException(msg);
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    static MemorySegment utf8(MemorySegment unicode) throws Throwable {
        MemorySegment p = (MemorySegment) PyUnicode_AsUTF8.invokeExact(unicode);
        if (p.equals(NULL)) throw fetchError();
        return p;
    }

    public static MemorySegment checked(MemorySegment result) {
        if (result.equals(NULL)) throw fetchError();
        return result;
    }

    // ---- GIL guard ----
    public static final class Gil implements AutoCloseable {
        final int state;
        Gil(int state) { this.state = state; }
        public static Gil lock() {
            try { return new Gil((int) PyGILState_Ensure.invokeExact()); }
            catch (Throwable t) { throw sneaky(t); }
        }
        @Override public void close() {
            try { PyGILState_Release.invokeExact(state); }
            catch (Throwable t) { throw sneaky(t); }
        }
    }

    // ---- lifecycle ----

    /** Initialize Python and immediately release the GIL so any thread can use Gil.lock(). */
    public static void start() {
        try {
            Py_InitializeEx.invokeExact(0);
            MemorySegment unused = (MemorySegment) PyEval_SaveThread.invokeExact();
        } catch (Throwable t) { throw sneaky(t); }
    }

    /** Re-take the GIL on this thread and finalize. Returns Py_FinalizeEx's status (0 = clean). */
    public static int stop() {
        try {
            int ignored = (int) PyGILState_Ensure.invokeExact();
            return (int) Py_FinalizeEx.invokeExact();
        } catch (Throwable t) { throw sneaky(t); }
    }

    public static String version() {
        try { return jstr((MemorySegment) Py_GetVersion.invokeExact()); }
        catch (Throwable t) { throw sneaky(t); }
    }

    // ---- convenience wrappers used by the milestones (GIL must be held) ----

    public static void exec(String code) {
        try (Arena a = Arena.ofConfined()) {
            int rc = (int) PyRun_SimpleString.invokeExact(cstr(a, code));
            if (rc != 0) throw new PyException("PyRun_SimpleString failed for: " + code);
        } catch (Throwable t) { throw sneaky(t); }
    }

    /** Evaluate an expression in __main__ and return a new reference. */
    public static MemorySegment eval(String expr) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment main = checked((MemorySegment) PyImport_AddModule.invokeExact(cstr(a, "__main__"))); // borrowed
            MemorySegment globals = checked((MemorySegment) PyModule_GetDict.invokeExact(main)); // borrowed
            return checked((MemorySegment) PyRun_String.invokeExact(
                    cstr(a, expr), Py_eval_input, globals, globals));
        } catch (Throwable t) { throw sneaky(t); }
    }

    public static String str(MemorySegment obj) {
        try {
            MemorySegment s = checked((MemorySegment) PyObject_Str.invokeExact(obj));
            String out = jstr(utf8(s));
            Py_DecRef.invokeExact(s);
            return out;
        } catch (Throwable t) { throw sneaky(t); }
    }

    public static long asLong(MemorySegment obj) {
        try {
            long v = (long) PyLong_AsLong.invokeExact(obj);
            if (v == -1) {
                MemorySegment err = (MemorySegment) PyErr_Occurred.invokeExact();
                if (!err.equals(NULL)) throw fetchError();
            }
            return v;
        } catch (Throwable t) { throw sneaky(t); }
    }

    /**
     * Convert a Python number to a Java double. PyFloat_AsDouble accepts any
     * object with __float__/__index__ and signals failure by returning -1.0
     * with the error indicator set.
     */
    public static double asDouble(MemorySegment obj) {
        try {
            double v = (double) PyFloat_AsDouble.invokeExact(obj);
            if (v == -1.0) {
                MemorySegment err = (MemorySegment) PyErr_Occurred.invokeExact();
                if (!err.equals(NULL)) throw fetchError();
            }
            return v;
        } catch (Throwable t) { throw sneaky(t); }
    }

    /** Call a Python callable and narrow the result to a Java primitive float. */
    public static float callFloat(MemorySegment callable, long... args) {
        MemorySegment r = call(callable, args);
        try {
            return (float) asDouble(r);
        } finally {
            decRef(r);
        }
    }

    public static void incRef(MemorySegment obj) {
        try { Py_IncRef.invokeExact(obj); } catch (Throwable t) { throw sneaky(t); }
    }

    public static void decRef(MemorySegment obj) {
        try { Py_DecRef.invokeExact(obj); } catch (Throwable t) { throw sneaky(t); }
    }

    public static MemorySegment importModule(String name) {
        try (Arena a = Arena.ofConfined()) {
            return checked((MemorySegment) PyImport_ImportModule.invokeExact(cstr(a, name)));
        } catch (Throwable t) { throw sneaky(t); }
    }

    public static MemorySegment getAttr(MemorySegment obj, String name) {
        try (Arena a = Arena.ofConfined()) {
            return checked((MemorySegment) PyObject_GetAttrString.invokeExact(obj, cstr(a, name)));
        } catch (Throwable t) { throw sneaky(t); }
    }

    /** Call a Python callable with long arguments; return the result (new reference). */
    public static MemorySegment call(MemorySegment callable, long... args) {
        try {
            MemorySegment tuple = checked((MemorySegment) PyTuple_New.invokeExact((long) args.length));
            for (int i = 0; i < args.length; i++) {
                MemorySegment v = checked((MemorySegment) PyLong_FromLong.invokeExact(args[i]));
                int rc = (int) PyTuple_SetItem.invokeExact(tuple, (long) i, v); // steals ref
                if (rc != 0) throw fetchError();
            }
            MemorySegment result = (MemorySegment) PyObject_CallObject.invokeExact(callable, tuple);
            Py_DecRef.invokeExact(tuple);
            return checked(result);
        } catch (Throwable t) { throw sneaky(t); }
    }

    private Py() {}
}
