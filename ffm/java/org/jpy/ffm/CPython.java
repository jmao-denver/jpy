package org.jpy.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * FFM bindings to the CPython 3.12+ C API.
 *
 * Only exported functions and exported data symbols are used. No PyObject or
 * PyTypeObject field is ever read or written, so one build works for every
 * CPython 3.12+ interpreter, including free-threaded ones.
 *
 * Reference conventions follow the C API: "new" results must be decRef'd by
 * the caller, "borrowed" results must not.
 */
public final class CPython {

    static final Linker LINKER = Linker.nativeLinker();
    static final Arena GLOBAL = Arena.global();
    static final SymbolLookup LIB;

    /** Value of jpy.pythonLib that means "look the Python API up in the running process". */
    public static final String PROCESS = "process";

    static {
        String path = System.getProperty("jpy.pythonLib");
        if (path == null || path.isEmpty()) {
            throw new IllegalStateException("system property 'jpy.pythonLib' is not set");
        }
        // jpy.py passes "process" when the running Python has libpython built into its executable.
        LIB = path.equals(PROCESS) ? Dl.processLookup() : SymbolLookup.libraryLookup(Path.of(path), GLOBAL);
    }

    public static final MemorySegment NULL = MemorySegment.NULL;

    // ---- constants (stable ABI values) ----

    public static final int METH_VARARGS = 0x0001;
    public static final int METH_KEYWORDS = 0x0002;
    public static final int METH_NOARGS = 0x0004;
    public static final int METH_O = 0x0008;

    public static final int Py_bf_getbuffer = 1;
    public static final int Py_bf_releasebuffer = 2;
    public static final int Py_sq_ass_item = 39;
    public static final int Py_sq_item = 44;
    public static final int Py_sq_length = 45;
    public static final int Py_tp_call = 50;
    public static final int Py_tp_dealloc = 52;
    public static final int Py_tp_doc = 56;
    public static final int Py_tp_getattro = 58;
    public static final int Py_tp_hash = 59;
    public static final int Py_tp_init = 60;
    public static final int Py_tp_new = 65;
    public static final int Py_tp_repr = 66;
    public static final int Py_tp_richcompare = 67;
    public static final int Py_tp_setattro = 69;
    public static final int Py_tp_str = 70;
    public static final int Py_tp_free = 74;
    public static final int Py_tp_members = 72;
    public static final int Py_tp_methods = 64;
    public static final int Py_tp_getset = 73;

    public static final long Py_TPFLAGS_DISALLOW_INSTANTIATION = 1L << 7;
    /** Type attributes cannot be set or deleted from Python, like the C jpy's static types. Not inherited. */
    public static final long Py_TPFLAGS_IMMUTABLETYPE = 1L << 8;
    public static final long Py_TPFLAGS_BASETYPE = 1L << 10;

    public static final int Py_LT = 0, Py_LE = 1, Py_EQ = 2, Py_NE = 3, Py_GT = 4, Py_GE = 5;

    // ---- symbol helpers ----

    static MethodHandle dc(String name, FunctionDescriptor fd) {
        return LINKER.downcallHandle(
                LIB.find(name).orElseThrow(() -> new UnsatisfiedLinkError("libpython has no symbol " + name)), fd);
    }

    /** Address of an exported data symbol, e.g. a static type object such as PyLong_Type. */
    static MemorySegment sym(String name) {
        return LIB.find(name).orElseThrow(() -> new UnsatisfiedLinkError("libpython has no symbol " + name));
    }

    /** Value of an exported pointer variable, e.g. PyExc_RuntimeError. */
    static MemorySegment ptrSym(String name) {
        return sym(name).reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0);
    }

    // ---- objects ----

    public static final MemorySegment Py_None = sym("_Py_NoneStruct");
    public static final MemorySegment Py_True = sym("_Py_TrueStruct");
    public static final MemorySegment Py_False = sym("_Py_FalseStruct");
    public static final MemorySegment Py_NotImplemented = sym("_Py_NotImplementedStruct");

    public static final MemorySegment PyBool_Type = sym("PyBool_Type");
    public static final MemorySegment PyLong_Type = sym("PyLong_Type");
    public static final MemorySegment PyFloat_Type = sym("PyFloat_Type");
    public static final MemorySegment PyUnicode_Type = sym("PyUnicode_Type");

    public static final MemorySegment PyExc_RuntimeError = ptrSym("PyExc_RuntimeError");
    public static final MemorySegment PyExc_ValueError = ptrSym("PyExc_ValueError");
    public static final MemorySegment PyExc_TypeError = ptrSym("PyExc_TypeError");
    public static final MemorySegment PyExc_AttributeError = ptrSym("PyExc_AttributeError");
    public static final MemorySegment PyExc_IndexError = ptrSym("PyExc_IndexError");

    /** Function pointer used as Py_tp_new for instantiable Java types. */
    public static final MemorySegment PyType_GenericNew_ADDR = sym("PyType_GenericNew");

    /** The `type` type object, base of jpy.JTypeMeta. */
    public static final MemorySegment PyType_Type = sym("PyType_Type");

    // ---- downcall handles ----

    static final MethodHandle Py_IncRef = dc("Py_IncRef", FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle Py_DecRef = dc("Py_DecRef", FunctionDescriptor.ofVoid(ADDRESS));

    static final MethodHandle PyGILState_Ensure = dc("PyGILState_Ensure", FunctionDescriptor.of(JAVA_INT));
    static final MethodHandle PyGILState_Release = dc("PyGILState_Release", FunctionDescriptor.ofVoid(JAVA_INT));
    static final MethodHandle PyEval_SaveThread = dc("PyEval_SaveThread", FunctionDescriptor.of(ADDRESS));
    static final MethodHandle PyEval_RestoreThread = dc("PyEval_RestoreThread", FunctionDescriptor.ofVoid(ADDRESS));

    static final MethodHandle PyObject_Type = dc("PyObject_Type", FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle PyObject_GenericGetAttr = dc("PyObject_GenericGetAttr", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle PyObject_GenericSetAttr = dc("PyObject_GenericSetAttr", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle PyObject_SetAttrString = dc("PyObject_SetAttrString", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle PyObject_Str = dc("PyObject_Str", FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle PyObject_IsInstance = dc("PyObject_IsInstance", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    static final MethodHandle PyObject_IsTrue = dc("PyObject_IsTrue", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle PyObject_Call = dc("PyObject_Call", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    static final MethodHandle PyType_FromSpecWithBases = dc("PyType_FromSpecWithBases", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle PyType_FromMetaclass = dc("PyType_FromMetaclass", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle PyType_GetSlot = dc("PyType_GetSlot", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
    static final MethodHandle PyType_GenericAlloc = dc("PyType_GenericAlloc", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
    static final MethodHandle PyMethod_New = dc("PyMethod_New", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle PyModule_AddFunctions = dc("PyModule_AddFunctions", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    static final MethodHandle PyUnicode_FromStringAndSize = dc("PyUnicode_FromStringAndSize", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
    static final MethodHandle PyUnicode_AsUTF8AndSize = dc("PyUnicode_AsUTF8AndSize", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

    static final MethodHandle PyLong_FromLongLong = dc("PyLong_FromLongLong", FunctionDescriptor.of(ADDRESS, JAVA_LONG));
    static final MethodHandle PyLong_AsLongLong = dc("PyLong_AsLongLong", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    static final MethodHandle PyFloat_FromDouble = dc("PyFloat_FromDouble", FunctionDescriptor.of(ADDRESS, JAVA_DOUBLE));
    static final MethodHandle PyFloat_AsDouble = dc("PyFloat_AsDouble", FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS));
    static final MethodHandle PyNumber_Check = dc("PyNumber_Check", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    static final MethodHandle PyTuple_New = dc("PyTuple_New", FunctionDescriptor.of(ADDRESS, JAVA_LONG));
    static final MethodHandle PyTuple_Size = dc("PyTuple_Size", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    static final MethodHandle PyTuple_GetItem = dc("PyTuple_GetItem", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
    static final MethodHandle PyTuple_SetItem = dc("PyTuple_SetItem", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS));

    static final MethodHandle PyDict_New = dc("PyDict_New", FunctionDescriptor.of(ADDRESS));
    static final MethodHandle PyDict_GetItemString = dc("PyDict_GetItemString", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle PyDict_SetItemString = dc("PyDict_SetItemString", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    static final MethodHandle PySequence_Check = dc("PySequence_Check", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle PySequence_Size = dc("PySequence_Size", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    static final MethodHandle PySequence_GetItem = dc("PySequence_GetItem", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));

    static final MethodHandle PyCallable_Check = dc("PyCallable_Check", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    static final MethodHandle PyObject_CheckBuffer = dc("PyObject_CheckBuffer", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle PyObject_GetBuffer = dc("PyObject_GetBuffer", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
    static final MethodHandle PyBuffer_Release = dc("PyBuffer_Release", FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle PyList_New = dc("PyList_New", FunctionDescriptor.of(ADDRESS, JAVA_LONG));
    static final MethodHandle PyList_SetItem = dc("PyList_SetItem", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS));

    static final MethodHandle PyErr_SetString = dc("PyErr_SetString", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    static final MethodHandle PyErr_Occurred = dc("PyErr_Occurred", FunctionDescriptor.of(ADDRESS));
    static final MethodHandle PyErr_Clear = dc("PyErr_Clear", FunctionDescriptor.ofVoid());
    static final MethodHandle PyErr_ExceptionMatches = dc("PyErr_ExceptionMatches", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** Calls a C function pointer of type void(*)(void*), e.g. a tp_free slot. */
    static final MethodHandle CALL_VOID_PTR = LINKER.downcallHandle(FunctionDescriptor.ofVoid(ADDRESS));

    /** type's own tp_getattro and tp_setattro, which jpy.JTypeMeta's hooks delegate to. */
    static final MethodHandle TYPE_GETATTRO;
    static final MethodHandle TYPE_SETATTRO;

    static {
        try {
            TYPE_GETATTRO = LINKER.downcallHandle(
                    (MemorySegment) PyType_GetSlot.invokeExact(PyType_Type, Py_tp_getattro),
                    FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
            TYPE_SETATTRO = LINKER.downcallHandle(
                    (MemorySegment) PyType_GetSlot.invokeExact(PyType_Type, Py_tp_setattro),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    /** type.__getattribute__(t, name): new reference or NULL with an error set. */
    public static MemorySegment typeGetAttrOrNull(MemorySegment type, MemorySegment name) {
        try {
            return (MemorySegment) TYPE_GETATTRO.invokeExact(type, name);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** type.__setattr__(t, name, value) (value NULL deletes); returns 0 or -1 with an error set. */
    public static int typeSetAttr(MemorySegment type, MemorySegment name, MemorySegment value) {
        try {
            return (int) TYPE_SETATTRO.invokeExact(type, name, value);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static final MethodHandle PyType_GetDict = dc("PyType_GetDict", FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle PyType_Modified = dc("PyType_Modified", FunctionDescriptor.ofVoid(ADDRESS));

    /**
     * Puts an entry into a type's own dict, as the C jpy does with tp_dict. Works on immutable
     * types, where setattr raises TypeError, and bypasses jpy.JTypeMeta's hook. PyType_Modified
     * drops CPython's attribute cache for the type.
     */
    public static void typeDictSet(MemorySegment type, String name, MemorySegment value) {
        try {
            MemorySegment dict = check((MemorySegment) PyType_GetDict.invokeExact(type));
            try {
                dictSet(dict, name, value);
            } finally {
                decRef(dict);
            }
            PyType_Modified.invokeExact(type);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static final MethodHandle PyErr_NoMemory = dc("PyErr_NoMemory", FunctionDescriptor.of(ADDRESS));

    /**
     * A new Java array, or Python's MemoryError if the JVM is out of heap. The C jpy raises
     * MemoryError when JNI's New&lt;Type&gt;Array fails, so the same Java error must not surface
     * as a RuntimeError here.
     */
    public static Object newJavaArray(Class<?> component, int length) {
        try {
            return java.lang.reflect.Array.newInstance(component, length);
        } catch (OutOfMemoryError e) {
            try {
                MemorySegment ignored = (MemorySegment) PyErr_NoMemory.invokeExact();
            } catch (Throwable t) {
                throw rethrow(t);
            }
            throw PyErrAlreadySet.INSTANCE;
        }
    }

    public static boolean errMatches(MemorySegment excType) {
        try {
            return (int) PyErr_ExceptionMatches.invokeExact(excType) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- exceptions used to cross the upcall boundary ----

    /** Thrown when a downcall failed and the Python error indicator is already set. */
    public static final class PyErrAlreadySet extends RuntimeException {
        static final PyErrAlreadySet INSTANCE = new PyErrAlreadySet();

        private PyErrAlreadySet() {
            super("Python error indicator is set", null, false, false);
        }
    }

    /** Thrown to raise a Python exception of the given type with the given message. */
    public static final class PyRaise extends RuntimeException {
        final MemorySegment excType;

        public PyRaise(MemorySegment excType, String message) {
            super(message, null, false, false);
            this.excType = excType;
        }
    }

    public static PyRaise runtimeError(String msg) { return new PyRaise(PyExc_RuntimeError, msg); }
    public static PyRaise valueError(String msg) { return new PyRaise(PyExc_ValueError, msg); }
    public static PyRaise typeError(String msg) { return new PyRaise(PyExc_TypeError, msg); }

    static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) return r;
        if (t instanceof Error e) throw e;
        return new RuntimeException(t);
    }

    /** Sets the Python error indicator for an exception caught at an upcall boundary. */
    public static void setPythonError(Throwable t) {
        if (t instanceof PyErrAlreadySet) {
            return;
        }
        MemorySegment type = t instanceof PyRaise r ? r.excType : PyExc_RuntimeError;
        String msg = t instanceof PyRaise ? t.getMessage() : String.valueOf(t);
        try (Arena a = Arena.ofConfined()) {
            PyErr_SetString.invokeExact(type, a.allocateFrom(msg == null ? "" : msg));
        } catch (Throwable ignored) {
            // nothing more we can do from inside an upcall
        }
    }

    // ---- thin wrappers (GIL must be held) ----

    public static MemorySegment check(MemorySegment result) {
        if (result.equals(NULL)) {
            throw PyErrAlreadySet.INSTANCE;
        }
        return result;
    }

    public static boolean errOccurred() {
        try {
            return !((MemorySegment) PyErr_Occurred.invokeExact()).equals(NULL);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void errClear() {
        try {
            PyErr_Clear.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void incRef(MemorySegment o) {
        try {
            Py_IncRef.invokeExact(o);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void decRef(MemorySegment o) {
        try {
            Py_DecRef.invokeExact(o);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Returns a new reference to None. */
    public static MemorySegment none() {
        incRef(Py_None);
        return Py_None;
    }

    /**
     * Py_REFCNT(o). 3.14 exports it as a function. 3.12 and 3.13 have only the macro, so the count
     * is read from the object header, whose layout differs in free-threaded builds:
     * { Py_ssize_t ob_refcnt; PyTypeObject* ob_type } with the GIL, and
     * { uintptr_t ob_tid; uint16_t ob_flags; PyMutex ob_mutex; uint8_t ob_gc_bits;
     * uint32_t ob_ref_local; Py_ssize_t ob_ref_shared; PyTypeObject* ob_type } without it.
     * GIL held.
     */
    public static long refCount(MemorySegment o) {
        try {
            if (Refcnt.FUNCTION != null) {
                return (long) Refcnt.FUNCTION.invokeExact(o);
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
        MemorySegment header = o.reinterpret(24);
        if (!Refcnt.FREE_THREADED) {
            return header.get(JAVA_LONG, 0);
        }
        int local = header.get(JAVA_INT, 12);
        if (local == -1) {
            // UINT32_MAX: immortal
            return Long.MAX_VALUE;
        }
        return Integer.toUnsignedLong(local) + (header.get(JAVA_LONG, 16) >> 2);
    }

    /** Looked up on first use, when Python is running. */
    private static final class Refcnt {
        static final MethodHandle FUNCTION = LIB.find("Py_REFCNT")
                .map(s -> LINKER.downcallHandle(s, FunctionDescriptor.of(JAVA_LONG, ADDRESS)))
                .orElse(null);
        /** ob_type sits at offset 8 with the GIL and at offset 24 without it. */
        static final boolean FREE_THREADED =
                Py_None.reinterpret(16).get(ADDRESS, 8).address() != typeAddress(Py_None);
    }

    /** The address of the object's type. Borrowed: the object keeps its type alive. */
    public static long typeAddress(MemorySegment o) {
        try {
            MemorySegment t = (MemorySegment) PyObject_Type.invokeExact(o);
            Py_DecRef.invokeExact(t);
            return t.address();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static boolean isInstance(MemorySegment o, MemorySegment type) {
        try {
            int r = (int) PyObject_IsInstance.invokeExact(o, type);
            if (r < 0) throw PyErrAlreadySet.INSTANCE;
            return r != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static boolean isExactType(MemorySegment o, MemorySegment type) {
        return typeAddress(o) == type.address();
    }

    public static boolean isStr(MemorySegment o) {
        return isExactType(o, PyUnicode_Type) || isInstance(o, PyUnicode_Type);
    }

    public static boolean isBool(MemorySegment o) {
        return o.equals(Py_True) || o.equals(Py_False);
    }

    /** PyLong_Check: exact int, bool, or any int subclass. */
    public static boolean isLong(MemorySegment o) {
        return isExactType(o, PyLong_Type) || isBool(o) || isInstance(o, PyLong_Type);
    }

    public static boolean isFloat(MemorySegment o) {
        return isExactType(o, PyFloat_Type) || isInstance(o, PyFloat_Type);
    }

    public static boolean isNumber(MemorySegment o) {
        try {
            return (int) PyNumber_Check.invokeExact(o) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static boolean isSequence(MemorySegment o) {
        try {
            return (int) PySequence_Check.invokeExact(o) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static boolean isCallable(MemorySegment o) {
        try {
            return (int) PyCallable_Check.invokeExact(o) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static boolean isTrue(MemorySegment o) {
        try {
            int r = (int) PyObject_IsTrue.invokeExact(o);
            if (r < 0) throw PyErrAlreadySet.INSTANCE;
            return r != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- strings ----

    /** New reference to a Python str. */
    public static MemorySegment newStr(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        try (Arena a = Arena.ofConfined()) {
            MemorySegment buf = a.allocate(bytes.length + 1L);
            MemorySegment.copy(bytes, 0, buf, JAVA_BYTE, 0, bytes.length);
            return check((MemorySegment) PyUnicode_FromStringAndSize.invokeExact(buf, (long) bytes.length));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** The text of a Python str (TypeError if it is not a str). */
    public static String toJavaString(MemorySegment unicode) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment sizeOut = a.allocate(JAVA_LONG);
            MemorySegment p = check((MemorySegment) PyUnicode_AsUTF8AndSize.invokeExact(unicode, sizeOut));
            long n = sizeOut.get(JAVA_LONG, 0);
            byte[] bytes = p.reinterpret(n).toArray(JAVA_BYTE);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** str(o) as a Java string. */
    public static String str(MemorySegment o) {
        try {
            MemorySegment s = check((MemorySegment) PyObject_Str.invokeExact(o));
            try {
                return toJavaString(s);
            } finally {
                Py_DecRef.invokeExact(s);
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * The object's type name as C's Py_TYPE(o)->tp_name would print it: the
     * full Java name for Java types, __name__ for everything else.
     */
    public static String typeName(MemorySegment o) {
        try {
            MemorySegment t = check((MemorySegment) PyObject_Type.invokeExact(o));
            try {
                JavaType jt = JTypes.byPyType(t.address());
                if (jt != null) {
                    return jt.name;
                }
                MemorySegment key = newStr("__name__");
                MemorySegment qn = (MemorySegment) PyObject_GenericGetAttr.invokeExact(t, key);
                Py_DecRef.invokeExact(key);
                if (qn.equals(NULL)) {
                    errClear();
                    return "?";
                }
                try {
                    return toJavaString(qn);
                } finally {
                    Py_DecRef.invokeExact(qn);
                }
            } finally {
                Py_DecRef.invokeExact(t);
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- numbers ----

    public static MemorySegment newLong(long v) {
        try {
            return check((MemorySegment) PyLong_FromLongLong.invokeExact(v));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static MemorySegment newFloat(double v) {
        try {
            return check((MemorySegment) PyFloat_FromDouble.invokeExact(v));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * A new reference to True or False. Not PyBool_FromLong: its argument is a C long, which is
     * 32 bits on Windows and 64 elsewhere, so no single FFM descriptor fits every platform.
     */
    public static MemorySegment newBool(boolean v) {
        MemorySegment b = v ? Py_True : Py_False;
        incRef(b);
        return b;
    }

    /** PyLong_AsLongLong; raises on overflow or non-int. */
    public static long asLong(MemorySegment o) {
        try {
            long v = (long) PyLong_AsLongLong.invokeExact(o);
            if (v == -1 && errOccurred()) throw PyErrAlreadySet.INSTANCE;
            return v;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * PyLong_AsLongLong the way jpy's JPy_AS_J* macros use it: the error
     * indicator is never checked there, so overflow yields -1. We keep the
     * value but clear the error so it cannot leak into a later call.
     */
    public static long asLongUnchecked(MemorySegment o) {
        try {
            long v = (long) PyLong_AsLongLong.invokeExact(o);
            if (v == -1 && errOccurred()) errClear();
            return v;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static double asDouble(MemorySegment o) {
        try {
            double v = (double) PyFloat_AsDouble.invokeExact(o);
            if (v == -1.0 && errOccurred()) throw PyErrAlreadySet.INSTANCE;
            return v;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- tuples ----

    public static long tupleSize(MemorySegment tuple) {
        try {
            long n = (long) PyTuple_Size.invokeExact(tuple);
            if (n < 0) throw PyErrAlreadySet.INSTANCE;
            return n;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Borrowed reference. */
    public static MemorySegment tupleGet(MemorySegment tuple, long i) {
        try {
            return check((MemorySegment) PyTuple_GetItem.invokeExact(tuple, i));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- attributes ----

    /** PyObject_GenericGetAttr: new reference, or NULL with an error set. */
    public static MemorySegment genericGetAttrOrNull(MemorySegment o, MemorySegment name) {
        try {
            return (MemorySegment) PyObject_GenericGetAttr.invokeExact(o, name);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int genericSetAttr(MemorySegment o, MemorySegment name, MemorySegment value) {
        try {
            return (int) PyObject_GenericSetAttr.invokeExact(o, name, value);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** setattr(o, name, value); does not steal value. */
    public static void setAttr(MemorySegment o, String name, MemorySegment value) {
        try (Arena a = Arena.ofConfined()) {
            int rc = (int) PyObject_SetAttrString.invokeExact(o, a.allocateFrom(name), value);
            if (rc != 0) throw PyErrAlreadySet.INSTANCE;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- dicts ----

    /** Borrowed reference or NULL (no error) if absent. */
    public static MemorySegment dictGet(MemorySegment dict, String key) {
        try (Arena a = Arena.ofConfined()) {
            return (MemorySegment) PyDict_GetItemString.invokeExact(dict, a.allocateFrom(key));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** New reference to an empty dict. */
    public static MemorySegment newDict() {
        try {
            return check((MemorySegment) PyDict_New.invokeExact());
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void dictSet(MemorySegment dict, String key, MemorySegment value) {
        try (Arena a = Arena.ofConfined()) {
            int rc = (int) PyDict_SetItemString.invokeExact(dict, a.allocateFrom(key), value);
            if (rc != 0) throw PyErrAlreadySet.INSTANCE;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- sequences ----

    public static long sequenceSize(MemorySegment o) {
        try {
            long n = (long) PySequence_Size.invokeExact(o);
            if (n < 0) throw PyErrAlreadySet.INSTANCE;
            return n;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** New reference. */
    public static MemorySegment sequenceGet(MemorySegment o, long i) {
        try {
            return check((MemorySegment) PySequence_GetItem.invokeExact(o, i));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- types ----

    /** New reference to an instance of a heap type, with no Java object attached yet. */
    public static MemorySegment allocInstance(MemorySegment type) {
        try {
            return check((MemorySegment) PyType_GenericAlloc.invokeExact(type, 0L));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Frees an object through its type's tp_free and drops the instance's reference to its heap type. */
    public static void freeHeapInstance(MemorySegment self) {
        try {
            MemorySegment type = (MemorySegment) PyObject_Type.invokeExact(self);
            MemorySegment free = (MemorySegment) PyType_GetSlot.invokeExact(type, Py_tp_free);
            CALL_VOID_PTR.invokeExact(free, self);
            Py_DecRef.invokeExact(type); // from PyObject_Type
            Py_DecRef.invokeExact(type); // the instance's own reference to its heap type
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static MemorySegment bindMethod(MemorySegment func, MemorySegment self) {
        try {
            return check((MemorySegment) PyMethod_New.invokeExact(func, self));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static MemorySegment call(MemorySegment callable, MemorySegment args, MemorySegment kwargs) {
        try {
            return check((MemorySegment) PyObject_Call.invokeExact(callable, args, kwargs));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** New tuple; steals the item references. */
    public static MemorySegment newTuple(MemorySegment... items) {
        try {
            MemorySegment t = check((MemorySegment) PyTuple_New.invokeExact((long) items.length));
            for (int i = 0; i < items.length; i++) {
                int rc = (int) PyTuple_SetItem.invokeExact(t, (long) i, items[i]);
                if (rc != 0) throw PyErrAlreadySet.INSTANCE;
            }
            return t;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- buffer protocol (consumer side) ----

    public static final int PyBUF_SIMPLE = 0;
    public static final int PyBUF_WRITABLE = 0x0001;
    public static final int PyBUF_FORMAT = 0x0004;
    /** PyBUF_C_CONTIGUOUS = 0x0020 | PyBUF_STRIDES (0x0010 | PyBUF_ND 0x0008). */
    public static final int PyBUF_C_CONTIGUOUS = 0x0038;

    /**
     * A Py_buffer obtained with PyObject_GetBuffer. Py_buffer is part of the stable ABI
     * since 3.11: { void* buf; PyObject* obj; Py_ssize_t len; Py_ssize_t itemsize; int readonly;
     * int ndim; char* format; Py_ssize_t* shape; Py_ssize_t* strides; Py_ssize_t* suboffsets;
     * void* internal } = 80 bytes on 64-bit platforms.
     */
    public static final class Buffer implements AutoCloseable {
        static final long SIZE = 80;
        private final Arena arena = Arena.ofConfined();
        final MemorySegment view = arena.allocate(SIZE, 8);
        private boolean held;

        /** Returns null (with no error left set) if the object refuses the request. */
        static Buffer get(MemorySegment obj, int flags) {
            Buffer b = new Buffer();
            try {
                if ((int) PyObject_GetBuffer.invokeExact(obj, b.view, flags) != 0) {
                    errClear();
                    b.arena.close();
                    return null;
                }
            } catch (Throwable t) {
                b.arena.close();
                throw rethrow(t);
            }
            b.held = true;
            return b;
        }

        /** Like get, but a refused request raises the Python error PyObject_GetBuffer set. */
        static Buffer getOrRaise(MemorySegment obj, int flags) {
            Buffer b = new Buffer();
            try {
                if ((int) PyObject_GetBuffer.invokeExact(obj, b.view, flags) != 0) {
                    b.arena.close();
                    throw PyErrAlreadySet.INSTANCE;
                }
            } catch (PyErrAlreadySet e) {
                throw e;
            } catch (Throwable t) {
                b.arena.close();
                throw rethrow(t);
            }
            b.held = true;
            return b;
        }

        long len() {
            return view.get(JAVA_LONG, 16);
        }

        long itemsize() {
            return view.get(JAVA_LONG, 24);
        }

        /** The first format character, or 0 if the format was not requested or is NULL. */
        char format() {
            MemorySegment f = view.get(ADDRESS, 40);
            return f.equals(NULL) ? 0 : (char) f.reinterpret(1).get(JAVA_BYTE, 0);
        }

        MemorySegment data() {
            return view.get(ADDRESS, 0).reinterpret(len());
        }

        /** Shared arena guarding segments that may outlive this buffer. Closed before the release. */
        private Arena dataArena;

        /**
         * Like data(), but the segment, and any ByteBuffer made from it, dies when close() runs.
         * A Java access after that throws IllegalStateException. Without it, Java would read the
         * Python object's memory after PyBuffer_Release, which may already be freed or reused.
         */
        MemorySegment scopedData() {
            if (dataArena == null) {
                dataArena = Arena.ofShared();
            }
            return view.get(ADDRESS, 0).reinterpret(len(), dataArena, null);
        }

        @Override
        public void close() {
            if (held) {
                held = false;
                if (dataArena != null) {
                    // Throws if a Java thread holds the segment, for example in a native call. Then
                    // we keep the export and leak it, because releasing would free memory in use.
                    dataArena.close();
                }
                try {
                    PyBuffer_Release.invokeExact(view);
                } catch (Throwable t) {
                    throw rethrow(t);
                } finally {
                    arena.close();
                }
            }
        }
    }

    public static boolean checkBuffer(MemorySegment o) {
        try {
            return (int) PyObject_CheckBuffer.invokeExact(o) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** New list; steals the item references. */
    public static MemorySegment newList(MemorySegment... items) {
        try {
            MemorySegment l = check((MemorySegment) PyList_New.invokeExact((long) items.length));
            for (int i = 0; i < items.length; i++) {
                int rc = (int) PyList_SetItem.invokeExact(l, (long) i, items[i]);
                if (rc != 0) throw PyErrAlreadySet.INSTANCE;
            }
            return l;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private CPython() {
    }
}
