package org.jpy.ffm;

/** Classes the bridge treats specially, looked up once. */
final class Constants {

    /** org.jpy.PyObject if present on the class path, else null. */
    static final Class<?> PY_OBJECT_CLASS = tryLoad("org.jpy.PyObject");

    private static Class<?> tryLoad(String name) {
        try {
            return Class.forName(name, false, Constants.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private Constants() {
    }
}
