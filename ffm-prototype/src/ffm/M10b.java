package ffm;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.*;

/**
 * Does a JNI local reference survive from one FFM downcall to the next when
 * no other Java code runs in between? All handles are bound up front.
 */
public class M10b {
    public static void main(String[] args) throws Throwable {
        Linker linker = Linker.nativeLinker();
        SymbolLookup jvm = SymbolLookup.libraryLookup(
                Path.of(System.getProperty("java.home"), "lib", "server", System.mapLibraryName("jvm")), Arena.global());
        try (Arena a = Arena.ofConfined()) {
            MethodHandle getCreated = linker.downcallHandle(jvm.find("JNI_GetCreatedJavaVMs").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
            MemorySegment vmBuf = a.allocate(ADDRESS);
            MemorySegment nVMs = a.allocate(JAVA_INT);
            int rc0 = (int) getCreated.invokeExact(vmBuf, 1, nVMs);
            MemorySegment vm = vmBuf.get(ADDRESS, 0);
            MemorySegment vmTable = vm.reinterpret(8).get(ADDRESS, 0);
            MethodHandle getEnv = linker.downcallHandle(vmTable.reinterpret(8 * 7).getAtIndex(ADDRESS, 6),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
            MemorySegment envOut = a.allocate(ADDRESS);
            int rc1 = (int) getEnv.invokeExact(vm, envOut, 0x000A0000);
            MemorySegment env = envOut.get(ADDRESS, 0);
            MemorySegment t = env.reinterpret(8).get(ADDRESS, 0).reinterpret(8 * 240);

            MethodHandle findClass = linker.downcallHandle(t.getAtIndex(ADDRESS, 6), FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
            MethodHandle newGlobalRef = linker.downcallHandle(t.getAtIndex(ADDRESS, 21), FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
            MethodHandle getStaticMethodId = linker.downcallHandle(t.getAtIndex(ADDRESS, 113), FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
            MethodHandle getObjectClass = linker.downcallHandle(t.getAtIndex(ADDRESS, 31), FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
            MemorySegment cn = a.allocateFrom("java/lang/ClassLoader");
            MemorySegment mn = a.allocateFrom("getSystemClassLoader");
            MemorySegment sig = a.allocateFrom("()Ljava/lang/ClassLoader;");

            System.out.println("rc " + rc0 + " " + rc1);
            // 1: local ref straight into the next call
            MemorySegment local = (MemorySegment) findClass.invokeExact(env, cn);
            MemorySegment mid = (MemorySegment) getStaticMethodId.invokeExact(env, local, mn, sig);
            System.out.println("local ref reused in next downcall: " + (mid.equals(MemorySegment.NULL) ? "FAILED" : "works"));
            // 2: global ref taken immediately
            MemorySegment local2 = (MemorySegment) findClass.invokeExact(env, cn);
            MemorySegment global = (MemorySegment) newGlobalRef.invokeExact(env, local2);
            System.gc();
            MemorySegment mid2 = (MemorySegment) getStaticMethodId.invokeExact(env, global, mn, sig);
            System.out.println("global ref across System.gc(): " + (mid2.equals(MemorySegment.NULL) ? "FAILED" : "works"));
        }
    }
}
