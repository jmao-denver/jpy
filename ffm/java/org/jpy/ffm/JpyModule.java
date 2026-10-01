package org.jpy.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.jpy.ffm.CPython.NULL;

/**
 * The Java-implemented functions of the Python 'jpy' module, and the module
 * dicts the bridge reads (jpy.types, jpy.type_callbacks, jpy.type_translations).
 */
final class JpyModule {

    static MemorySegment module;
    /** jpy.types: Java type name -> Python type. */
    static MemorySegment types;
    static MemorySegment typeCallbacks;
    static MemorySegment typeTranslations;

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private static MemorySegment stub(String method, FunctionDescriptor fd) {
        try {
            return Linker.nativeLinker().upcallStub(
                    LOOKUP.findStatic(JpyModule.class, method, fd.toMethodType()), fd, Arena.global());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final FunctionDescriptor PY_CFUNCTION_WITH_KEYWORDS = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor PY_CFUNCTION = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS);

    /** Called once with the GIL held, from Bootstrap.install. */
    static void install(MemorySegment jpyModule) {
        module = jpyModule;
        types = requiredDict("types");
        typeCallbacks = requiredDict("type_callbacks");
        typeTranslations = requiredDict("type_translations");

        // PyMethodDef[] { name, meth, flags, doc } + sentinel; must live as long as the module.
        String[][] defs = {
                {"get_type", "get_type(name, resolve=True) - Return the Java class with the given name, e.g. 'java.io.File'. "
                        + "Loads the Java class from the JVM if not already done. Optionally avoids resolving the class' methods."},
                {"cast", "cast(obj, type) - Cast the given Java object to the given Java type (type name or type object). "
                        + "Returns None if the cast is not possible."},
                {"convert", "convert(obj, type) - Convert the given Python object to the given Java type (type name or type object). "
                        + "Returns None if the conversion is not possible. If the Java type is a primitive, the returned object "
                        + "will be of the corresponding boxed type."},
                {"array", "array(name, init) - Return a new Java array of given Java type (type name or type object) and initializer "
                        + "(array length or sequence). Possible primitive types are 'boolean', 'byte', 'char', 'short', 'int', "
                        + "'long', 'float', and 'double'."},
        };
        MemorySegment[] impls = {
                stub("getType", PY_CFUNCTION_WITH_KEYWORDS),
                stub("cast", PY_CFUNCTION),
                stub("convert", PY_CFUNCTION),
                stub("array", PY_CFUNCTION),
        };
        int[] flags = {
                CPython.METH_VARARGS | CPython.METH_KEYWORDS,
                CPython.METH_VARARGS,
                CPython.METH_VARARGS,
                CPython.METH_VARARGS,
        };

        Arena forever = Arena.global();
        MemorySegment table = forever.allocate(32L * (defs.length + 1), 8);
        for (int i = 0; i < defs.length; i++) {
            long off = 32L * i;
            table.set(ADDRESS, off, forever.allocateFrom(defs[i][0]));
            table.set(ADDRESS, off + 8, impls[i]);
            table.set(JAVA_INT, off + 16, flags[i]);
            table.set(ADDRESS, off + 24, forever.allocateFrom(defs[i][1]));
        }
        try {
            int rc = (int) CPython.PyModule_AddFunctions.invokeExact(module, table);
            if (rc != 0) throw CPython.PyErrAlreadySet.INSTANCE;
        } catch (Throwable t) {
            throw CPython.rethrow(t);
        }
    }

    private static MemorySegment requiredDict(String attr) {
        MemorySegment key = CPython.newStr(attr);
        try {
            MemorySegment d = CPython.genericGetAttrOrNull(module, key);
            if (d.equals(NULL)) {
                throw CPython.PyErrAlreadySet.INSTANCE;
            }
            return d; // keep the reference for the life of the process
        } finally {
            CPython.decRef(key);
        }
    }

    /** The callable in jpy.type_translations for a Java type name, borrowed, or null. */
    static MemorySegment typeTranslation(String javaName) {
        if (typeTranslations == null) {
            return null;
        }
        MemorySegment cb = CPython.dictGet(typeTranslations, javaName);
        if (cb.equals(NULL) || !CPython.isCallable(cb)) {
            return null;
        }
        return cb;
    }

    // ------------------------------------------------------------------
    // Module functions
    // ------------------------------------------------------------------

    /** jpy.get_type(name, resolve=True), argument parsing as PyArg_ParseTupleAndKeywords("s|i"). */
    static MemorySegment getType(MemorySegment self, MemorySegment args, MemorySegment kwds) {
        try {
            long n = CPython.tupleSize(args);
            MemorySegment nameObj = n > 0 ? CPython.tupleGet(args, 0) : keyword(kwds, "name");
            MemorySegment resolveObj = n > 1 ? CPython.tupleGet(args, 1) : keyword(kwds, "resolve");
            if (nameObj == null) {
                throw CPython.typeError("get_type() missing required argument 'name' (pos 1)");
            }
            if (n > 2) {
                throw CPython.typeError("get_type() takes at most 2 arguments (" + n + " given)");
            }
            if (!CPython.isStr(nameObj)) {
                throw CPython.typeError("get_type() argument 1 must be str, not " + CPython.typeName(nameObj));
            }
            boolean resolve = true;
            if (resolveObj != null) {
                if (!CPython.isLong(resolveObj)) {
                    throw CPython.typeError("'" + CPython.typeName(resolveObj) + "' object cannot be interpreted as an integer");
                }
                resolve = CPython.asLong(resolveObj) != 0;
            }
            JavaType jt = JTypes.getTypeForName(CPython.toJavaString(nameObj), resolve);
            CPython.incRef(jt.pyType);
            return jt.pyType;
        } catch (Throwable t) {
            CPython.setPythonError(t);
            return NULL;
        }
    }

    /** PyArg_ParseTuple(args, "OO:<fn>"): exactly two positional arguments, borrowed. */
    private static MemorySegment[] twoArgs(MemorySegment args, String fn) {
        long n = CPython.tupleSize(args);
        if (n != 2) {
            throw CPython.typeError(fn + "() takes exactly 2 arguments (" + n + " given)");
        }
        return new MemorySegment[]{CPython.tupleGet(args, 0), CPython.tupleGet(args, 1)};
    }

    /** A type argument given as a Java type name or a Java type object (not resolved). */
    private static JavaType typeArg(MemorySegment arg, String errorMessage) {
        if (CPython.isStr(arg)) {
            return JTypes.getTypeForName(CPython.toJavaString(arg), false);
        }
        JavaType jt = JTypes.byPyType(arg.address());
        if (jt == null) {
            throw CPython.valueError(errorMessage);
        }
        return jt;
    }

    /** JObj_New: wrap with the object's runtime type, resolved. */
    private static MemorySegment wrapRuntime(Object o) {
        return JObjects.wrap(o, JTypes.getType(o.getClass(), true));
    }

    /** jpy.cast(obj, type): JPy_cast_internal. */
    static MemorySegment cast(MemorySegment self, MemorySegment args) {
        try {
            MemorySegment[] a = twoArgs(args, "cast");
            if (a[0].equals(CPython.Py_None)) {
                return CPython.none();
            }
            Object o = JObjects.get(a[0]);
            if (o == null) {
                throw CPython.valueError("cast: argument 1 (obj) must be a Java object");
            }
            JavaType target = typeArg(a[1], "cast: argument 2 (obj_type) must be a Java type name or Java type object");
            return target.clazz.isInstance(o) ? JObjects.wrap(o, target) : CPython.none();
        } catch (Throwable t) {
            CPython.setPythonError(t);
            return NULL;
        }
    }

    /** jpy.convert(obj, type): JPy_convert_internal. */
    static MemorySegment convert(MemorySegment self, MemorySegment args) {
        try {
            MemorySegment[] a = twoArgs(args, "convert");
            if (a[0].equals(CPython.Py_None)) {
                return CPython.none();
            }
            JavaType target = typeArg(a[1], "cast: argument 2 (obj_type) must be a Java type name or Java type object");
            Object o = JObjects.get(a[0]);
            if (o != null && target.clazz.isInstance(o)) {
                return JObjects.wrap(o, target);
            }
            return JObjects.wrap(Convert.toJavaObject(a[0], target.clazz, false), target);
        } catch (Throwable t) {
            CPython.setPythonError(t);
            return NULL;
        }
    }

    /** jpy.array(type, init): JPy_array_internal. */
    static MemorySegment array(MemorySegment self, MemorySegment args) {
        try {
            MemorySegment[] a = twoArgs(args, "array");
            JavaType component = typeArg(a[0], "array: argument 1 (type) must be a type name or Java type object");
            if (component.clazz == void.class) {
                throw CPython.valueError("array: argument 1 (type) must not be 'void'");
            }
            String initError = "array: argument 2 (init) must be either an integer array length or any sequence";
            if (CPython.isLong(a[1])) {
                int length = (int) CPython.asLongUnchecked(a[1]);
                if (length < 0) {
                    throw CPython.valueError(initError);
                }
                return wrapRuntime(java.lang.reflect.Array.newInstance(component.clazz, length));
            }
            if (CPython.isSequence(a[1])) {
                return wrapRuntime(Convert.toJavaArray(a[1], component.clazz, false));
            }
            throw CPython.valueError(initError);
        } catch (Throwable t) {
            CPython.setPythonError(t);
            return NULL;
        }
    }

    /** Borrowed keyword argument or null. */
    private static MemorySegment keyword(MemorySegment kwds, String key) {
        if (kwds.equals(NULL)) {
            return null;
        }
        MemorySegment v = CPython.dictGet(kwds, key);
        return v.equals(NULL) ? null : v;
    }

    private JpyModule() {
    }
}
