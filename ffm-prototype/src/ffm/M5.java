package ffm;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicLong;

import static java.lang.foreign.ValueLayout.*;

/** Milestone 5: measure per-call costs. Simple nanoTime loops with warmup. */
public class M5 {
    static final AtomicLong SINK = new AtomicLong();
    static final Arena ARENA = Arena.global();

    static MemorySegment javaAdd(MemorySegment self, MemorySegment args) {
        try {
            MemorySegment a = (MemorySegment) Py.PyTuple_GetItem.invokeExact(args, 0L);
            long va = (long) Py.PyLong_AsLong.invokeExact(a);
            SINK.addAndGet(va);
            return (MemorySegment) Py.PyLong_FromLong.invokeExact(va + 1);
        } catch (Throwable t) {
            return Py.NULL;
        }
    }

    /**
     * METH_FASTCALL impl: args arrive as a C array of PyObject*; one downcall
     * per arg is replaced by direct segment reads of the pointer array.
     */
    static MemorySegment javaAddFast(MemorySegment self, MemorySegment args, long nargs) {
        try {
            MemorySegment a0 = args.reinterpret(nargs * 8).getAtIndex(ADDRESS, 0);
            long va = (long) Py.PyLong_AsLong.invokeExact(a0);
            SINK.addAndGet(va);
            return (MemorySegment) Py.PyLong_FromLong.invokeExact(va + 1);
        } catch (Throwable t) {
            return Py.NULL;
        }
    }

    public static void main(String[] argv) throws Throwable {
        Py.start();
        MemorySegment addStub = Linker.nativeLinker().upcallStub(
                MethodHandles.lookup().findStatic(M5.class, "javaAdd",
                        MethodType.methodType(MemorySegment.class, MemorySegment.class, MemorySegment.class)),
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS), ARENA);
        MemorySegment fastStub = Linker.nativeLinker().upcallStub(
                MethodHandles.lookup().findStatic(M5.class, "javaAddFast",
                        MethodType.methodType(MemorySegment.class, MemorySegment.class, MemorySegment.class, long.class)),
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG), ARENA);

        try (Py.Gil gil = Py.Gil.lock()) {
            // install java_add
            MemorySegment def = ARENA.allocate(32);
            def.set(ADDRESS, 0, Py.cstr(ARENA, "java_add"));
            def.set(ADDRESS, 8, addStub);
            def.set(JAVA_INT, 16, Py.METH_VARARGS);
            MemorySegment fn = Py.checked((MemorySegment) Py.PyCFunction_NewEx.invokeExact(def, Py.NULL, Py.NULL));
            MemorySegment main = Py.checked((MemorySegment) Py.PyImport_AddModule.invokeExact(Py.cstr(ARENA, "__main__")));
            int rc = (int) Py.PyObject_SetAttrString.invokeExact(main, Py.cstr(ARENA, "java_add"), fn);
            if (rc != 0) throw Py.fetchError();

            // ---- 1. minimal downcall pair: Py_IncRef + Py_DecRef on None
            MemorySegment none = Py.eval("None");
            bench("downcall pair incRef+decRef", 2_000_000, () -> {
                Py.incRef(none); Py.decRef(none);
            });

            // ---- 2. Java -> Python function call (tuple build + PyObject_CallObject)
            Py.exec("def py_add(a, b): return a + b");
            MemorySegment pyAdd = Py.eval("py_add");
            bench("Java->Python call py_add(1,2)", 1_000_000, () -> {
                MemorySegment r = Py.call(pyAdd, 1, 2);
                Py.decRef(r);
            });

            // install java_add_fast (METH_FASTCALL = 0x0080)
            MemorySegment defFast = ARENA.allocate(32);
            defFast.set(ADDRESS, 0, Py.cstr(ARENA, "java_add_fast"));
            defFast.set(ADDRESS, 8, fastStub);
            defFast.set(JAVA_INT, 16, 0x0080);
            MemorySegment fnFast = Py.checked((MemorySegment) Py.PyCFunction_NewEx.invokeExact(defFast, Py.NULL, Py.NULL));
            rc = (int) Py.PyObject_SetAttrString.invokeExact(main, Py.cstr(ARENA, "java_add_fast"), fnFast);
            if (rc != 0) throw Py.fetchError();

            // ---- 3. Python -> Java upcall, measured from Python
            pyBench("Python->Java upcall java_add(i,)", "java_add(1)");
            pyBench("Python->Java FASTCALL java_add_fast(1)", "java_add_fast(1)");
            // baseline: same loop calling a pure-Python function
            Py.exec("def py_inc(a): return a + 1");
            pyBench("Python->Python baseline py_inc(1)", "py_inc(1)");
        }
        Py.stop();
        System.out.println("M5 done (sink=" + SINK.get() + ")");
    }

    interface Op { void run(); }

    static void bench(String name, int iters, Op op) {
        for (int i = 0; i < iters / 4; i++) op.run(); // warmup
        long t0 = System.nanoTime();
        for (int i = 0; i < iters; i++) op.run();
        long dt = System.nanoTime() - t0;
        System.out.printf("%-42s %8.1f ns/op  (%d iters)%n", name, (double) dt / iters, iters);
    }

    static void pyBench(String name, String callExpr) {
        MemorySegment r = Py.eval("""
                (lambda: __import__('timeit').timeit(lambda: %s, number=1_000_000))()""".formatted(callExpr));
        try {
            double secs = (double) Py.PyFloat_AsDouble.invokeExact(r);
            System.out.printf("%-42s %8.1f ns/op  (1000000 iters, timeit)%n", name, secs * 1e9 / 1_000_000);
            Py.decRef(r);
        } catch (Throwable t) { throw Py.sneaky(t); }
    }
}
