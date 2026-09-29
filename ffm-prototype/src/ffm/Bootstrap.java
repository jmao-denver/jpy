package ffm;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import static java.lang.foreign.ValueLayout.*;

/**
 * Python-first entry point. Python (via ctypes) creates the JVM with
 * JNI_CreateJavaVM and calls this class's install() through the JNI
 * invocation API — the only JNI in the whole design. install() then uses
 * FFM to register a 'jbridge' module into the ALREADY RUNNING interpreter:
 * no Py_InitializeEx here, just PyGILState_Ensure. The libpython dylib the
 * FFM bindings dlopen is the same image the Python process already loaded,
 * so all state is shared.
 */
public final class Bootstrap {
    static final Arena ARENA = Arena.global();

    /** jbridge.java_add(a, b) -> a + b, computed in Java. */
    static MemorySegment javaAdd(MemorySegment self, MemorySegment args) {
        try {
            MemorySegment a = (MemorySegment) Py.PyTuple_GetItem.invokeExact(args, 0L);
            MemorySegment b = (MemorySegment) Py.PyTuple_GetItem.invokeExact(args, 1L);
            if (a.equals(Py.NULL) || b.equals(Py.NULL)) return err("java_add needs 2 args");
            long va = (long) Py.PyLong_AsLong.invokeExact(a);
            long vb = (long) Py.PyLong_AsLong.invokeExact(b);
            return (MemorySegment) Py.PyLong_FromLong.invokeExact(va + vb);
        } catch (Throwable t) {
            return err("java_add failed: " + t);
        }
    }

    /** jbridge.java_version() -> the JVM's java.version, proving we run in-JVM. */
    static MemorySegment javaVersion(MemorySegment self, MemorySegment args) {
        try (Arena a = Arena.ofConfined()) {
            return (MemorySegment) Py.PyUnicode_FromString.invokeExact(
                    Py.cstr(a, System.getProperty("java.version")));
        } catch (Throwable t) {
            return err("java_version failed: " + t);
        }
    }

    static MemorySegment err(String msg) {
        try (Arena a = Arena.ofConfined()) {
            Py.PyErr_SetString.invokeExact(Py.PyExc_RuntimeError, Py.cstr(a, msg));
        } catch (Throwable ignored) { }
        return Py.NULL;
    }

    /** Called from Python through JNI. Registers the 'jbridge' module. */
    public static void install() {
        try (Py.Gil gil = Py.Gil.lock()) {
            MethodHandles.Lookup lk = MethodHandles.lookup();
            FunctionDescriptor pyCFunction = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS);
            MemorySegment addStub = Linker.nativeLinker().upcallStub(
                    lk.findStatic(Bootstrap.class, "javaAdd",
                            MethodType.methodType(MemorySegment.class, MemorySegment.class, MemorySegment.class)),
                    pyCFunction, ARENA);
            MemorySegment verStub = Linker.nativeLinker().upcallStub(
                    lk.findStatic(Bootstrap.class, "javaVersion",
                            MethodType.methodType(MemorySegment.class, MemorySegment.class, MemorySegment.class)),
                    pyCFunction, ARENA);

            // creates the module and registers it in sys.modules; 'import jbridge' then finds it
            MemorySegment mod = Py.checked((MemorySegment)
                    Py.PyImport_AddModule.invokeExact(Py.cstr(ARENA, "jbridge")));

            addFunction(mod, "java_add", addStub, "adds two ints in Java");
            addFunction(mod, "java_version", verStub, "returns the JVM's java.version");
        } catch (Throwable t) {
            // never let anything escape into JNI's CallStaticVoidMethodA
            System.err.println("Bootstrap.install failed: " + t);
        }
    }

    static void addFunction(MemorySegment module, String name, MemorySegment stub, String doc) throws Throwable {
        MemorySegment def = ARENA.allocate(32);
        def.set(ADDRESS, 0, Py.cstr(ARENA, name));
        def.set(ADDRESS, 8, stub);
        def.set(JAVA_INT, 16, Py.METH_VARARGS);
        def.set(ADDRESS, 24, Py.cstr(ARENA, doc));
        MemorySegment fn = Py.checked((MemorySegment)
                Py.PyCFunction_NewEx.invokeExact(def, Py.NULL, Py.NULL));
        try (Arena a = Arena.ofConfined()) {
            int rc = (int) Py.PyObject_SetAttrString.invokeExact(module, Py.cstr(a, name), fn);
            if (rc != 0) throw Py.fetchError();
        }
        Py.decRef(fn); // module holds its own reference now
    }

    private Bootstrap() {}
}
