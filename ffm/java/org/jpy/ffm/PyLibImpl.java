package org.jpy.ffm;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.jpy.PyObject;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.jpy.ffm.CPython.NULL;
import static org.jpy.ffm.CPython.Py_None;
import static org.jpy.ffm.CPython.Py_True;

/**
 * The bodies of org.jpy.PyLib's former native methods, ported from org_jpy_PyLib.c. Pointers are
 * PyObject* addresses as Java longs, exactly as in the C jpy. Each entry point takes the GIL itself
 * (JPy_BEGIN_GIL_STATE) and turns a pending Python error into a Java exception
 * (PyLib_HandlePythonException).
 */
public final class PyLibImpl {

    // Make sure the following constants are same as in enum org.jpy.PyInputMode
    private static final int JPy_IM_STATEMENT = 256;
    private static final int JPy_IM_SCRIPT = 257;
    private static final int Py_single_input = 256;
    private static final int Py_file_input = 257;
    private static final int Py_eval_input = 258;

    private static final String JPY_ERR_BASE_MSG = "Error in Python interpreter";
    private static final String JPY_NO_INFO_MSG = JPY_ERR_BASE_MSG + ", no information available";

    private static final MethodHandle Py_IsInitialized = CPython.dc("Py_IsInitialized", FunctionDescriptor.of(JAVA_INT));
    private static final MethodHandle Py_Initialize = CPython.dc("Py_Initialize", FunctionDescriptor.ofVoid());
    private static final MethodHandle Py_Finalize = CPython.dc("Py_Finalize", FunctionDescriptor.ofVoid());
    private static final MethodHandle Py_GetVersion = CPython.dc("Py_GetVersion", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle Py_DecodeLocale = CPython.dc("Py_DecodeLocale", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    /** 3.13 made Py_IsFinalizing public; 3.12 only has _Py_IsFinalizing. */
    private static final MethodHandle Py_IsFinalizing = optional(FunctionDescriptor.of(JAVA_INT), "Py_IsFinalizing", "_Py_IsFinalizing");
    /** Deprecated since 3.11 and gone in later versions; setPythonHome falls back to PYTHONHOME. */
    private static final MethodHandle Py_SetPythonHome = optional(FunctionDescriptor.ofVoid(ADDRESS), "Py_SetPythonHome");
    private static final MethodHandle Py_SetProgramName = optional(FunctionDescriptor.ofVoid(ADDRESS), "Py_SetProgramName");
    private static final MethodHandle PyGILState_Check = CPython.dc("PyGILState_Check", FunctionDescriptor.of(JAVA_INT));

    private static final MethodHandle PyRun_SimpleStringFlags = CPython.dc("PyRun_SimpleStringFlags", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle PyRun_StringFlags = CPython.dc("PyRun_StringFlags", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle Py_CompileStringExFlags = CPython.dc("Py_CompileStringExFlags", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle PyEval_EvalCode = CPython.dc("PyEval_EvalCode", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    private static final MethodHandle PyImport_AddModule = CPython.dc("PyImport_AddModule", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle PyImport_Import = CPython.dc("PyImport_Import", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle PyImport_ImportModule = CPython.dc("PyImport_ImportModule", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle PyModule_GetDict = CPython.dc("PyModule_GetDict", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle PySys_GetObject = CPython.dc("PySys_GetObject", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle PySys_SetObject = CPython.dc("PySys_SetObject", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /** PEP 667: 3.13 adds the new-reference forms and deprecates the borrowed ones. */
    private static final MethodHandle PyEval_GetFrameGlobals = optional(FunctionDescriptor.of(ADDRESS), "PyEval_GetFrameGlobals");
    private static final MethodHandle PyEval_GetFrameLocals = optional(FunctionDescriptor.of(ADDRESS), "PyEval_GetFrameLocals");
    private static final MethodHandle PyEval_GetGlobals = CPython.dc("PyEval_GetGlobals", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle PyEval_GetLocals = CPython.dc("PyEval_GetLocals", FunctionDescriptor.of(ADDRESS));

    private static final MethodHandle PyDict_Copy = CPython.dc("PyDict_Copy", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle PyDict_Keys = CPython.dc("PyDict_Keys", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle PyDict_Values = CPython.dc("PyDict_Values", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle PyDict_Items = CPython.dc("PyDict_Items", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle PyDict_Contains = CPython.dc("PyDict_Contains", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle PyDict_SetItem = CPython.dc("PyDict_SetItem", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle PyList_Insert = CPython.dc("PyList_Insert", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS));

    private static final MethodHandle PyObject_GetAttrString = CPython.dc("PyObject_GetAttrString", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle PyObject_HasAttrString = CPython.dc("PyObject_HasAttrString", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle PyObject_CallObject = CPython.dc("PyObject_CallObject", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle PyObject_Repr = CPython.dc("PyObject_Repr", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle PyObject_Hash = CPython.dc("PyObject_Hash", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    private static final MethodHandle PyObject_RichCompare = CPython.dc("PyObject_RichCompare", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle PyErr_GetRaisedException = CPython.dc("PyErr_GetRaisedException", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle PyUnicode_Join = CPython.dc("PyUnicode_Join", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

    private static final MemorySegment PyDict_Type = CPython.sym("PyDict_Type");
    private static final MemorySegment PyList_Type = CPython.sym("PyList_Type");
    private static final MemorySegment PyTuple_Type = CPython.sym("PyTuple_Type");
    private static final MemorySegment PyModule_Type = CPython.sym("PyModule_Type");
    private static final MemorySegment PyFunction_Type = CPython.sym("PyFunction_Type");
    private static final MemorySegment PyExc_KeyError = CPython.ptrSym("PyExc_KeyError");
    private static final MemorySegment PyExc_StopIteration = CPython.ptrSym("PyExc_StopIteration");

    private static final Arena FOREVER = Arena.global();

    /** The main thread's state, saved after Py_Initialize so other threads can take the GIL. */
    private static MemorySegment savedThreadState;

    private static volatile int diagFlags;

    private static MethodHandle optional(FunctionDescriptor fd, String... names) {
        for (String name : names) {
            Optional<MemorySegment> s = CPython.LIB.find(name);
            if (s.isPresent()) {
                return CPython.LINKER.downcallHandle(s.get(), fd);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // GIL and errors
    // ------------------------------------------------------------------

    private static boolean isFinalizing() {
        if (Py_IsFinalizing == null) {
            return false;
        }
        try {
            return (int) Py_IsFinalizing.invokeExact() != 0;
        } catch (Throwable t) {
            throw CPython.rethrow(t);
        }
    }

    /**
     * JPy_BEGIN_GIL_STATE. The finalizing check keeps a Java thread from hanging in
     * PyGILState_Ensure while the interpreter shuts down.
     */
    private static Gil.Ensured gil() {
        if (isFinalizing()) {
            throw new RuntimeException("Thread attempted to call into Python during interpreter shutdown");
        }
        return Gil.ensure();
    }

    /**
     * PyLib_HandlePythonException: the pending Python error as the Java exception to throw, or null
     * if no error is pending. The message is "Error in Python interpreter:\n" plus Python's own
     * traceback.format_exception() output, so chained exceptions show up as Python prints them.
     */
    static RuntimeException pythonException() {
        if (!CPython.errOccurred()) {
            return null;
        }
        MemorySegment exc;
        try {
            exc = (MemorySegment) PyErr_GetRaisedException.invokeExact();
        } catch (Throwable t) {
            throw CPython.rethrow(t);
        }
        try {
            String message = JPY_NO_INFO_MSG;
            try {
                message = JPY_ERR_BASE_MSG + ":\n" + formatException(exc);
            } catch (RuntimeException e) {
                CPython.errClear();
            }
            if (CPython.isInstance(exc, PyExc_KeyError)) {
                return PyObjects.hooks().newKeyError(message);
            }
            if (CPython.isInstance(exc, PyExc_StopIteration)) {
                return PyObjects.hooks().newStopIteration(message);
            }
            return new RuntimeException(message);
        } finally {
            CPython.decRef(exc);
            CPython.errClear();
        }
    }

    private static String formatException(MemorySegment exc) {
        MemorySegment tb = importModule0("traceback");
        try {
            MemorySegment fn = getAttr(tb, "format_exception");
            try {
                CPython.incRef(exc);
                MemorySegment lines = CPython.call(fn, CPython.newTuple(exc), NULL);
                try {
                    MemorySegment sep = CPython.newStr("");
                    try {
                        MemorySegment joined = CPython.check((MemorySegment) PyUnicode_Join.invokeExact(sep, lines));
                        try {
                            return CPython.toJavaString(joined);
                        } finally {
                            CPython.decRef(joined);
                        }
                    } finally {
                        CPython.decRef(sep);
                    }
                } finally {
                    CPython.decRef(lines);
                }
            } finally {
                CPython.decRef(fn);
            }
        } catch (Throwable t) {
            throw CPython.rethrow(t);
        } finally {
            CPython.decRef(tb);
        }
    }

    /**
     * Turns a failure inside an entry point into the Java exception to throw. A bridge conversion
     * error (PyRaise) becomes a Python error first, so the caller sees the same
     * "Error in Python interpreter" message the C jpy produces from its Python-side errors.
     */
    private static RuntimeException fail(Throwable t) {
        if (t instanceof CPython.PyRaise || t instanceof CPython.PyErrAlreadySet) {
            CPython.setPythonError(t);
            RuntimeException e = pythonException();
            return e != null ? e : new RuntimeException(JPY_NO_INFO_MSG);
        }
        if (t instanceof RuntimeException r) {
            return r;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new RuntimeException(t);
    }

    private static MemorySegment ptr(long p) {
        return MemorySegment.ofAddress(p);
    }

    // ------------------------------------------------------------------
    // Interpreter life cycle
    // ------------------------------------------------------------------

    public static boolean isInitialized() {
        try {
            return (int) Py_IsInitialized.invokeExact() != 0;
        } catch (Throwable t) {
            throw CPython.rethrow(t);
        }
    }

    public static boolean isPythonRunning() {
        return isInitialized() && Bootstrap.isInstalled();
    }

    public static boolean setPythonHome(String pythonHome) {
        MemorySegment home = decodeLocale(pythonHome);
        if (home == null) {
            return false;
        }
        try {
            if (Py_SetPythonHome != null) {
                Py_SetPythonHome.invokeExact(home);
            } else {
                Dl.setenv("PYTHONHOME", pythonHome);
            }
            return true;
        } catch (Throwable t) {
            throw CPython.rethrow(t);
        }
    }

    public static boolean setProgramName(String programName) {
        MemorySegment name = decodeLocale(programName);
        if (name == null || Py_SetProgramName == null) {
            return false;
        }
        try {
            Py_SetProgramName.invokeExact(name);
            return true;
        } catch (Throwable t) {
            throw CPython.rethrow(t);
        }
    }

    /** Py_DecodeLocale; the C jpy rejects 256 characters or more (its static buffer size). Never freed. */
    private static MemorySegment decodeLocale(String s) {
        if (s == null || s.length() >= 256) {
            return null;
        }
        try {
            MemorySegment w = (MemorySegment) Py_DecodeLocale.invokeExact(FOREVER.allocateFrom(s), NULL);
            return w.equals(NULL) ? null : w;
        } catch (Throwable t) {
            throw CPython.rethrow(t);
        }
    }

    public static String getPythonVersion() {
        try {
            MemorySegment v = (MemorySegment) Py_GetVersion.invokeExact();
            return v.equals(NULL) ? null : v.reinterpret(Long.MAX_VALUE).getString(0);
        } catch (Throwable t) {
            throw CPython.rethrow(t);
        }
    }

    /** Java_org_jpy_PyLib_startPython0. */
    public static boolean startPython(String[] paths) {
        boolean pyInit = isInitialized();
        if (!pyInit) {
            try {
                Py_Initialize.invokeExact();
            } catch (Throwable t) {
                throw CPython.rethrow(t);
            }
            // Py_Initialize already sets sys.argv to [''], what PySys_SetArgvEx(0, NULL, 0) did for the C jpy.
            redirectStdOut();
            pyInit = isInitialized();
            try {
                savedThreadState = (MemorySegment) CPython.PyEval_SaveThread.invokeExact();
            } catch (Throwable t) {
                throw CPython.rethrow(t);
            }
        }

        if (pyInit) {
            if (paths != null && paths.length > 0) {
                try (Gil.Ensured g = gil()) {
                    MemorySegment pathList = sysGet("path");
                    if (!pathList.equals(NULL)) {
                        for (int i = paths.length - 1; i >= 0; i--) {
                            if (paths[i] != null) {
                                MemorySegment p = CPython.newStr(paths[i]);
                                try {
                                    int rc = (int) PyList_Insert.invokeExact(pathList, 0L, p);
                                } catch (Throwable t) {
                                    throw CPython.rethrow(t);
                                } finally {
                                    CPython.decRef(p);
                                }
                            }
                        }
                    }
                }
            }

            if (!Bootstrap.isInstalled()) {
                try (Gil.Ensured g = gil()) {
                    MemorySegment module;
                    try (Arena a = Arena.ofConfined()) {
                        module = (MemorySegment) PyImport_ImportModule.invokeExact(a.allocateFrom("jpy"));
                    } catch (Throwable t) {
                        throw CPython.rethrow(t);
                    }
                    if (module.equals(NULL)) {
                        RuntimeException e = pythonException();
                        if (e != null) {
                            throw e;
                        }
                    } else {
                        try {
                            // Java-first: the pure-Python jpy module does not install the bridge on import.
                            Bootstrap.installEmbedded(module);
                        } finally {
                            CPython.decRef(module);
                        }
                    }
                }
            }
        }

        if (!pyInit) {
            throw new RuntimeException("Failed to initialize Python interpreter.");
        }
        if (!Bootstrap.isInstalled()) {
            throw new RuntimeException("Failed to initialize Python 'jpy' module.");
        }
        return true;
    }

    /** Java_org_jpy_PyLib_stopPython0. */
    public static void stopPython() {
        // Python-first: this JVM did not start the interpreter, so it must neither finalize it nor
        // take the bridge away from it.
        if (isInitialized() && savedThreadState != null) {
            try (Gil.Ensured g = gil()) {
                Bootstrap.uninstall();
            }
            try {
                CPython.PyEval_RestoreThread.invokeExact(savedThreadState);
                savedThreadState = null;
                Py_Finalize.invokeExact();
            } catch (Throwable t) {
                throw CPython.rethrow(t);
            }
        }
    }

    /**
     * PyLib_RedirectStdOut: in embedded mode the C jpy replaces sys.stdout and sys.stderr with a
     * module 'jpy_stdout' whose write() prints to the process's stdout. Kept for parity, written in
     * Python instead of C.
     */
    private static void redirectStdOut() {
        String code = """
                import os as _os, sys as _sys, types as _types
                _m = _types.ModuleType('jpy_stdout', "Redirect 'stdout' to the console in embedded mode")
                def _write(text):
                    "Internal function. Used to print to stdout in embedded mode."
                    if not isinstance(text, str):
                        raise TypeError('argument 1 must be str, not ' + type(text).__name__)
                    _os.write(1, text.encode('utf-8', 'surrogateescape'))
                def _flush():
                    "Internal function. Used to flush to stdout in embedded mode."
                _m.write = _write
                _m.flush = _flush
                _sys.stdout = _m
                _sys.stderr = _m
                """;
        MemorySegment globals = CPython.newDict();
        try (Arena a = Arena.ofConfined()) {
            MemorySegment r = (MemorySegment) PyRun_StringFlags.invokeExact(a.allocateFrom(code), Py_file_input, globals, globals, NULL);
            if (r.equals(NULL)) {
                CPython.errClear();
            } else {
                CPython.decRef(r);
            }
        } catch (Throwable t) {
            throw CPython.rethrow(t);
        } finally {
            CPython.decRef(globals);
        }
    }

    private static MemorySegment sysGet(String name) {
        try (Arena a = Arena.ofConfined()) {
            return (MemorySegment) PySys_GetObject.invokeExact(a.allocateFrom(name));
        } catch (Throwable t) {
            throw CPython.rethrow(t);
        }
    }

    // ------------------------------------------------------------------
    // Running code
    // ------------------------------------------------------------------

    @Deprecated
    public static int execScript(String script) {
        try (Gil.Ensured g = gil(); Arena a = Arena.ofConfined()) {
            // Note that we cannot retrieve last Python exception after a calling PyRun_SimpleString.
            return (int) PyRun_SimpleStringFlags.invokeExact(a.allocateFrom(script), NULL);
        } catch (Throwable t) {
            throw CPython.rethrow(t);
        }
    }

    private interface DoRun {
        MemorySegment run(int start, MemorySegment globals, MemorySegment locals) throws Throwable;
    }

    public static long executeCode(String code, int start, Object globals, Object locals) {
        return executeInternal(start, globals, locals, (s, g, l) -> {
            try (Arena a = Arena.ofConfined()) {
                return (MemorySegment) PyRun_StringFlags.invokeExact(a.allocateFrom(code), s, g, l, NULL);
            }
        });
    }

    /**
     * The C jpy hands a FILE* to PyRun_File. Here the bytes are compiled with the file name, which
     * is the same parse (coding cookie and BOM included) and gives the same tracebacks.
     */
    public static long executeScript(String file, int start, Object globals, Object locals) throws FileNotFoundException {
        byte[] source;
        try {
            source = Files.readAllBytes(new File(file).toPath());
        } catch (IOException e) {
            throw new FileNotFoundException(file);
        }
        return executeInternal(start, globals, locals, (s, g, l) -> {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment src = a.allocate(source.length + 1L);
                MemorySegment.copy(source, 0, src, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, source.length);
                MemorySegment codeObj = (MemorySegment) Py_CompileStringExFlags.invokeExact(src, a.allocateFrom(file), s, NULL, -1);
                if (codeObj.equals(NULL)) {
                    return NULL;
                }
                try {
                    return (MemorySegment) PyEval_EvalCode.invokeExact(codeObj, g, l);
                } finally {
                    CPython.decRef(codeObj);
                }
            }
        });
    }

    /**
     * executeInternal: globals and locals may be a PyObject or PyDictWrapper (used as is), a
     * Map&lt;String, Object&gt; (copied into a new dict and copied back afterwards), or null (the
     * main module's globals; locals default to globals).
     */
    private static long executeInternal(int jStart, Object jGlobals, Object jLocals, DoRun runFunction) {
        try (Gil.Ensured g = gil()) {
            MemorySegment pyGlobals = NULL;
            MemorySegment pyLocals = NULL;
            boolean decGlobals = false, decLocals = false, copyGlobals = false, copyLocals = false;
            MemorySegment result = NULL;
            RuntimeException error = null;
            try {
                if (jGlobals == null) {
                    pyGlobals = mainGlobals();
                    decGlobals = true;
                } else if (jGlobals instanceof PyObject p) {
                    pyGlobals = PyObjects.pointer(p);
                } else if (PyObjects.hooks().dictWrapperPointer(jGlobals) != 0) {
                    pyGlobals = ptr(PyObjects.hooks().dictWrapperPointer(jGlobals));
                } else if (jGlobals instanceof Map<?, ?> m) {
                    pyGlobals = mapToDict(m);
                    if (pyGlobals == null) {
                        pyGlobals = NULL;
                        throw new RuntimeException("Could not convert globals from Java Map to Python dictionary");
                    }
                    copyGlobals = decGlobals = true;
                } else {
                    throw new UnsupportedOperationException("Unsupported globals type");
                }

                if (jLocals == null) {
                    pyLocals = pyGlobals;
                } else if (jLocals instanceof PyObject p) {
                    pyLocals = PyObjects.pointer(p);
                } else if (PyObjects.hooks().dictWrapperPointer(jLocals) != 0) {
                    pyLocals = ptr(PyObjects.hooks().dictWrapperPointer(jLocals));
                } else if (jLocals instanceof Map<?, ?> m) {
                    pyLocals = mapToDict(m);
                    if (pyLocals == null) {
                        pyLocals = NULL;
                        throw new RuntimeException("Could not convert locals from Java Map to Python dictionary");
                    }
                    copyLocals = decLocals = true;
                } else {
                    throw new UnsupportedOperationException("Unsupported locals type");
                }

                int start = jStart == JPy_IM_STATEMENT ? Py_single_input
                        : jStart == JPy_IM_SCRIPT ? Py_file_input
                        : Py_eval_input;
                try {
                    result = runFunction.run(start, pyGlobals, pyLocals);
                } catch (Throwable t) {
                    throw CPython.rethrow(t);
                }
                if (result.equals(NULL)) {
                    error = pythonException();
                }
            } catch (RuntimeException e) {
                error = e instanceof CPython.PyRaise || e instanceof CPython.PyErrAlreadySet ? fail(e) : e;
            } finally {
                // As in the C jpy, the copy-back also runs when the code raised.
                if (copyGlobals) {
                    dictToMap(pyGlobals, (Map<?, ?>) jGlobals);
                }
                if (copyLocals) {
                    dictToMap(pyLocals, (Map<?, ?>) jLocals);
                }
                if (decGlobals && !pyGlobals.equals(NULL)) {
                    CPython.decRef(pyGlobals);
                }
                if (decLocals && !pyLocals.equals(NULL)) {
                    CPython.decRef(pyLocals);
                }
            }
            if (error != null) {
                throw error;
            }
            return result.address();
        }
    }

    /** getMainGlobals(): a new reference to __main__.__dict__. */
    private static MemorySegment mainGlobals() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment main = CPython.check((MemorySegment) PyImport_AddModule.invokeExact(a.allocateFrom("__main__")));
            MemorySegment dict = (MemorySegment) PyModule_GetDict.invokeExact(main);
            CPython.incRef(dict);
            return dict;
        } catch (Throwable t) {
            throw CPython.rethrow(t);
        }
    }

    /** copyJavaStringObjectMapToPyDict: null (no Python error) if a key is not a String. */
    private static MemorySegment mapToDict(Map<?, ?> map) {
        MemorySegment dict = CPython.newDict();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!(e.getKey() instanceof String key)) {
                CPython.decRef(dict);
                return null;
            }
            MemorySegment value = fromJava(e.getValue(), null);
            try {
                CPython.dictSet(dict, key, value);
            } finally {
                CPython.decRef(value);
            }
        }
        return dict;
    }

    /**
     * copyPythonDictToJavaMap: converts every entry first and only then replaces the map's
     * contents. A conversion failure leaves the map unchanged; the C jpy ignores that failure too.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void dictToMap(MemorySegment dict, Map map) {
        if (!CPython.isInstance(dict, PyDict_Type)) {
            throw new UnsupportedOperationException("PyObject is not a dictionary!");
        }
        List<Object> keys = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        MemorySegment items;
        try {
            items = CPython.check((MemorySegment) PyDict_Items.invokeExact(dict));
        } catch (Throwable t) {
            CPython.errClear();
            return;
        }
        try {
            long n = CPython.sequenceSize(items);
            for (long i = 0; i < n; i++) {
                MemorySegment item = CPython.sequenceGet(items, i);
                try {
                    keys.add(Convert.toJavaObject(CPython.tupleGet(item, 0), String.class, false));
                    values.add(Convert.toJavaObject(CPython.tupleGet(item, 1), Object.class, true));
                } finally {
                    CPython.decRef(item);
                }
            }
        } catch (CPython.PyRaise | CPython.PyErrAlreadySet e) {
            CPython.errClear();
            return;
        } finally {
            CPython.decRef(items);
        }
        map.clear();
        for (int i = 0; i < keys.size(); i++) {
            map.put(keys.get(i), values.get(i));
        }
    }

    // ------------------------------------------------------------------
    // Globals, locals and dicts
    // ------------------------------------------------------------------

    public static PyObject getMainGlobals() {
        try (Gil.Ensured g = gil()) {
            try {
                MemorySegment globals = mainGlobals();
                try {
                    return PyObjects.wrap(globals);
                } finally {
                    CPython.decRef(globals);
                }
            } catch (Throwable t) {
                throw fail(t);
            }
        }
    }

    public static PyObject getCurrentGlobals() {
        return currentFrameDict(PyEval_GetFrameGlobals, PyEval_GetGlobals);
    }

    public static PyObject getCurrentLocals() {
        return currentFrameDict(PyEval_GetFrameLocals, PyEval_GetLocals);
    }

    /** Null, with no exception, if no Python frame is executing. */
    private static PyObject currentFrameDict(MethodHandle newRef, MethodHandle borrowed) {
        try (Gil.Ensured g = gil()) {
            try {
                MemorySegment d;
                if (newRef != null) {
                    d = (MemorySegment) newRef.invokeExact();
                } else {
                    d = (MemorySegment) borrowed.invokeExact();
                    if (!d.equals(NULL)) {
                        CPython.incRef(d);
                    }
                }
                if (d.equals(NULL)) {
                    CPython.errClear();
                    return null;
                }
                try {
                    return PyObjects.wrap(d);
                } finally {
                    CPython.decRef(d);
                }
            } catch (Throwable t) {
                throw fail(t);
            }
        }
    }

    public static PyObject copyDict(long pyPointer) {
        return newFromDict(pyPointer, PyDict_Copy);
    }

    public static PyObject pyDictKeys(long pyPointer) {
        return newFromDict(pyPointer, PyDict_Keys);
    }

    public static PyObject pyDictValues(long pyPointer) {
        return newFromDict(pyPointer, PyDict_Values);
    }

    private static PyObject newFromDict(long pyPointer, MethodHandle op) {
        try (Gil.Ensured g = gil()) {
            try {
                MemorySegment src = ptr(pyPointer);
                if (!CPython.isInstance(src, PyDict_Type)) {
                    throw new UnsupportedOperationException("Not a dictionary!");
                }
                MemorySegment r = CPython.check((MemorySegment) op.invokeExact(src));
                try {
                    return PyObjects.wrap(r);
                } finally {
                    CPython.decRef(r);
                }
            } catch (Throwable t) {
                throw fail(t);
            }
        }
    }

    public static PyObject newDict() {
        try (Gil.Ensured g = gil()) {
            try {
                MemorySegment d = CPython.newDict();
                try {
                    return PyObjects.wrap(d);
                } finally {
                    CPython.decRef(d);
                }
            } catch (Throwable t) {
                throw fail(t);
            }
        }
    }

    public static boolean pyDictContains(long dict, Object key, Class<?> keyClass) {
        try (Gil.Ensured g = gil()) {
            try {
                MemorySegment src = ptr(dict);
                if (!CPython.isInstance(src, PyDict_Type)) {
                    throw new UnsupportedOperationException("Not a dictionary!");
                }
                MemorySegment pyKey = fromJava(key, keyClass);
                try {
                    int r = (int) PyDict_Contains.invokeExact(src, pyKey);
                    if (r < 0) {
                        throw CPython.PyErrAlreadySet.INSTANCE;
                    }
                    return r == 1;
                } finally {
                    CPython.decRef(pyKey);
                }
            } catch (Throwable t) {
                throw fail(t);
            }
        }
    }

    // ------------------------------------------------------------------
    // Reference counts
    // ------------------------------------------------------------------

    public static void incRef(long pointer) {
        if (isInitialized()) {
            try (Gil.Ensured g = gil()) {
                CPython.incRef(ptr(pointer));
            }
        }
    }

    public static void decRef(long pointer) {
        if (isInitialized()) {
            try (Gil.Ensured g = gil()) {
                CPython.decRef(ptr(pointer));
            }
        }
    }

    public static void decRefs(long[] pointers, int len) {
        if (isInitialized()) {
            try (Gil.Ensured g = gil()) {
                for (int i = 0; i < len; i++) {
                    CPython.decRef(ptr(pointers[i]));
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Values
    // ------------------------------------------------------------------

    /** JPy_AS_CLONG, cast to int: overflow beyond 32 bits truncates, as in the C jpy. */
    public static int getIntValue(long pointer) {
        return (int) getLongValue(pointer);
    }

    public static long getLongValue(long pointer) {
        try (Gil.Ensured g = gil()) {
            try {
                return CPython.asLong(ptr(pointer));
            } catch (Throwable t) {
                throw fail(t);
            }
        }
    }

    /** False for anything that is not a bool. */
    public static boolean getBooleanValue(long pointer) {
        try (Gil.Ensured g = gil()) {
            return ptr(pointer).equals(Py_True);
        }
    }

    public static double getDoubleValue(long pointer) {
        try (Gil.Ensured g = gil()) {
            try {
                return CPython.asDouble(ptr(pointer));
            } catch (Throwable t) {
                throw fail(t);
            }
        }
    }

    public static String getStringValue(long pointer) {
        try (Gil.Ensured g = gil()) {
            try {
                MemorySegment o = ptr(pointer);
                return o.equals(Py_None) ? null : CPython.toJavaString(o);
            } catch (Throwable t) {
                throw fail(t);
            }
        }
    }

    public static Object getObjectValue(long pointer) {
        try (Gil.Ensured g = gil()) {
            try {
                MemorySegment o = ptr(pointer);
                Object wrapped = JObjects.get(o);
                if (wrapped != null) {
                    return wrapped;
                }
                return Convert.toJavaObject(o, Object.class, false);
            } catch (Throwable t) {
                throw fail(t);
            }
        }
    }

    public static boolean isConvertible(long pointer) {
        try (Gil.Ensured g = gil()) {
            MemorySegment o = ptr(pointer);
            return o.equals(Py_None) || JObjects.get(o) != null || CPython.isBool(o)
                    || CPython.isLong(o) || CPython.isFloat(o) || CPython.isStr(o);
        }
    }

    public static boolean pyNoneCheck(long pointer) {
        return ptr(pointer).equals(Py_None);
    }

    public static boolean pyDictCheck(long pointer) {
        return isInstanceUnderGil(pointer, PyDict_Type);
    }

    public static boolean pyListCheck(long pointer) {
        return isInstanceUnderGil(pointer, PyList_Type);
    }

    public static boolean pyBoolCheck(long pointer) {
        return CPython.isBool(ptr(pointer));
    }

    public static boolean pyIntCheck(long pointer) {
        try (Gil.Ensured g = gil()) {
            return CPython.isLong(ptr(pointer));
        }
    }

    public static boolean pyLongCheck(long pointer) {
        return pyIntCheck(pointer);
    }

    public static boolean pyFloatCheck(long pointer) {
        try (Gil.Ensured g = gil()) {
            return CPython.isFloat(ptr(pointer));
        }
    }

    public static boolean pyStringCheck(long pointer) {
        try (Gil.Ensured g = gil()) {
            return CPython.isStr(ptr(pointer));
        }
    }

    public static boolean pyCallableCheck(long pointer) {
        try (Gil.Ensured g = gil()) {
            return CPython.isCallable(ptr(pointer));
        }
    }

    /** PyFunction_Check is an exact type check. */
    public static boolean pyFunctionCheck(long pointer) {
        try (Gil.Ensured g = gil()) {
            return CPython.isExactType(ptr(pointer), PyFunction_Type);
        }
    }

    public static boolean pyModuleCheck(long pointer) {
        return isInstanceUnderGil(pointer, PyModule_Type);
    }

    public static boolean pyTupleCheck(long pointer) {
        return isInstanceUnderGil(pointer, PyTuple_Type);
    }

    private static boolean isInstanceUnderGil(long pointer, MemorySegment type) {
        try (Gil.Ensured g = gil()) {
            try {
                return CPython.isInstance(ptr(pointer), type);
            } catch (Throwable t) {
                throw fail(t);
            }
        }
    }

    /** A new reference to the object's type. */
    public static long getType(long pointer) {
        try (Gil.Ensured g = gil()) {
            try {
                MemorySegment t = (MemorySegment) CPython.PyObject_Type.invokeExact(ptr(pointer));
                return t.address();
            } catch (Throwable t) {
                throw fail(t);
            }
        }
    }

    public static String str(long pointer) {
        try (Gil.Ensured g = gil()) {
            try {
                return CPython.str(ptr(pointer));
            } catch (Throwable t) {
                throw fail(t);
            }
        }
    }

    /** Unlike str(), a failing repr returns null without an exception, as in the C jpy. */
    public static String repr(long pointer) {
        try (Gil.Ensured g = gil()) {
            try {
                MemorySegment s = (MemorySegment) PyObject_Repr.invokeExact(ptr(pointer));
                if (s.equals(NULL)) {
                    CPython.errClear();
                    return null;
                }
                try {
                    return CPython.toJavaString(s);
                } finally {
                    CPython.decRef(s);
                }
            } catch (Throwable t) {
                throw fail(t);
            }
        }
    }

    public static long hash(long pointer) {
        try (Gil.Ensured g = gil()) {
            try {
                long h = (long) PyObject_Hash.invokeExact(ptr(pointer));
                if (h == -1) {
                    RuntimeException e = pythonException();
                    if (e != null) {
                        throw e;
                    }
                }
                return h;
            } catch (Throwable t) {
                throw fail(t);
            }
        }
    }

    /**
     * PyObject_RichCompare, not PyObject_RichCompareBool: the latter returns true for the same
     * object without calling __eq__, and the C jpy deliberately delegates to __eq__.
     */
    public static boolean eq(long pointer, Object other) {
        try (Gil.Ensured g = gil()) {
            try {
                MemorySegment o2 = fromJava(other, null);
                MemorySegment r;
                try {
                    r = CPython.check((MemorySegment) PyObject_RichCompare.invokeExact(ptr(pointer), o2, CPython.Py_EQ));
                } finally {
                    CPython.decRef(o2);
                }
                try {
                    return CPython.isBool(r) ? r.equals(Py_True) : CPython.isTrue(r);
                } finally {
                    CPython.decRef(r);
                }
            } catch (Throwable t) {
                throw fail(t);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T[] getObjectArrayValue(long pointer, Class<? extends T> itemType) {
        try (Gil.Ensured g = gil()) {
            try {
                MemorySegment o = ptr(pointer);
                if (o.equals(Py_None)) {
                    return null;
                }
                Object wrapped = JObjects.get(o);
                if (wrapped != null) {
                    return (T[]) wrapped;
                }
                if (!CPython.isSequence(o)) {
                    throw new RuntimeException("python object cannot be converted to Object[]");
                }
                int length = (int) CPython.sequenceSize(o);
                Object[] array = (Object[]) java.lang.reflect.Array.newInstance(itemType, length);
                for (int i = 0; i < length; i++) {
                    MemorySegment item = CPython.sequenceGet(o, i);
                    try {
                        // a plain array store, so a wrong item type throws ArrayStoreException as JNI does
                        array[i] = Convert.toJavaObject(item, Object.class, false);
                    } finally {
                        CPython.decRef(item);
                    }
                }
                return (T[]) array;
            } catch (Throwable t) {
                throw fail(t);
            }
        }
    }

    // ------------------------------------------------------------------
    // Modules and attributes
    // ------------------------------------------------------------------

    public static long importModule(String name) {
        try (Gil.Ensured g = gil()) {
            try {
                return importModule0(name).address();
            } catch (Throwable t) {
                throw fail(t);
            }
        }
    }

    /** PyImport_Import: new reference. */
    private static MemorySegment importModule0(String name) {
        MemorySegment pyName = CPython.newStr(name);
        try {
            return CPython.check((MemorySegment) PyImport_Import.invokeExact(pyName));
        } catch (Throwable t) {
            throw CPython.rethrow(t);
        } finally {
            CPython.decRef(pyName);
        }
    }

    /** PyObject_GetAttrString: new reference. */
    private static MemorySegment getAttr(MemorySegment o, String name) {
        try (Arena a = Arena.ofConfined()) {
            return CPython.check((MemorySegment) PyObject_GetAttrString.invokeExact(o, a.allocateFrom(name)));
        } catch (Throwable t) {
            throw CPython.rethrow(t);
        }
    }

    public static long getAttributeObject(long pointer, String name) {
        try (Gil.Ensured g = gil()) {
            try {
                return getAttr(ptr(pointer), name).address();
            } catch (Throwable t) {
                throw fail(t);
            }
        }
    }

    public static <T> T getAttributeValue(long pointer, String name, Class<? extends T> valueType) {
        try (Gil.Ensured g = gil()) {
            try {
                MemorySegment v = getAttr(ptr(pointer), name);
                try {
                    return toJavaWithClass(v, valueType);
                } finally {
                    CPython.decRef(v);
                }
            } catch (Throwable t) {
                throw fail(t);
            }
        }
    }

    public static <T> void setAttributeValue(long pointer, String name, T value, Class<? extends T> valueType) {
        try (Gil.Ensured g = gil()) {
            try {
                MemorySegment v = fromJava(value, valueType);
                try {
                    CPython.setAttr(ptr(pointer), name, v);
                } finally {
                    CPython.decRef(v);
                }
            } catch (Throwable t) {
                throw fail(t);
            }
        }
    }

    public static void delAttribute(long pointer, String name) {
        try (Gil.Ensured g = gil(); Arena a = Arena.ofConfined()) {
            try {
                // PyObject_DelAttrString is a macro for this before 3.13
                int rc = (int) CPython.PyObject_SetAttrString.invokeExact(ptr(pointer), a.allocateFrom(name), NULL);
                if (rc < 0) {
                    throw CPython.PyErrAlreadySet.INSTANCE;
                }
            } catch (Throwable t) {
                throw fail(t);
            }
        }
    }

    public static boolean hasAttribute(long pointer, String name) {
        try (Gil.Ensured g = gil(); Arena a = Arena.ofConfined()) {
            try {
                return (int) PyObject_HasAttrString.invokeExact(ptr(pointer), a.allocateFrom(name)) != 0;
            } catch (Throwable t) {
                throw fail(t);
            }
        }
    }

    // ------------------------------------------------------------------
    // GIL
    // ------------------------------------------------------------------

    /** No GIL needed to ask whether we hold the GIL. */
    public static boolean hasGil() {
        try {
            return (int) PyGILState_Check.invokeExact() != 0;
        } catch (Throwable t) {
            throw CPython.rethrow(t);
        }
    }

    public static <T> T ensureGil(Supplier<T> supplier) {
        try (Gil.Ensured g = gil()) {
            return supplier.get();
        }
    }

    // ------------------------------------------------------------------
    // Calls
    // ------------------------------------------------------------------

    public static long callAndReturnObject(long pointer, boolean methodCall, String name, int argCount,
                                           Object[] args, Class<?>[] paramTypes) {
        try (Gil.Ensured g = gil()) {
            try {
                return callAndReturnObject0(ptr(pointer), name, argCount, args, paramTypes).address();
            } catch (Throwable t) {
                throw fail(t);
            }
        }
    }

    public static <T> T callAndReturnValue(long pointer, boolean methodCall, String name, int argCount,
                                           Object[] args, Class<?>[] paramTypes, Class<T> returnType) {
        try (Gil.Ensured g = gil()) {
            try {
                MemorySegment r = callAndReturnObject0(ptr(pointer), name, argCount, args, paramTypes);
                if (r.equals(NULL)) {
                    return null;
                }
                try {
                    return toJavaWithClass(r, returnType);
                } finally {
                    CPython.decRef(r);
                }
            } catch (Throwable t) {
                throw fail(t);
            }
        }
    }

    /**
     * PyLib_CallAndReturnObject: getattr(o, name)(*args). Method calls need no special case: the
     * attribute lookup already returns a bound method.
     */
    private static MemorySegment callAndReturnObject0(MemorySegment o, String name, int argCount,
                                                      Object[] args, Class<?>[] paramTypes) throws Throwable {
        MemorySegment callable = getAttr(o, name);
        try {
            if (!CPython.isCallable(callable)) {
                // The C jpy calls PyLib_HandlePythonException here with no error set, so nothing is
                // thrown: the call returns 0 (callAndReturnObject) or null (callAndReturnValue).
                return NULL;
            }
            MemorySegment[] items = new MemorySegment[argCount];
            int made = 0;
            try {
                for (; made < argCount; made++) {
                    Class<?> paramType = paramTypes != null ? paramTypes[made] : null;
                    items[made] = fromJava(args[made], paramType);
                }
            } catch (Throwable t) {
                for (int i = 0; i < made; i++) {
                    CPython.decRef(items[i]);
                }
                throw t;
            }
            MemorySegment tuple = CPython.newTuple(items);
            try {
                // A local, not a ?: argument: a conditional passed to invokeExact is typed Object.
                MemorySegment callArgs = argCount > 0 ? tuple : NULL;
                return CPython.check((MemorySegment) PyObject_CallObject.invokeExact(callable, callArgs));
            } finally {
                CPython.decRef(tuple);
            }
        } finally {
            CPython.decRef(callable);
        }
    }

    // ------------------------------------------------------------------
    // Conversions
    // ------------------------------------------------------------------

    /**
     * JPy_FromJObject / JPy_FromJObjectWithType: a new reference. With no explicit class, the
     * object's runtime class decides the conversion.
     */
    static MemorySegment fromJava(Object value, Class<?> explicitClass) {
        if (value == null) {
            return CPython.none();
        }
        return Convert.toPython(value, explicitClass != null ? explicitClass : value.getClass());
    }

    /** JPy_AsJObjectWithClass: None is null; no class means java.lang.Object; no PyObject wrapping. */
    @SuppressWarnings("unchecked")
    private static <T> T toJavaWithClass(MemorySegment v, Class<? extends T> c) {
        if (v.equals(Py_None)) {
            return null;
        }
        return (T) Convert.toJavaObject(v, c != null ? c : Object.class, false);
    }

    // ------------------------------------------------------------------
    // Diag
    // ------------------------------------------------------------------

    public static int getDiagFlags() {
        return diagFlags;
    }

    public static void setDiagFlags(int flags) {
        diagFlags = flags;
    }

    private PyLibImpl() {
    }
}
