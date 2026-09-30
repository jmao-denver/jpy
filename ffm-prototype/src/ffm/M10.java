package ffm;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.foreign.ValueLayout.*;

/**
 * Milestone 10: pin a Java heap array with zero compiled C, by calling JNI
 * functions through FFM.
 *
 * FFM cannot pass a Java object to native code, but a JNI call can return
 * one: CallStaticObjectMethodA on fetch(id) yields a jobject for any array we
 * registered. GetPrimitiveArrayCritical on that jobject gives the pinned
 * address. Every step is an FFM downcall into a JNIEnv function pointer.
 */
public class M10 {

    static final ConcurrentHashMap<Long, Object> REGISTRY = new ConcurrentHashMap<>();

    /** Called from native code (via JNI) to turn a registry id into a jobject. */
    public static Object fetch(long id) {
        return REGISTRY.get(id);
    }

    static final Linker LINKER = Linker.nativeLinker();
    static final SymbolLookup JVM = SymbolLookup.libraryLookup(
            Path.of(System.getProperty("java.home"), "lib", "server", System.mapLibraryName("jvm")), Arena.global());

    static MemorySegment env;
    static MemorySegment fetchClass;   // global ref
    static MemorySegment fetchMethod;  // jmethodID

    // JNIEnv function table indices, from jni.h
    static final int FIND_CLASS = 6, NEW_GLOBAL_REF = 21, DELETE_GLOBAL_REF = 22, DELETE_LOCAL_REF = 23,
            GET_STATIC_METHOD_ID = 113, CALL_STATIC_OBJECT_METHOD_A = 116,
            GET_PRIMITIVE_ARRAY_CRITICAL = 222, RELEASE_PRIMITIVE_ARRAY_CRITICAL = 223, EXCEPTION_CHECK = 228;

    static MethodHandle fn(MemorySegment table, int index, FunctionDescriptor fd) {
        MemorySegment ptr = table.reinterpret(8L * (index + 1)).getAtIndex(ADDRESS, index);
        return LINKER.downcallHandle(ptr, fd);
    }

    static MemorySegment envTable() {
        return env.reinterpret(8).get(ADDRESS, 0);
    }

    static void init() throws Throwable {
        // JavaVM* via the exported JNI_GetCreatedJavaVMs, then JNIEnv* via JavaVM->GetEnv (index 6)
        try (Arena a = Arena.ofConfined()) {
            MethodHandle getCreated = LINKER.downcallHandle(JVM.find("JNI_GetCreatedJavaVMs").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
            MemorySegment vmBuf = a.allocate(ADDRESS);
            MemorySegment nVMs = a.allocate(JAVA_INT);
            check((int) getCreated.invokeExact(vmBuf, 1, nVMs) == 0 && nVMs.get(JAVA_INT, 0) == 1, "one JVM found");
            MemorySegment vm = vmBuf.get(ADDRESS, 0);
            MemorySegment vmTable = vm.reinterpret(8).get(ADDRESS, 0);
            MemorySegment envOut = a.allocate(ADDRESS);
            int rc = (int) fn(vmTable, 6, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT))
                    .invokeExact(vm, envOut, 0x000A0000);
            check(rc == 0, "GetEnv returned JNI_OK");
            env = envOut.get(ADDRESS, 0);

            MemorySegment t = envTable();
            // Rule learned here: a JNI local ref survives only until other Java code runs. So every
            // handle and argument is prepared first, and the JNI calls then run back to back.
            MethodHandle findClass = fn(t, FIND_CLASS, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
            MethodHandle getStaticMethodId = fn(t, GET_STATIC_METHOD_ID, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
            MethodHandle callStaticObject = fn(t, CALL_STATIC_OBJECT_METHOD_A, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
            MethodHandle getMethodId = fn(t, 33 /* GetMethodID */, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
            MethodHandle newStringUtf = fn(t, 167 /* NewStringUTF */, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
            MethodHandle callObject = fn(t, 36 /* CallObjectMethodA */, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
            MethodHandle newGlobal = fn(t, NEW_GLOBAL_REF, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
            MemorySegment sClassLoader = a.allocateFrom("java/lang/ClassLoader");
            MemorySegment sGetScl = a.allocateFrom("getSystemClassLoader");
            MemorySegment sGetSclSig = a.allocateFrom("()Ljava/lang/ClassLoader;");
            MemorySegment sLoadClass = a.allocateFrom("loadClass");
            MemorySegment sLoadClassSig = a.allocateFrom("(Ljava/lang/String;)Ljava/lang/Class;");
            MemorySegment sName = a.allocateFrom("ffm.M10");
            MemorySegment sFetch = a.allocateFrom("fetch");
            MemorySegment sFetchSig = a.allocateFrom("(J)Ljava/lang/Object;");
            MemorySegment nameArg = a.allocate(ADDRESS);

            // FindClass through an FFM downcall uses java.base's loader (the nearest Java frame is JDK
            // internal), so application classes are invisible to it. Bootstrap once through JDK classes:
            // ClassLoader.getSystemClassLoader().loadClass("ffm.M10").
            // A JNI call that runs Java code (Call*Method*, and class loading) can invalidate earlier local
            // refs here, so each local ref becomes a global ref in the very next call.
            MemorySegment clClass = (MemorySegment) newGlobal.invokeExact(env,
                    (MemorySegment) findClass.invokeExact(env, sClassLoader));
            MemorySegment getScl = (MemorySegment) getStaticMethodId.invokeExact(env, clClass, sGetScl, sGetSclSig);
            MemorySegment loadClass = (MemorySegment) getMethodId.invokeExact(env, clClass, sLoadClass, sLoadClassSig);
            MemorySegment loader = (MemorySegment) newGlobal.invokeExact(env,
                    (MemorySegment) callStaticObject.invokeExact(env, clClass, getScl, MemorySegment.NULL));
            MemorySegment name = (MemorySegment) newGlobal.invokeExact(env,
                    (MemorySegment) newStringUtf.invokeExact(env, sName));
            nameArg.set(ADDRESS, 0, name);
            fetchClass = (MemorySegment) newGlobal.invokeExact(env,
                    (MemorySegment) callObject.invokeExact(env, loader, loadClass, nameArg));
            fetchMethod = (MemorySegment) getStaticMethodId.invokeExact(env, fetchClass, sFetch, sFetchSig);
            check(!fetchClass.equals(MemorySegment.NULL), "loaded ffm.M10 through the system class loader via JNI");
            check(!fetchMethod.equals(MemorySegment.NULL), "GetStaticMethodID(fetch)");
        }
    }

    static MethodHandle CALL_STATIC_OBJECT, NEW_GLOBAL, DELETE_LOCAL, DELETE_GLOBAL, PIN, UNPIN;

    static void bindHot() {
        MemorySegment t = envTable();
        CALL_STATIC_OBJECT = fn(t, CALL_STATIC_OBJECT_METHOD_A, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        NEW_GLOBAL = fn(t, NEW_GLOBAL_REF, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        DELETE_LOCAL = fn(t, DELETE_LOCAL_REF, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
        DELETE_GLOBAL = fn(t, DELETE_GLOBAL_REF, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
        PIN = fn(t, GET_PRIMITIVE_ARRAY_CRITICAL, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        UNPIN = fn(t, RELEASE_PRIMITIVE_ARRAY_CRITICAL, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
    }

    record Pin(MemorySegment globalRef, MemorySegment address, boolean isCopy) {}

    static Pin pin(Object array, long byteSize, Arena scratch) throws Throwable {
        long id = System.identityHashCode(array);
        REGISTRY.put(id, array);
        try {
            MemorySegment jvalues = scratch.allocate(JAVA_LONG);
            jvalues.set(JAVA_LONG, 0, id);
            MemorySegment local = (MemorySegment) CALL_STATIC_OBJECT.invokeExact(env, fetchClass, fetchMethod, jvalues);
            MemorySegment global = (MemorySegment) NEW_GLOBAL.invokeExact(env, local);
            DELETE_LOCAL.invokeExact(env, local);
            MemorySegment isCopy = scratch.allocate(JAVA_BYTE);
            MemorySegment p = (MemorySegment) PIN.invokeExact(env, global, isCopy);
            return new Pin(global, p.reinterpret(byteSize), isCopy.get(JAVA_BYTE, 0) != 0);
        } finally {
            REGISTRY.remove(id);
        }
    }

    static void unpin(Pin pin) throws Throwable {
        UNPIN.invokeExact(env, pin.globalRef, pin.address, 0);
        DELETE_GLOBAL.invokeExact(env, pin.globalRef);
    }

    public static void main(String[] args) throws Throwable {
        init();
        bindHot();
        System.out.println("GC: " + java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()
                .stream().map(b -> b.getName()).toList());

        int[] data = new int[4096];
        for (int i = 0; i < data.length; i++) data[i] = i;

        try (Arena a = Arena.ofConfined()) {
            Pin pin = pin(data, 4L * data.length, a);
            check(!pin.isCopy, "pinned in place (isCopy == false), no copy");
            check(pin.address.getAtIndex(JAVA_INT, 7) == 7, "native read sees Java data");

            // Hold the pin across heavy allocation and an explicit full GC.
            long garbage = 0;
            for (int i = 0; i < 2_000; i++) garbage += new byte[100_000].length;
            System.gc();
            check(garbage > 0, "allocated ~200 MB and ran System.gc() while pinned");

            pin.address.setAtIndex(JAVA_INT, 7, 4242);   // write through the raw pointer
            unpin(pin);
        }
        check(data[7] == 4242, "Java sees the native write after unpin (same memory, not a copy)");

        // cost of one pin + unpin round trip, the per-chunk overhead for vectorized UDFs
        try (Arena a = Arena.ofConfined()) {
            for (int i = 0; i < 100_000; i++) unpin(pin(data, 4L * data.length, a));
        }
        int n = 1_000_000;
        long t0 = System.nanoTime();
        try (Arena a = Arena.ofConfined()) {
            for (int i = 0; i < n; i++) unpin(pin(data, 4L * data.length, a));
        }
        double ns = (System.nanoTime() - t0) / (double) n;
        System.out.printf("pin+unpin round trip: %.0f ns  (= %.3f ns/row for a 4096-row chunk)%n", ns, ns / 4096);
        System.out.println("M10 OK");
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) System.exit(1);
    }
}
