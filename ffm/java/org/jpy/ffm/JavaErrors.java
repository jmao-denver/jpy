package org.jpy.ffm;

/**
 * Java exception to Python exception, as JPy_HandleJavaException does with
 * verbose exceptions off: a RuntimeError carrying Throwable.toString().
 * TODO(session 7): jpy.VerboseExceptions stack traces and the cause chain.
 */
final class JavaErrors {

    static CPython.PyRaise toPython(Throwable t) {
        if (t == null) {
            return CPython.runtimeError("Java VM exception occurred, no message");
        }
        return CPython.runtimeError(t.toString());
    }

    private JavaErrors() {
    }
}
