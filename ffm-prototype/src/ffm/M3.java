package ffm;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicLong;

import static java.lang.foreign.ValueLayout.*;

/**
 * Milestone 3: Python -> Java via FFM upcall stubs.
 * 1. A Python builtin function backed by a Java method (PyCFunction_NewEx),
 *    hammered from 4 Python 'threading' threads (which are unknown native
 *    threads the JVM must auto-attach).
 * 2. A Python type built with PyType_FromSpec whose tp_call slot is Java.
 * Every stub body catches Throwable: a Java exception escaping an upcall
 * kills the JVM.
 */
public class M3 {
    static final AtomicLong CALLS = new AtomicLong();
    static final Arena ARENA = Arena.global();

    /** PyCFunction impl: java_add(a, b) -> a + b. */
    static MemorySegment javaAdd(MemorySegment self, MemorySegment args) {
        try {
            MemorySegment a = (MemorySegment) Py.PyTuple_GetItem.invokeExact(args, 0L); // borrowed
            MemorySegment b = (MemorySegment) Py.PyTuple_GetItem.invokeExact(args, 1L);
            if (a.equals(Py.NULL) || b.equals(Py.NULL)) return err("java_add needs 2 args");
            long va = (long) Py.PyLong_AsLong.invokeExact(a);
            long vb = (long) Py.PyLong_AsLong.invokeExact(b);
            CALLS.incrementAndGet();
            return (MemorySegment) Py.PyLong_FromLong.invokeExact(va + vb);
        } catch (Throwable t) {
            return err("java_add failed: " + t);
        }
    }

    /** ternaryfunc impl for tp_call: instances of the Java-made type return 123. */
    static MemorySegment tpCall(MemorySegment self, MemorySegment args, MemorySegment kwargs) {
        try {
            CALLS.incrementAndGet();
            return (MemorySegment) Py.PyLong_FromLong.invokeExact(123L);
        } catch (Throwable t) {
            return err("tp_call failed: " + t);
        }
    }

    static MemorySegment err(String msg) {
        try (Arena a = Arena.ofConfined()) {
            Py.PyErr_SetString.invokeExact(Py.PyExc_RuntimeError, Py.cstr(a, msg));
        } catch (Throwable ignored) { }
        return Py.NULL;
    }

    public static void main(String[] argv) throws Throwable {
        Py.start();
        MethodHandles.Lookup lk = MethodHandles.lookup();
        FunctionDescriptor pyCFunction = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS);
        FunctionDescriptor ternaryfunc = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS);
        MemorySegment addStub = Linker.nativeLinker().upcallStub(
                lk.findStatic(M3.class, "javaAdd",
                        MethodType.methodType(MemorySegment.class, MemorySegment.class, MemorySegment.class)),
                pyCFunction, ARENA);
        MemorySegment callStub = Linker.nativeLinker().upcallStub(
                lk.findStatic(M3.class, "tpCall",
                        MethodType.methodType(MemorySegment.class, MemorySegment.class, MemorySegment.class, MemorySegment.class)),
                ternaryfunc, ARENA);

        try (Py.Gil gil = Py.Gil.lock()) {
            // --- builtin function: PyMethodDef {name, meth, flags, doc}, must outlive the function object
            MemorySegment def = ARENA.allocate(32);
            def.set(ADDRESS, 0, Py.cstr(ARENA, "java_add"));
            def.set(ADDRESS, 8, addStub);
            def.set(JAVA_INT, 16, Py.METH_VARARGS);
            def.set(ADDRESS, 24, Py.cstr(ARENA, "adds two ints in Java"));
            MemorySegment fn = Py.checked((MemorySegment) Py.PyCFunction_NewEx.invokeExact(def, Py.NULL, Py.NULL));
            MemorySegment main = Py.checked((MemorySegment) Py.PyImport_AddModule.invokeExact(Py.cstr(ARENA, "__main__")));
            try (Arena a = Arena.ofConfined()) {
                int rc = (int) Py.PyObject_SetAttrString.invokeExact(main, Py.cstr(a, "java_add"), fn);
                if (rc != 0) throw Py.fetchError();
            }

            // single-threaded sanity
            MemorySegment r = Py.eval("java_add(20, 22)");
            check("upcall from main thread", Py.asLong(r) == 42);
            Py.decRef(r);

            // --- type from spec: slots = [{Py_tp_call, stub}, {0, NULL}]
            MemorySegment slots = ARENA.allocate(2 * 16);
            slots.set(JAVA_INT, 0, Py.Py_tp_call);
            slots.set(ADDRESS, 8, callStub);
            // second entry stays zeroed = terminator
            MemorySegment spec = ARENA.allocate(32);
            spec.set(ADDRESS, 0, Py.cstr(ARENA, "jffm.JavaCallable"));
            spec.set(JAVA_INT, 8, 16);   // basicsize = sizeof(PyObject)
            spec.set(JAVA_INT, 12, 0);   // itemsize
            spec.set(JAVA_INT, 16, (int) (Py.Py_TPFLAGS_DEFAULT | Py.Py_TPFLAGS_BASETYPE));
            spec.set(ADDRESS, 24, slots);
            MemorySegment type = Py.checked((MemorySegment) Py.PyType_FromSpec.invokeExact(spec));
            try (Arena a = Arena.ofConfined()) {
                int rc = (int) Py.PyObject_SetAttrString.invokeExact(main, Py.cstr(a, "JavaCallable"), type);
                if (rc != 0) throw Py.fetchError();
            }
            MemorySegment r2 = Py.eval("JavaCallable()()");
            check("tp_call upcall via PyType_FromSpec", Py.asLong(r2) == 123);
            Py.decRef(r2);

            // --- hammer from 4 Python threads (unknown native threads -> JVM auto-attach)
            CALLS.set(0);
            Py.exec("""
                import threading
                total = 0
                lock = threading.Lock()
                def work():
                    global total
                    s = 0
                    for i in range(10000):
                        s += java_add(i, 1)
                    with lock:
                        total += s
                ts = [threading.Thread(target=work) for _ in range(4)]
                [t.start() for t in ts]
                [t.join() for t in ts]
                """);
            MemorySegment total = Py.eval("total");
            long expected = 4L * (10000L * 9999L / 2 + 10000L);
            check("python-side sum " + Py.asLong(total) + " == " + expected, Py.asLong(total) == expected);
            Py.decRef(total);
            check("java saw all 40000 upcalls, got " + CALLS.get(), CALLS.get() == 40000);
        }
        int rc = Py.stop();
        check("clean finalize", rc == 0);
        System.out.println("M3 OK");
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) System.exit(1);
    }
}
