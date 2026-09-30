package org.jpy;

/**
 * FFM jpy counterpart of the C jpy's PyLibInitializer, called by
 * jpyutil.init_jvm() after the JVM starts. Records where libpython and the
 * jpy module live for Java-to-Python calls.
 * TODO(session 6): wire this into the Java-side PyLib implementation.
 */
public class PyLibInitializer {

    private static volatile String pythonLib;
    private static volatile String jpyLib;

    public static void initPyLib(String pythonLibPath, String jpyLibPath, String jdlLibPath) {
        pythonLib = pythonLibPath;
        jpyLib = jpyLibPath;
    }

    public static String getPythonLib() {
        return pythonLib;
    }

    public static String getJpyLib() {
        return jpyLib;
    }
}
