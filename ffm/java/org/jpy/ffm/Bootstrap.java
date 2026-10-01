package org.jpy.ffm;

import java.lang.foreign.MemorySegment;

/**
 * Entry point called by the pure-Python jpy module (through the JNI
 * invocation API, via ctypes) right after it created the JVM. Everything
 * after this call is FFM.
 */
public final class Bootstrap {

    private static volatile boolean installed;

    /**
     * Populates the running interpreter's 'jpy' module.
     *
     * @param modulePtr address of the jpy module object (id(module) in CPython)
     */
    public static void install(long modulePtr) {
        if (installed) {
            return;
        }
        try (Gil.Ensured gil = Gil.ensure()) {
            try {
                MemorySegment module = MemorySegment.ofAddress(modulePtr);
                JpyModule.install(module);
                Slots.createModuleTypes(module);
                JTypes.objectType = JTypes.getType(Object.class, false);
                JTypes.classType = JTypes.getType(Class.class, false);
                // Types created before java.lang.Class existed get their jclass attribute now.
                JTypes.addClassAttribute(JTypes.objectType);
                JTypes.addClassAttribute(JTypes.classType);
                installed = true;
            } catch (CPython.PyErrAlreadySet e) {
                String msg = describePendingPythonError();
                throw new IllegalStateException("jpy FFM bootstrap failed: " + msg);
            }
        }
    }

    public static boolean isInstalled() {
        return installed;
    }

    /**
     * Java-first: org.jpy.PyLib started the interpreter and imported the pure-Python jpy module,
     * which only installs the bridge itself from create_jvm(). Marks the module as running inside
     * an existing JVM (so jpy.has_jvm() is true and create_jvm() does nothing), then installs.
     * The GIL must be held.
     */
    static void installEmbedded(MemorySegment module) {
        CPython.setAttr(module, "_embedded", CPython.Py_True);
        install(module.address());
    }

    /**
     * Before Py_Finalize: forgets every Python object the bridge knows, so a restarted interpreter
     * starts from a clean bridge (the C jpy's JPy_free). Nothing is decRef'd: finalization frees it.
     * The GIL must be held.
     */
    static void uninstall() {
        installed = false;
        JTypes.reset();
        JObjects.reset();
        OverloadSet.BY_PYOBJ.clear();
        OverloadSet.JMethod.BY_PYOBJ.clear();
        JFieldInfo.BY_PYOBJ.clear();
        ArrayExports.reset();
        JpyModule.reset();
        Slots.reset();
    }

    private static String describePendingPythonError() {
        // Keep it simple: the Python side re-raises with this text.
        CPython.errClear();
        return "a Python error occurred during installation";
    }

    private Bootstrap() {
    }
}
