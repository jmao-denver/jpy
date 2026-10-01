package org.jpy.ffm;

/**
 * Java exception to Python exception: JPy_HandleJavaException. Always a RuntimeError. Its message
 * is Throwable.toString(), or with jpy.VerboseExceptions.enabled, toString() plus the stack trace
 * and the cause chain in the C jpy's format.
 */
final class JavaErrors {

    /** jpy.VerboseExceptions.enabled (JPy_VerboseExceptions). */
    static volatile boolean verbose;

    private static final String AT_STRING = "\tat ";
    private static final String CAUSED_BY_STRING = "caused by ";

    static CPython.PyRaise toPython(Throwable t) {
        if (t == null) {
            return CPython.runtimeError("Java VM exception occurred, no message");
        }
        if (PyLibImpl.getDiagFlags() != 0) {
            // the C jpy's ExceptionDescribe
            t.printStackTrace();
        }
        return CPython.runtimeError(verbose ? verboseMessage(t) : t.toString());
    }

    /**
     * The C jpy's verbose format: like printStackTrace, but "caused by " in lower case and no
     * suppressed exceptions. A cause's frames shared with the frames of the exception it caused are
     * replaced by "\t... N more".
     */
    static String verboseMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        StackTraceElement[] enclosing = new StackTraceElement[0];
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (!sb.isEmpty()) {
                sb.append(CAUSED_BY_STRING);
            }
            sb.append(cause).append('\n');
            StackTraceElement[] trace = callerFrames(cause.getStackTrace());
            int last = trace.length - 1;
            for (int e = enclosing.length - 1; last >= 0 && e >= 0 && trace[last].equals(enclosing[e]); e--) {
                last--;
            }
            for (int i = 0; i <= last; i++) {
                sb.append(AT_STRING).append(trace[i]).append('\n');
            }
            if (last < trace.length - 1) {
                sb.append("\t... ").append(trace.length - 1 - last).append(" more\n");
            }
            enclosing = trace;
        }
        return sb.toString();
    }

    /**
     * The frames above the bridge. A JNI call starts a fresh Java stack, so the C jpy's traces end
     * at the called method. Here the call comes through the bridge and reflection, so the frames of
     * the first org.jpy.ffm method from the top, and the reflection frames just above it, are cut.
     */
    static StackTraceElement[] callerFrames(StackTraceElement[] trace) {
        int bridge = -1;
        for (int i = 0; i < trace.length; i++) {
            if (trace[i].getClassName().startsWith("org.jpy.ffm.")) {
                bridge = i;
                break;
            }
        }
        if (bridge < 0) {
            return trace;
        }
        int end = bridge;
        while (end > 0 && isReflection(trace[end - 1].getClassName())) {
            end--;
        }
        return java.util.Arrays.copyOf(trace, end);
    }

    private static boolean isReflection(String className) {
        return className.startsWith("jdk.internal.reflect.") || className.startsWith("java.lang.reflect.")
                || className.startsWith("java.lang.invoke.");
    }

    private JavaErrors() {
    }
}
