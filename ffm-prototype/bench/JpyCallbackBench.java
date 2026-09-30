import org.jpy.PyObject;

/**
 * Measures JNI jpy's Java -> Python call cost: invoking a Python function
 * held as a PyObject, with a boxed int argument, discarding the result.
 * Comparable to the FFM prototype's M5 "Java->Python call py_add" number.
 * Compiled against the jpy 2.1 jar (NOT the FFM prototype's org.jpy).
 */
public class JpyCallbackBench {
    public static double bench(PyObject fn, int iters) {
        Object one = 1;
        for (int i = 0; i < iters / 4; i++) {
            fn.call("__call__", one).close();   // warmup
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < iters; i++) {
            fn.call("__call__", one).close();
        }
        long dt = System.nanoTime() - t0;
        return (double) dt / iters;
    }
}
