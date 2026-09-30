package ffm;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.Map;

/** Milestone 7: generic Python -> Java conversion driven by the object's type. */
public class M7 {
    public static void main(String[] args) {
        Py.start();
        try (Py.Gil gil = Py.Gil.lock()) {
            check("None -> null", cv("None") == null);
            check("bool -> Boolean", cv("True").equals(Boolean.TRUE) && cv("False").equals(Boolean.FALSE));
            check("int -> Long", cv("42").equals(42L));
            check("negative int", cv("-1").equals(-1L));   // -1 is also the C error sentinel
            check("big-but-fitting int", cv("2**62").equals(1L << 62));
            check("float -> Double", cv("3.5").equals(3.5d));
            check("str -> String", cv("'héllo'").equals("héllo"));
            check("bytes -> byte[]", Arrays.equals((byte[]) cv("b'\\x01\\x02\\xff'"),
                    new byte[]{1, 2, (byte) 0xff}));

            Object[] lst = (Object[]) cv("[1, 'two', 3.0, None, True]");
            check("list -> Object[] mixed",
                    lst.length == 5 && lst[0].equals(1L) && lst[1].equals("two")
                            && lst[2].equals(3.0d) && lst[3] == null && lst[4].equals(Boolean.TRUE));

            Object[] tup = (Object[]) cv("(1, (2, 3))");
            check("tuple -> Object[] nested",
                    tup[0].equals(1L) && Arrays.equals((Object[]) tup[1], new Object[]{2L, 3L}));

            @SuppressWarnings("unchecked")
            Map<Object, Object> map = (Map<Object, Object>) cv("{'a': 1, 2: [True, {'x': None}]}");
            check("dict -> Map recursive", map.get("a").equals(1L)
                    && ((Object[]) map.get(2L))[0].equals(Boolean.TRUE)
                    && ((Map<?, ?>) ((Object[]) map.get(2L))[1]).containsKey("x"));

            // bool subclasses int: must come out Boolean, not Long
            check("bool-before-int ordering", cv("bool(1)") instanceof Boolean);

            // int subclass (IntEnum-like) still converts as a number via isinstance
            Py.exec("import enum\nclass Color(enum.IntEnum):\n    RED = 7\n");
            check("int subclass -> Long", cv("Color.RED").equals(7L));

            // int too big for 64 bits -> Java exception, not silent garbage
            boolean threw = false;
            try { cv("2**100"); } catch (Py.PyException e) { threw = e.getMessage().contains("too big"); }
            check("overflowing int -> PyException", threw);

            // unmapped type -> opaque handle that still works
            Object h = cv("__import__('decimal').Decimal('1.25')");
            check("other -> PyHandle", h instanceof Convert.PyHandle p && p.str().equals("1.25"));
            ((Convert.PyHandle) h).release();

            // end to end: call a Python function, convert whatever comes back
            Py.exec("def stats(n): return {'n': n, 'half': n / 2, 'label': f'v{n}', 'ok': True}");
            MemorySegment stats = Py.eval("stats");
            @SuppressWarnings("unchecked")
            Map<Object, Object> r = (Map<Object, Object>) Convert.callToJava(stats, 9);
            check("call + generic convert", r.get("n").equals(9L) && r.get("half").equals(4.5d)
                    && r.get("label").equals("v9") && r.get("ok").equals(Boolean.TRUE));
            Py.decRef(stats);
        }
        check("clean finalize", Py.stop() == 0);
        System.out.println("M7 OK");
    }

    static Object cv(String expr) {
        MemorySegment obj = Py.eval(expr);
        try {
            return Convert.toJava(obj);
        } finally {
            Py.decRef(obj);
        }
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) System.exit(1);
    }
}
