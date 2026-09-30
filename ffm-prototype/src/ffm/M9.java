package ffm;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicLong;

import static java.lang.foreign.ValueLayout.*;

/**
 * Milestone 9: free-threaded (3.13t) comparison run.
 * Measures, on whatever libpython -Dlibpython points at:
 *   1. Python -> Java upcall, single thread (ns/op)
 *   2. Python -> Java upcall, 4 Python threads (aggregate Mcalls/s)
 *   3. Java -> Python call, single thread (ns/op)
 *   4. Java -> Python call, 4 Java threads (aggregate Mcalls/s)
 * On a GIL build, 2 and 4 cannot beat 1 and 3; on a free-threaded build they
 * should scale if the bridge itself is not the serializer.
 */
public class M9 {
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

    public static void main(String[] argv) throws Throwable {
        Py.start();
        MemorySegment addStub = Linker.nativeLinker().upcallStub(
                MethodHandles.lookup().findStatic(M9.class, "javaAdd",
                        MethodType.methodType(MemorySegment.class, MemorySegment.class, MemorySegment.class)),
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS), ARENA);

        try (Py.Gil gil = Py.Gil.lock()) {
            System.out.println("python: " + Py.version().lines().findFirst().orElse("?"));
            MemorySegment gilEnabled = Py.eval("str(getattr(__import__('sys'), '_is_gil_enabled', lambda: True)())");
            System.out.println("GIL enabled: " + Py.str(gilEnabled));
            Py.decRef(gilEnabled);

            MemorySegment def = ARENA.allocate(32);
            def.set(ADDRESS, 0, Py.cstr(ARENA, "java_add"));
            def.set(ADDRESS, 8, addStub);
            def.set(JAVA_INT, 16, Py.METH_VARARGS);
            MemorySegment fn = Py.checked((MemorySegment) Py.PyCFunction_NewEx.invokeExact(def, Py.NULL, Py.NULL));
            MemorySegment main = Py.checked((MemorySegment) Py.PyImport_AddModule.invokeExact(Py.cstr(ARENA, "__main__")));
            int rc = (int) Py.PyObject_SetAttrString.invokeExact(main, Py.cstr(ARENA, "java_add"), fn);
            if (rc != 0) throw Py.fetchError();

            // 1 + 2: Python -> Java, single and 4 threads, measured in Python
            Py.exec("""
                import time, threading
                def hammer(n):
                    for i in range(n):
                        java_add(1)
                # warmup
                hammer(200_000)
                t0 = time.perf_counter(); hammer(1_000_000); single_s = time.perf_counter() - t0
                ts = [threading.Thread(target=hammer, args=(250_000,)) for _ in range(4)]
                t0 = time.perf_counter()
                [t.start() for t in ts]; [t.join() for t in ts]
                multi_s = time.perf_counter() - t0
                """);
            double singleS = pyFloat("single_s");
            double multiS = pyFloat("multi_s");
            System.out.printf("P->J single thread:  %8.1f ns/op%n", singleS * 1e9 / 1e6);
            System.out.printf("P->J 4 threads:      %8.2f Mcalls/s (single was %.2f) -> scaling x%.2f%n",
                    1.0 / multiS, 1.0 / singleS, singleS / multiS);
        }

        // 3 + 4: Java -> Python, single and 4 Java threads
        MemorySegment pyAdd;
        try (Py.Gil gil = Py.Gil.lock()) {
            Py.exec("def py_add(a, b): return a + b");
            pyAdd = Py.eval("py_add");
            for (int i = 0; i < 200_000; i++) { Py.decRef(Py.call(pyAdd, 1, 2)); } // warmup
            long t0 = System.nanoTime();
            for (int i = 0; i < 1_000_000; i++) { Py.decRef(Py.call(pyAdd, 1, 2)); }
            System.out.printf("J->P single thread:  %8.1f ns/op%n", (System.nanoTime() - t0) / 1e6);
        }
        Thread[] ts = new Thread[4];
        long t0 = System.nanoTime();
        for (int t = 0; t < 4; t++) {
            ts[t] = new Thread(() -> {
                try (Py.Gil gil = Py.Gil.lock()) {
                    for (int i = 0; i < 250_000; i++) { Py.decRef(Py.call(pyAdd, 1, 2)); }
                }
            });
            ts[t].start();
        }
        for (Thread t : ts) t.join();
        double multi = (System.nanoTime() - t0) / 1e9;
        System.out.printf("J->P 4 threads:      %8.2f Mcalls/s -> wall %.2fs for 1M calls%n", 1.0 / multi, multi);

        try (Py.Gil gil = Py.Gil.lock()) { Py.decRef(pyAdd); }
        System.out.println("M9 done (sink=" + SINK.get() + ")");
        int rcStop = Py.stop();
        System.out.println("finalize rc=" + rcStop);
    }

    static double pyFloat(String name) {
        MemorySegment v = Py.eval(name);
        try {
            return (double) Py.PyFloat_AsDouble.invokeExact(v);
        } catch (Throwable t) {
            throw Py.sneaky(t);
        } finally {
            Py.decRef(v);
        }
    }
}
