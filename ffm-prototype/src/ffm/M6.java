package ffm;

import java.lang.foreign.MemorySegment;

/**
 * Milestone 6: typed conversion — call a Python function returning a float,
 * receive a Java primitive float (the FFM equivalent of PyLib.getDoubleValue).
 */
public class M6 {
    public static void main(String[] args) {
        Py.start();
        try (Py.Gil gil = Py.Gil.lock()) {
            Py.exec("""
                import math
                def circle_area(r):
                    return math.pi * r * r
                def bad():
                    return "not a number"
                """);
            MemorySegment circleArea = Py.eval("circle_area");

            float area = Py.callFloat(circleArea, 3);          // Python float -> Java float
            check("float conversion: circle_area(3) = " + area,
                    Math.abs(area - 28.274334f) < 1e-4f);

            float doubled = area * 2.0f;                        // plain Java float arithmetic
            check("Java arithmetic on it: * 2 = " + doubled, doubled > 56.5f && doubled < 56.6f);

            MemorySegment intFn = Py.eval("(lambda: 7)");
            check("Python int also converts: " + Py.callFloat(intFn),
                    Py.callFloat(intFn) == 7.0f);
            Py.decRef(intFn);

            boolean threw = false;
            MemorySegment bad = Py.eval("bad");
            try { Py.callFloat(bad); }
            catch (Py.PyException e) { threw = e.getMessage().contains("must be a") || e.getMessage().contains("str"); }
            check("non-numeric result -> Java exception", threw);
            Py.decRef(bad);
            Py.decRef(circleArea);
        }
        check("clean finalize", Py.stop() == 0);
        System.out.println("M6 OK");
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) System.exit(1);
    }
}
