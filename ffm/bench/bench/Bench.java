package bench;

import org.jpy.PyObject;

/** Java-side loops for the jpy bridge benchmark. Same source for the C jpy and the FFM jpy. */
public class Bench {

    /** A trivial Java method for Python to call. */
    public static int nop() {
        return 0;
    }

    public int inst(int x) {
        return x;
    }

    public static int overloaded(int x) { return 1; }
    public static int overloaded(long x) { return 2; }
    public static int overloaded(double x) { return 3; }
    public static int overloaded(String x) { return 4; }
    public static int overloaded(Object x) { return 5; }

    public static int objects(Object a, Object b, Object c) {
        return 0;
    }

    /** Java calls a Python callable n times; returns ns per call. Result wrappers are closed. */
    public static double callPython(PyObject callable, int n) {
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            try (PyObject r = callable.call("__call__", i)) {
                // drop the result
            }
        }
        return (System.nanoTime() - t0) / (double) n;
    }

    /** Java calls a Python callable n times and converts the result to a Java Integer. */
    public static double callPythonValue(PyObject callable, int n) {
        long t0 = System.nanoTime();
        long sum = 0;
        for (int i = 0; i < n; i++) {
            sum += callable.call(Integer.class, "__call__", Integer.class, i);
        }
        if (sum == 42) System.out.print("");
        return (System.nanoTime() - t0) / (double) n;
    }
}
