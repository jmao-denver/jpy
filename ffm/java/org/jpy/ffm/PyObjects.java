package org.jpy.ffm;

import java.lang.foreign.MemorySegment;

import org.jpy.PyModule;
import org.jpy.PyObject;

/**
 * The bridge's access to org.jpy constructors and members that are package-private: new
 * PyObject(long, boolean), new KeyError(String), new StopIteration(String) and
 * PyDictWrapper.getPointer(). org.jpy.PyLib installs the hooks when it initializes, so
 * org.jpy keeps its public API unchanged.
 */
public final class PyObjects {

    /** Implemented by org.jpy.PyLib. */
    public interface Hooks {
        /** new PyObject(pointer, true): takes over one reference. */
        PyObject newPyObject(long pointer);

        RuntimeException newKeyError(String message);

        RuntimeException newStopIteration(String message);

        /** The dict pointer of an org.jpy.PyDictWrapper, or 0 if o is not one. */
        long dictWrapperPointer(Object o);
    }

    private static volatile Hooks hooks;

    public static void install(Hooks h) {
        hooks = h;
    }

    static Hooks hooks() {
        Hooks h = hooks;
        if (h == null) {
            // Python-first: nothing on the Java side has touched PyLib yet. Initializing it installs the hooks.
            try {
                Class.forName("org.jpy.PyLib", true, PyObjects.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
            h = hooks;
        }
        return h;
    }

    /** JType_CreateJavaPyObject: a new org.jpy.PyObject holding its own reference to o. GIL held. */
    static PyObject wrap(MemorySegment o) {
        CPython.incRef(o);
        return hooks().newPyObject(o.address());
    }

    /** The Python object behind an org.jpy.PyObject or PyModule. Borrowed. */
    static MemorySegment pointer(PyObject o) {
        return MemorySegment.ofAddress(o.getPointer());
    }

    static boolean isPyObjectClass(Class<?> c) {
        return c == PyObject.class || c == PyModule.class;
    }

    private PyObjects() {
    }
}
