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

    private static String describePendingPythonError() {
        // Keep it simple: the Python side re-raises with this text.
        CPython.errClear();
        return "a Python error occurred during installation";
    }

    private Bootstrap() {
    }
}
