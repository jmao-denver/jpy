package ffm;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import static java.lang.foreign.ValueLayout.*;

/**
 * Milestone 11: can a metaclass serve live Java static fields without the
 * crash the C jpy hit?
 *
 * The C jpy's JType metatype derived from object, not type, and it tried to
 * swap Py_TYPE of hand-built static type structs to it afterwards; its own
 * comment says that crashed. Here the metaclass is a real heap subclass of
 * `type` (PyType_FromSpecWithBases with base PyType_Type) and the Java type
 * is created through PyType_FromMetaclass, the 3.12+ API for this.
 */
public class M11 {

    /** The Java static field being served: public static int counter. */
    public static int counter = 7;

    static final Arena GLOBAL = Arena.global();
    static final int Py_tp_getattro = 58, Py_tp_setattro = 69, Py_tp_new = 65;
    static final long BASETYPE = 1L << 10;

    static final MethodHandle PyType_FromMetaclass = Py.dc("PyType_FromMetaclass",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle PyType_FromSpecWithBases = Py.dc("PyType_FromSpecWithBases",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle PyType_GetSlot = Py.dc("PyType_GetSlot",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
    static final MethodHandle PyUnicode_CompareWithASCIIString = Py.dc("PyUnicode_CompareWithASCIIString",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    static final MemorySegment PyType_Type = Py.LIB.find("PyType_Type").orElseThrow();
    static final MemorySegment GENERIC_NEW = Py.LIB.find("PyType_GenericNew").orElseThrow();

    /** type's own tp_getattro / tp_setattro, so the metaclass can delegate everything else. */
    static MethodHandle typeGetattro;
    static MethodHandle typeSetattro;
    static MemorySegment fooType;
    static final MemorySegment COUNTER_NAME = GLOBAL.allocateFrom("counter");

    static boolean isCounter(MemorySegment name) throws Throwable {
        return (int) PyUnicode_CompareWithASCIIString.invokeExact(name, COUNTER_NAME) == 0;
    }

    /** Metaclass tp_getattro: live static field, else type's normal lookup. */
    static MemorySegment metaGetattro(MemorySegment type, MemorySegment name) {
        try {
            if (type.address() == fooType.address() && isCounter(name)) {
                return (MemorySegment) Py.PyLong_FromLong.invokeExact((long) counter);
            }
            return (MemorySegment) typeGetattro.invokeExact(type, name);
        } catch (Throwable t) {
            return MemorySegment.NULL;
        }
    }

    /** Metaclass tp_setattro: writes go to the Java static field, everything else to type. */
    static int metaSetattro(MemorySegment type, MemorySegment name, MemorySegment value) {
        try {
            if (type.address() == fooType.address() && isCounter(name) && !value.equals(MemorySegment.NULL)) {
                counter = (int) (long) Py.PyLong_AsLong.invokeExact(value);
                return 0;
            }
            return (int) typeSetattro.invokeExact(type, name, value);
        } catch (Throwable t) {
            return -1;
        }
    }

    static MemorySegment stub(String name, FunctionDescriptor fd) throws Exception {
        return Linker.nativeLinker().upcallStub(
                MethodHandles.lookup().findStatic(M11.class, name, fd.toMethodType()), fd, GLOBAL);
    }

    static MemorySegment spec(String name, long flags, long[][] slots) {
        MemorySegment slotArray = GLOBAL.allocate(16L * (slots.length + 1), 8);
        for (int i = 0; i < slots.length; i++) {
            slotArray.set(JAVA_INT, 16L * i, (int) slots[i][0]);
            slotArray.set(ADDRESS, 16L * i + 8, MemorySegment.ofAddress(slots[i][1]));
        }
        MemorySegment spec = GLOBAL.allocate(32, 8);
        spec.set(ADDRESS, 0, GLOBAL.allocateFrom(name));
        spec.set(JAVA_INT, 16, (int) flags);
        spec.set(ADDRESS, 24, slotArray);
        return spec;
    }

    public static void main(String[] args) throws Throwable {
        Py.start();
        try (Py.Gil gil = Py.Gil.lock()) {
            Linker linker = Linker.nativeLinker();
            typeGetattro = linker.downcallHandle((MemorySegment) PyType_GetSlot.invokeExact(PyType_Type, Py_tp_getattro),
                    FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
            typeSetattro = linker.downcallHandle((MemorySegment) PyType_GetSlot.invokeExact(PyType_Type, Py_tp_setattro),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

            MemorySegment meta = Py.checked((MemorySegment) PyType_FromSpecWithBases.invokeExact(
                    spec("jpy.JTypeMeta", BASETYPE, new long[][]{
                            {Py_tp_getattro, stub("metaGetattro", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS)).address()},
                            {Py_tp_setattro, stub("metaSetattro", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS)).address()},
                    }), PyType_Type));
            fooType = Py.checked((MemorySegment) PyType_FromMetaclass.invokeExact(meta, MemorySegment.NULL,
                    spec("demo.Foo", BASETYPE, new long[][]{{Py_tp_new, GENERIC_NEW.address()}}), MemorySegment.NULL));

            MemorySegment main = Py.checked((MemorySegment) Py.PyImport_AddModule.invokeExact(GLOBAL.allocateFrom("__main__")));
            int rc = (int) Py.PyObject_SetAttrString.invokeExact(main, GLOBAL.allocateFrom("Foo"), fooType);
            Py.exec("""
                import gc, sys, threading
                results = {}
                results['type(Foo) is metaclass, subclass of type'] = type(Foo).__name__ == 'JTypeMeta' and issubclass(type(Foo), type)
                results['class read is the live Java value (7)'] = Foo.counter == 7
                Foo.counter = 41
                results['class write reached Java (read back 41)'] = Foo.counter == 41
                results['ordinary class attrs still work'] = Foo.__name__ == 'Foo' and Foo.__module__ == 'demo'
                Foo.note = 'plain'
                results['non-Java class attrs set and get normally'] = Foo.note == 'plain'
                results['instances still work'] = isinstance(Foo(), Foo)
                class Sub(Foo): pass
                results['Python subclass works, inherits metaclass'] = type(Sub).__name__ == 'JTypeMeta' and isinstance(Sub(), Foo)
                def hammer():
                    for i in range(20000):
                        Foo.counter = Foo.counter
                ts = [threading.Thread(target=hammer) for _ in range(4)]
                [t.start() for t in ts]; [t.join() for t in ts]
                gc.collect()
                results['80k get/set from 4 threads + gc'] = Foo.counter == 41
                """);
            MemorySegment res = Py.eval("results");
            System.out.println(Py.str(res).replace(", '", ",\n '"));
            MemorySegment ok = Py.eval("all(results.values())");
            boolean allOk = Py.str(ok).equals("True");
            System.out.println("Java sees counter = " + counter);
            Py.decRef(ok); Py.decRef(res);
            if (!allOk || counter != 41) System.exit(1);
        }
        int rc = Py.stop();
        System.out.println("finalize rc=" + rc);
        System.out.println("M11 OK");
    }
}
