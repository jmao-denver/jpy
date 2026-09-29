package ffm;

import java.lang.foreign.MemorySegment;

/**
 * Milestone 2: FFM equivalents of five representative PyLib natives:
 * executeCode, getStringValue, importModule+getAttributeObject,
 * callAndReturnObject, incRef/decRef. Plus error translation.
 */
public class M2 {
    public static void main(String[] args) {
        Py.start();
        try (Py.Gil gil = Py.Gil.lock()) {
            // executeCode
            Py.exec("x = 6 * 7");
            MemorySegment x = Py.eval("x");
            check("executeCode/eval", Py.asLong(x) == 42);
            Py.decRef(x);

            // getStringValue
            MemorySegment s = Py.eval("'he' + 'llo'");
            check("getStringValue", Py.str(s).equals("hello"));
            Py.decRef(s);

            // importModule + getAttributeObject + callAndReturnObject
            MemorySegment math = Py.importModule("math");
            MemorySegment gcd = Py.getAttr(math, "gcd");
            MemorySegment result = Py.call(gcd, 12, 18);
            check("call math.gcd(12,18)", Py.asLong(result) == 6);
            Py.decRef(result); Py.decRef(gcd); Py.decRef(math);

            // incRef/decRef observable via sys.getrefcount
            MemorySegment obj = Py.eval("object()");
            MemorySegment sys = Py.importModule("sys");
            MemorySegment getrefcount = Py.getAttr(sys, "getrefcount");
            long before = countRefs(getrefcount, obj);
            Py.incRef(obj); Py.incRef(obj);
            long after = countRefs(getrefcount, obj);
            check("incRef visible (" + before + "->" + after + ")", after == before + 2);
            Py.decRef(obj); Py.decRef(obj);
            long restored = countRefs(getrefcount, obj);
            check("decRef visible", restored == before);
            Py.decRef(getrefcount); Py.decRef(sys); Py.decRef(obj);

            // error translation: Python exception -> Java exception
            boolean threw = false;
            try { Py.eval("1/0"); }
            catch (Py.PyException e) { threw = e.getMessage().contains("division by zero"); }
            check("python error becomes Java exception", threw);
        }
        int rc = Py.stop();
        check("clean finalize", rc == 0);
        System.out.println("M2 OK");
    }

    static long countRefs(MemorySegment getrefcount, MemorySegment obj) {
        try {
            MemorySegment tuple = Py.checked((MemorySegment) Py.PyTuple_New.invokeExact(1L));
            Py.incRef(obj); // PyTuple_SetItem steals
            int rc = (int) Py.PyTuple_SetItem.invokeExact(tuple, 0L, obj);
            if (rc != 0) throw Py.fetchError();
            MemorySegment r = Py.checked((MemorySegment) Py.PyObject_CallObject.invokeExact(getrefcount, tuple));
            long v = Py.asLong(r);
            Py.decRef(r); Py.decRef(tuple);
            return v;
        } catch (Throwable t) { throw Py.sneaky(t); }
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) System.exit(1);
    }
}
