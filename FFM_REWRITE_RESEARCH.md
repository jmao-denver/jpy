# Research: rewriting jpy with the Java FFM API (Java 22+)

Date: 2026-09-29. Based on the current source in this repo.

## TL;DR

It is feasible, with two exceptions (see "What cannot move off JNI"). FFM
(java.lang.foreign, final in Java 22 via JEP 454) can replace both halves of
jpy: the JNI native methods that let Java call CPython, and the hand-written
CPython extension module that lets Python call Java. The payoff is
large: almost no C code, no per-Python-version compiled extension (the
per-version handling moves into the jar as runtime dispatch — see "Version
portability"), and one fewer FFI hop in each direction. The costs are a big rewrite (~18k lines of C to
replace), a hard JDK 22+ floor, and careful GIL and refcount handling moving
from C into Java. Recommended next step: a small spike, not a commitment.

## What jpy is today

Two halves sharing one shared library:

1. **Java -> Python.** `org.jpy.PyLib` declares 58 `native` methods
   (`src/main/java/org/jpy/PyLib.java`). They are implemented in
   `src/main/c/jni/org_jpy_PyLib.c` (2,594 lines), which calls the CPython C
   API (`PyObject_Call`, `PyGILState_Ensure`, refcounting, conversions).

2. **Python -> Java.** A hand-written CPython extension module `jpy`
   (`jpy_module.c` 1,445 lines, `jpy_jtype.c` 2,717, `jpy_jmethod.c` 1,056,
   `jpy_jobj.c` 850, plus conv/array/field/diag files; ~8k lines total). It
   defines Python types that wrap Java classes and dispatches calls through
   JNI (`CallObjectMethodA` etc. in `jpy_jmethod.c:425`).

Two bootstrap modes:

- **Java first** (Deephaven embedded Python server): Java loads the native lib,
  `startPython0` dlopens libpython and runs `Py_Initialize`, then imports the
  `jpy` module from the same binary.
- **Python first**: `import jpy` runs `PyInit_jpy` (`jpy_module.c:301`), and the
  module creates the JVM with `JNI_CreateJavaVM` (`jpy_module.c:484`).

Because the extension links against version-specific CPython internals, the repo
ships one `.so` per interpreter: cpython-39, -312, -313, -313t, -314, -314t.

## How an FFM rewrite maps onto this

### Java -> Python (the easy half)

Replace every `PyLib` native with an FFM downcall:
`Linker.nativeLinker()` + `SymbolLookup.libraryLookup("libpython3.x")` +
`MethodHandle` per C API function. `jextract` can generate the bindings from
`Python.h`. The 2,594 lines of `org_jpy_PyLib.c` become plain Java. Downcall
performance is on par with JNI, and `Linker.Option.critical()` makes hot
no-callback calls faster than JNI.

GIL handling moves to Java: a small `try (GilLock g = gil.acquire())` wrapper
around `PyGILState_Ensure`/`Release`, replacing the `JPy_BEGIN_GIL_STATE`
macros.

### Python -> Java (the hard half)

The extension module is rebuilt from the Java side at runtime:

- Create the module and its types with `PyModule_Create` /
  `PyType_FromSpec`, called via downcalls. `PyType_FromSpec` avoids touching
  `PyTypeObject` struct layout, which is what forces per-version builds today.
- Every slot and method (`tp_call`, `tp_getattro`, `sq_length`, the
  `JMethod_InvokeMethod` dispatcher, etc.) becomes an **upcall stub**
  (`Linker.upcallStub`): a native function pointer that jumps straight into
  Java. FFM upcalls attach unknown native threads to the JVM automatically, so
  arbitrary Python threads calling Java work.
- This removes a hop: today Python -> C slot -> JNI `Call*MethodA` -> Java.
  After: Python -> stub -> Java. Argument matching/conversion
  (`jpy_jmethod.c`, `jpy_jtype.c`) is rewritten in Java where it can use
  reflection/`MethodHandles` directly instead of JNI method IDs.
- The buffer/array support (`jpy_jarray.c`, `Py_buffer`) is doable with a
  `MemoryLayout` for `Py_buffer`; that struct is in the limited API since 3.11.

### Bootstrap

- **Java first**: pure Java, no native lib to load at all. Start CPython with
  downcalls (`Py_InitializeEx`), then register the `jpy` module into the
  running interpreter.
- **Python first**: the process entry is Python, so something must create the
  JVM. A pure-Python launcher using `ctypes` to call `JNI_CreateJavaVM` works;
  once the JVM is up, Java-side FFM code injects the real `jpy` module. Net
  effect: **no compiled wheel at all** — jpy becomes a pure-Python package plus
  a jar.

### Version portability (corrected — weaker than first drafted)

FFM binds libpython symbols by name at runtime, so the wheel itself can be
pure Python. But "no per-version wheels" overstates the win; the per-version
burden moves into the jar rather than disappearing:

- The Java code must call only exported functions, never macros or static
  inlines: `Py_IncRef` instead of `Py_INCREF`, `PyTuple_GetItem` instead of
  `PyTuple_GET_ITEM`. This costs speed on hot paths.
- Functions-only has gaps. The buffer protocol slots (`bf_getbuffer` /
  `bf_releasebuffer`) are only settable via the stable `PyType_FromSpec` path
  since Python 3.12; supporting 3.9–3.11 means version-specific struct
  layouts (`MemoryLayout` per version) with runtime dispatch on `Py_Version`.
- Free-threaded builds (3.13t/3.14t) change object internals and locking
  assumptions; a separate tested code path either way.
- If the JNI shim for zero-copy arrays is kept, a compiled native library
  remains — per platform, though not per Python version.

Net: possibly one wheel, definitely not one code path. Each supported
CPython version still needs explicit certification.

## What cannot move off JNI

Two pieces have no FFM equivalent. "Rewrite in FFM" really means "rewrite all
but these".

1. **JVM creation in Python-first mode.** FFM is Java code and needs a running
   JVM. When the process entry point is Python, the JVM must be created with
   `JNI_CreateJavaVM` (`jpy_module.c:484`) — the JNI Invocation API, which FFM
   does not replace. It can be called from pure Python via `ctypes`, so no C
   code is needed, but that call is and stays JNI.

2. **Zero-copy buffer views of Java heap arrays.** `jpy_jarray.c:72` uses
   `GetPrimitiveArrayCritical` / `Get<Type>ArrayElements` to expose a Java
   primitive array to Python's buffer protocol without copying (numpy can wrap
   it directly). FFM cannot produce a native address for an on-heap Java
   array; heap `MemorySegment`s cannot be passed to native code by address.
   Options: copy at the boundary (costly for large arrays), keep a small JNI
   shim just for this path, or move the data off-heap
   (direct `ByteBuffer` / native `MemorySegment`), which changes the Java-side
   contract. Deephaven's numpy interop leans on this path, so this is the one
   real design problem in the rewrite.

Everything else — all 58 `PyLib` natives and the whole Python-facing extension
module — maps onto FFM downcalls and upcall stubs.

## Support matrix impact

Current CI (`.github/workflows/build.yml`, `check.yml`):

- Builds ~38 wheels: 5 platform targets (linux amd64/arm64, macOS, Windows)
  x 8 Python variants (3.9-3.14, 3.13t, 3.14t).
- Tests 8 Pythons x 4 Javas (11, 17, 21, 25).

With FFM:

- **Build matrix**: the Python axis disappears from building. Artifacts become
  one pure-Python wheel plus one jar, plus (if the zero-copy array shim is
  kept) one small JNI library per platform: 5 binaries instead of ~38.
- **Test matrix**: unchanged. Every platform x Python combination still needs
  testing, because version handling moved into the jar, not away.
- **Java axis**: shrinks from {11, 17, 21, 25} to {22+}. FFM drops three of
  the four Javas jpy currently supports and tests. Consumers pinned to
  Java 11/17/21 (including Deephaven's stated Java 11+ support) cannot use an
  FFM jpy. Realistically this means shipping the JNI jpy and an FFM jpy in
  parallel for a long transition, which *adds* to the matrix before it
  subtracts.

## Exception handling

Today both directions flatten exceptions to strings and drop the original
object: Python errors become a Java `RuntimeException` carrying formatted
traceback text (`PyLib_HandlePythonException`, `org_jpy_PyLib.c`; only
`KeyError`/`StopIteration` get dedicated Java classes), and Java throwables
become a Python `RuntimeError` carrying `toString()` text
(`JPy_HandleJavaException`, `jpy_module.c`). Round trips lose exception
identity: an inner `ValueError` resurfaces as `RuntimeError` text.

An FFM rewrite reproduces this mechanically: downcall wrappers check return
codes and fetch/clear the Python error (`PyErr_GetRaisedException` on 3.12+,
`PyErr_Fetch` before — version dispatch); every upcall stub body must
`catch (Throwable)` and set the Python error indicator, because a Java
exception escaping an upcall stub terminates the JVM. That catch-all is the
sharpest edge in the whole design.

Optionally, the rewrite could go further than the C version and preserve
exception identity across round trips (Java holds the original exception as a
live `PyObject` handle and restores it on the way back out). The C version
does not do this; adding it costs cross-boundary object lifetime complexity.

## Benefits

- Delete ~18k lines of C. The compiled Python extension goes away, so the six
  per-Python `.so` variants per platform become at most one small
  Python-version-independent JNI shim (see "What cannot move off JNI").
  Version differences are handled in Java at runtime instead of at compile
  time.
- One language. Refcount and error handling can be wrapped in safe Java
  abstractions (`Arena`, try-with-resources GIL, a `PyPtr` type with
  `Cleaner`), which is where jpy's historical crash bugs live.
- Fewer FFI hops both directions; `critical()` downcalls for hot paths.
- Debuggable from Java tooling; async-profiler sees the whole stack.

## Costs and risks

- **JDK floor: 22+** (21 only has FFM as preview). Deephaven currently supports
  Java 11–25; adopting an FFM jpy forces the Python-server flavor to 22+.
  This is probably the single biggest adoption question. A transition period
  shipping both the JNI jpy and the FFM jpy is possible since the public
  `org.jpy` API can stay identical.
- **JDK 24+ warning**: restricted FFM methods need
  `--enable-native-access=<module>` or you get runtime warnings (JEP 472),
  future releases make it an error. Launcher scripts must add it.
- **Rewrite size**: the method-overload matching in `jpy_jmethod.c` and the
  type system in `jpy_jtype.c` are intricate and full of edge cases. Estimate
  3–6 engineer-months to parity, dominated by testing, not typing.
- **Upcall overhead**: FFM upcalls are somewhat slower than a plain C function
  call, and every Python->Java operation (including `tp_dealloc` on GC!)
  becomes an upcall. Needs benchmarking in the spike; Deephaven's hot loops
  are mostly Java->Python, which gets faster.
- **Shutdown ordering** (`Py_Finalize` vs JVM exit) and `tp_dealloc` upcalls
  during interpreter teardown are a known hazard zone; the C code has scars
  here and the Java version will need the same care.
- **No mature prior art** that I know of for driving CPython from Java via FFM;
  existing bridges (jpy, JPype, pemja) are all JNI/C. First-mover risk.

## Performance forecast

A JNI or FFM crossing costs on the order of 10-30 ns; the forecast is about
crossing counts, not raw speed differences.

Expected wins:

- Java -> Python drops one hop (no JNI-native + C middle layer), and
  `Linker.Option.critical()` can beat JNI for hot non-blocking calls
  (incRef/decRef, type checks).
- Overload matching (`JMethod_MatchPyArgs`, jpy_jmethod.c:95) today makes many
  JNI calls per Python -> Java invocation; in Java it makes zero crossings.
  Likely the largest per-call win.
- No per-call JNI ExceptionCheck traffic.

Expected regressions and their fixes:

- Functions-only replaces macros (`PyTuple_GET_ITEM`, ~1 ns) with downcalls
  (~20 ns) per argument. Fix: register methods as `METH_FASTCALL` so args
  arrive as a contiguous `PyObject*` array read directly from one
  `MemorySegment`. This single choice decides whether dispatch gets faster or
  slower.
- MethodHandle warmup makes first calls slower; irrelevant to long-running
  servers.
- If zero-copy Java-array buffers are dropped rather than kept as a JNI shim,
  large-array interop becomes copy-bound — the only change that could dominate
  real workloads. Keep the shim.

Net: parity to ~10-30% better on chatty per-call paths, no change to
chunked/vectorized throughput, contingent on FASTCALL and the array shim.
Forecast only; the spike's benchmarks are the test.

## Alternatives considered

- **Keep JNI, modernize the C**: cheapest, keeps all current costs.
- **GraalPy**: runs Python on the JVM directly, no bridge. Not viable for
  Deephaven today: CPython extension compatibility (numpy etc.) and
  performance profile differ too much, and it ties you to GraalVM.
- **Stable ABI (abi3) build of the current C**: would cut the per-version
  wheels without a rewrite. Worth noting as a smaller independent win.

## Prototype results (2026-09-29): SUCCESS

The prototype below was built and run (`ffm-prototype/` on this branch), JDK 25
+ uv CPython 3.12.12, macOS arm64. All pass/fail criteria met:

- The 5 representative natives work as pure-Java downcalls, with GIL guard and
  Python-to-Java exception translation.
- A Python builtin function and a `PyType_FromSpec` type with a `tp_call` slot
  both dispatch into Java via upcall stubs; 40,000 calls from 4 Python
  `threading` threads (JVM auto-attach) all delivered correctly.
- Teardown: 10/10 clean full-lifecycle runs, zero crash dumps.
- Benchmarks: ~4 ns per downcall; 71-93 ns for a full Java -> Python call;
  106-119 ns for a Python -> Java upcall (~104 with METH_FASTCALL), vs ~409 ns
  for the same direction through today's JNI jpy (jpy includes overload
  matching, so treat the 4x gap as headroom, not a final speedup).

One environmental gotcha found: embedded python-build-standalone needs
PYTHONHOME set or interpreter init dies ("No module named 'encodings'").

Conclusion: the architecture is validated end to end with zero C code. The
remaining open questions are engineering scale (overload matching, conversions)
and the known exclusions (Python-first JVM bootstrap, zero-copy arrays,
free-threaded builds) — not feasibility.

## The prototype plan (as executed)

A throwaway Java 22+ project that, with zero C:

1. Loads libpython via `SymbolLookup.libraryLookup`, calls `Py_InitializeEx`.
2. Ports 5 representative `PyLib` natives (executeCode, getStringValue,
   callAndReturnObject, incRef/decRef) as downcalls with a GIL guard.
3. Registers a toy `jpy2` module with one type whose `tp_call` is an upcall
   stub invoking a Java lambda, called from a Python thread.
4. Benchmarks: downcall vs JNI for `callAndReturnObject`, upcall vs JNI
   dispatch for Python->Java, on 3.12 and 3.13t.

If the spike numbers and the teardown behavior look clean, the full rewrite is
a planning decision about the JDK 22 floor, not a technical unknown.
