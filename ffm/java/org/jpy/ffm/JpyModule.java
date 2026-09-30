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
        };
        MemorySegment[] impls = {stub("getType", PY_CFUNCTION_WITH_KEYWORDS)};
        int[] flags = {CPython.METH_VARARGS | CPython.METH_KEYWORDS};

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
