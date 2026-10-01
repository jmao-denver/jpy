# Research: rewriting jpy with the Java FFM API (JDK 25+)

**Scope decision (2026-09-30): the FFM drop-in targets CPython 3.12+ only.**
3.12 is where PyType_FromSpec gained buffer slots and PyType_FromMetaclass
appeared, so a 3.12 floor keeps the whole implementation functions-only: no
per-version struct layouts, no PyErr_Fetch legacy path, no version dispatch.
This cuts roughly a quarter of the estimated effort and most of the ABI risk;
the supported set becomes 3.12, 3.13(+t), 3.14(+t). Sections below that
discuss 3.9-3.11 workarounds are kept for the record but are out of scope.

**Scope decision (2026-10-01): the JDK floor is 25 (LTS).** FFM is final
since JDK 22, but 22, 23 and 24 are not LTS releases. JDK 25 is the first
LTS that ships FFM final, so a single LTS floor keeps the support matrix to
one supported Java line. Earlier text below that says "22+" reflects the
state before this decision.

**Scope decision (2026-10-01): no array-pinning feature.** Neither a scoped
context manager nor a global switch: both are too easy to misuse (a pin
held across arbitrary Python code, released on another thread, or under a
collector without region pinning). The FFM jpy keeps the C jpy's copy
semantics. The FFM-to-JNI pinning technique stays documented below for the
record only.

**Scope decision (2026-09-30): statically linked Pythons are unsupported.**
A shared libpython is required in both start modes — the same requirement
Java-first jpy has today (Deephaven already tells pyenv users to build with
--enable-shared). This drops the dlsym(RTLD_DEFAULT)/GetProcAddress symbol
fallback from the plan: one FFM libraryLookup path everywhere. Note this is
a small regression vs today's Python-first mode, where the C extension works
with static Pythons for free; accepted deliberately.

**Non-Deephaven users (2026-09-30)**: Deephaven is the driving use case but
not the only active user (jpy originates from Brockmann Consult; the ESA
SNAP/toolbox community and other downstreams embed it, often on older
JDK/Python stacks). Consequences: (a) the parity bar stays jpy's own full
test suites, not the Deephaven-exercised subset; (b) the FFM jpy ships as a
new major version alongside a maintained JNI line rather than replacing it —
the old build matrix only retires when the JNI line does; (c) environment
floors (JDK 25+, Python 3.12+, shared libpython) are acceptable for a new
major version, behavioral changes are not — quirks are replicated, not fixed.

**Distribution shape**: one pure-Python py3-none-any wheel (the ctypes loader
plus the jar as package data). If the heap-array JNI shim is kept, its
per-platform libraries ride inside the jar (extracted and System.load-ed at
runtime, sqlite-jdbc style), keeping the wheel universal. Binary Python
extensions: zero.

Date: 2026-09-29. Based on the current source in this repo.

## TL;DR

It is feasible, with two exceptions (see "What cannot move off JNI"). FFM
(java.lang.foreign, final in Java 22 via JEP 454) can replace both halves of
jpy: the JNI native methods that let Java call CPython, and the hand-written
CPython extension module that lets Python call Java. The payoff is
large: almost no C code, no per-Python-version compiled extension (the
per-version handling moves into the jar as runtime dispatch — see "Version
portability"), and one fewer FFI hop in each direction. The costs are a big rewrite (~18k lines of C to
replace), a hard JDK 25+ floor (decided; see scope decisions), and careful GIL and refcount handling moving
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

2. **Correction (2026-10-01, measured in session 5): this item is wrong as
   written below.** The current C jpy does not pin: `GetPrimitiveArrayCritical`
   is compiled out (`JPy_USE_GET_PRIMITIVE_ARRAY_CRITICAL` is commented out
   in `jpy_jarray.c`), and the active path uses `Get<Type>ArrayElements`,
   which copies on HotSpot. Measured on the C jpy: views are snapshots taken
   at the first export; the copy is written back only when the Python
   wrapper is deallocated, and only after a writable export (it then
   overwrites any Java-side change made in between). Deephaven's vectorized
   UDFs do not use the buffer protocol at all (`deephaven/_udf.py` reads with
   `zip(*args[2:])` and writes with `chunk_result[i] = ret`). So nothing here
   needs JNI: the FFM jpy ports the copy semantics in pure FFM, and
   `ffm/tests/ffm_buffer_test.py` passes on both implementations. The text
   below, including the FFM-to-JNI pinning update, is kept as the record of
   a technique that would enable a future zero-copy improvement.

   **Zero-copy buffer views of Java heap arrays.** `jpy_jarray.c:72` uses
   `GetPrimitiveArrayCritical` / `Get<Type>ArrayElements` to expose a Java
   primitive array to Python's buffer protocol without copying (numpy can wrap
   it directly). FFM cannot produce a native address for an on-heap Java
   array; heap `MemorySegment`s cannot be passed to native code by address.
   This is a core jpy feature, not a Deephaven detail: any user doing
   `np.frombuffer(java_array)` or `memoryview(java_array)` gets a zero-copy,
   write-through view, and always copying would be slower for large arrays
   and would silently break write-through for everyone.
   Deephaven is the heaviest user (verified in source): the engine
   copies each chunk into reusable heap scratch arrays
   (FillContextPython.java: sourceChunks[i].copyToArray(...)) plus a return
   array, and Python wraps those zero-copy via np.frombuffer(j_array)
   (jcompat.py) — jpy's buffer protocol, GetPrimitiveArrayCritical
   underneath. Results flow back by numpy writing into the Java return
   array, so the aliasing is semantic, not an optimization: a drop-in must
   keep it. Hence the ~150-line JNI pin/unpin shim, shipped inside the jar.
   Risk profile is mild: engine pins are short-lived (one UDF call per
   chunk), and the required JDK 22+ brings G1 region pinning (JEP 423), so
   pinned chunks no longer stall GC as on JDK 11. Long-lived pins remain
   possible via user-held jpy.array views — same as today.
   Longer term, the vectorized path could go shim-free with a localized
   engine change: the data is already copied once into scratch arrays, so
   allocating that scratch off-heap (MemorySegment) costs the same copy and
   lets numpy wrap native memory with no pin at all. The shim would then
   serve only user-held jpy.array views over real column arrays.
   **Update (2026-09-30, measured): the shim needs no compiled C.** FFM can
   call JNI functions directly, since they are C function pointers in the
   JNIEnv table (JNIEnv via the exported JNI_GetCreatedJavaVMs + GetEnv).
   FFM cannot pass a Java object to native code, but a JNI call can return
   one: CallStaticObjectMethodA on a Java `fetch(id)` registry method yields a
   jobject, and GetPrimitiveArrayCritical on it yields the pinned address.
   Prototype `ffm-prototype/src/ffm/M10.java`: in-place pin (isCopy false),
   pin held across ~200 MB of allocation plus System.gc(), native write seen
   by Java, 10/10 clean runs, 761 ns per pin+unpin (0.19 ns/row for a
   4096-row chunk). Two rules learned the hard way (both crashed first):
   (1) FindClass through an FFM downcall uses java.base's class loader, so
   application classes are bootstrapped via
   ClassLoader.getSystemClassLoader().loadClass(); (2) JNI local refs die as
   soon as any Java code runs (including inside a JNI Call*Method), so every
   local ref must become a global ref in the very next call, with all method
   handles bound up front. Risk: calling JNI functions from a thread inside
   an FFM downcall is outside what the JNI spec describes (it assumes native
   method frames). It works on HotSpot/JDK 25 and the rules above make it
   robust, but a future JDK could change handle-block behavior. The
   off-heap-scratch direction below removes the dependency entirely.
   FFM does have its own heap pinning — `Linker.Option.critical(true)`
   passes a heap MemorySegment's real address to a downcall — but the pin
   lasts one downcall and upcalls are forbidden during it, so it cannot back
   the buffer protocol (Python keeps the pointer and runs arbitrary code
   while it is held). It is the right tool only for transient one-shot
   calls on heap arrays.

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
- **Java axis**: shrinks from {11, 17, 21, 25} to {25+}. FFM drops three of
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

- **JDK floor: 25+** (FFM is final since 22, but 22-24 are not LTS; decided 2026-10-01). Deephaven currently supports
  Java 11–25; adopting an FFM jpy forces the Python-server flavor to 25+.
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

## Performance-only ROI (2026-09-30)

Measured per-crossing costs (Apple M-series, JDK 25 / CPython 3.12.12; trivial
function bodies, 1-2 args):

| direction | JNI jpy today | FFM prototype | saving |
|---|---|---|---|
| Python -> Java call | ~409 ns | ~106 ns | ~300 ns (≈4x) |
| Java -> Python call | ~1447 ns (PyObject.call("__call__", x)) | ~71-93 ns (held callable) | up to ~1.35 us (≈15x) |

Caveats: the FFM Python->Java number has no overload matching yet (it is the
floor); and part of the Java->Python gap is jpy's API design (per-call
"__call__" name lookup, wrapper churn), which FFM enables us to skip but which
a targeted JNI-jpy optimization could also partially recover.

ROI = (ns saved per crossing) x (crossings per second in real workloads):

1. **Vectorized / chunked Python UDFs** (the recommended Deephaven pattern):
   one crossing per chunk of ~4096 rows -> saving ~0.3 ns/row. **ROI ~ zero.**
2. **Scalar per-row Python UDFs** (non-vectorized update/where): one crossing
   per row. Today's ~1.4 us bridge overhead often rivals the Python body
   itself (0.5-5 us). Saving ~1.3 us/row is a **20-70% throughput gain on
   this path** (10M rows: ~14 s of bridge overhead -> ~1 s). But this is the
   path users are already steered away from.
3. **Listeners / callbacks per update cycle**: 100s-1000s of crossings/sec ->
   microseconds saved per second. Noise.
4. **Barrage / data plane**: no jpy crossings at all. Zero.
5. **Interactive scripting (Python driving Java)**: human-scale. Imperceptible.

**Real-engine measurement** (embedded Deephaven server 43.0-snapshot, JDK 21,
CPython 3.12, Apple M-series; `update` on 10M/2M-row static tables, best of 3):

| path | per row |
|---|---|
| Java formula `X + 1` | 2 ns |
| Python UDF `f(X)`, auto-vectorized | 305 ns |
| Python UDF `f(X) + 0`, forced scalar | 1916 ns |
| the Python body `f(1)` alone | 36 ns |

On the scalar path, bridge+boxing overhead is ~1878 ns/row — 98% of the cost
(the measured 1447 ns PyObject.call crossing plus engine-side boxing). FFM
projection: crossing ~100 ns, engine-side prep remains, landing zone
~300-500 ns/row = **4-6x on scalar UDFs with trivial bodies** (2x with a 1 us
body, ~8% with 10 us). The vectorized path's 305 ns/row is chunk marshaling
and numpy with almost no per-row crossings; FFM barely moves it.

The GIL, not the bridge, remains the ceiling for Python-heavy workloads, and
FFM does nothing about it (free-threaded support is orthogonal work in either
implementation).

**Free-threaded comparison** (same 3.13.9t interpreter, GIL off, M9 vs
bench/jpy_ft_bench.py): single-thread deltas match the GIL-build ratios
(P->J 333 ns JNI vs 99 ns FFM; J->P 1310 vs 79). At 4 Python threads both
bridges hit the identical ~5.6 Mcalls/s ceiling — free-threaded CPython's
own refcount contention on shared objects, not the bridge — so bridge choice
is irrelevant to FT scalability. One positive: 4 Java threads calling
Python scaled ~1.7x (21.5 Mcalls/s), which a GIL build cannot do. The FFM
prototype ran on 3.13t with zero code changes.

**Conclusion: performance alone does not pay for 15-19 weeks.** The honest
case for the rewrite is maintenance and risk (delete ~18k lines of C, collapse
the ~38-wheel build matrix, convert segfault-class bugs into exceptions),
platform strategy (JNI is being progressively restricted; FFM is the
supported path), and free-threading readiness — with the per-call speedups as
a bonus that materially helps only scalar-UDF-heavy users. If per-call
overhead is the immediate pain, a cheaper targeted fix exists: optimize the
JNI jpy call path (cache the callable, skip the name lookup) for a fraction
of the cost.

## Drop-in plan and re-estimate (2026-09-30, for CPython 3.12+ only)

"Drop-in" means jpy's own test suites (24 Python test files, 11 Java test
classes) pass unmodified against the FFM build.

Already banked by the prototype: bindings, GIL guard, exception translation,
upcalls (functions and type slots), both bootstraps, conversion parity (M8),
benchmarks. Direct ByteBuffers are fully covered by FFM
(MemorySegment.ofBuffer, zero-copy both ways), so the JNI shim question is
confined to Java HEAP arrays only; steering hot numpy paths toward direct
buffers / native MemorySegments shrinks it further.

| Component | Weeks |
|---|---|
| Type system (get_type, bases, constructors, fields, cache) | 3-4 |
| Overload resolution + conversion integration | 2-3 |
| Java-side PyObject lifecycle (references, cleanup thread, shutdown) | 1-1.5 |
| Arrays + buffer protocol (spec slots legal on 3.12+; heap-array shim optional) | 2 |
| Proxies | 1 |
| Remaining PyLib natives + jpy.cast/convert/diag/translation | 1.5-2 |
| Bootstrap productization (jpyutil compat, Linux/Windows discovery) | 1-1.5 |
| jsr223 | 0.5 |
| Test convergence, free-threaded (3.13t/3.14t), platform matrix | 3-4 |
| **Total** | **15-19 weeks (~3-4.5 months)** |

The 3.12+ floor deleted version dispatch and buffer-slot struct poking (was
3-6 months with 3.9-3.11). The bulk was always the type system, overload
matching, and bug-for-bug test convergence; that part did not move.

### Hands-off (agent-run) execution plan

Each session is prototype-sized (hours, single-digit-to-low-tens of millions
of tokens), ends with committed code and a pass/fail verdict defined by jpy's
own tests, never by judgment:

1. jpy_gettype_test.py — type system core. Ends with a design note the
   reviewer can veto before session 2 (the one decision with a long shadow:
   how Java-class metadata maps onto Python type objects).
2. jpy_obj_test.py + jpy_field_test.py — instances, methods, fields
3. jpy_overload_test.py + jpy_typeres_test.py — overload matching
4. jpy_typeconv_test.py + jpy_retval_test.py — conversions wired into calls
5. jpy_array_test.py — arrays + buffer protocol
6. Java side: PyObjectTest, PyModuleTest, lifecycle/cleanup tests
7. PyProxyTest + exception/translation tests
8. Full both-suite sweep on 3.12, then 3.13/3.14, then 3.13t/3.14t
9. Linux and Windows (loader and libpython discovery are untested off macOS; needs boxes or CI)

Overall: order of 50-150M tokens across ~9 sessions, 2-4 calendar weeks at
whatever launch pace, ~10 minutes of human review per session.

Known frays and their mitigations: cross-session drift (the repo, this doc,
and the tests are the memory); test-convergence rabbit holes (a test that
resists two focused attempts is skipped and listed in the session report, not
hidden); free-threaded Python may legitimately end in "conclusive failure
with findings".

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
a planning decision about the JDK floor, not a technical unknown.
