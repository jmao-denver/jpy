# Plan: FFM-based jpy (drop-in successor to JNI jpy)

Status: proposal for peer review. Companion document: `FFM_REWRITE_RESEARCH.md`
(full analysis, measurements, prototype results). Prototype code:
`ffm-prototype/` on branch `worktree-ffm-prototype`.

## Goal

A rewrite of jpy on the Java FFM API (java.lang.foreign, final in JDK 22)
that is a drop-in for the current JNI jpy:

- same `jpy` module API in Python, same `org.jpy` API in Java
- definition of done: **jpy's own test suites (24 Python test files, 11 Java
  test classes) pass unmodified** against the FFM build

## Scope decisions (made, with rationale)

| decision | rationale |
|---|---|
| CPython 3.12+ only | 3.12 added buffer slots to `PyType_FromSpec` and `PyType_FromMetaclass`; a 3.12 floor keeps the implementation functions-only — no version-specific struct layouts anywhere |
| JDK 22+ (developed on 25) | FFM is final in 22; JDK 25 is the LTS carrying it |
| shared libpython required | matches today's Java-first requirement; drops the dlsym fallback. Deliberate small regression vs today's Python-first mode on statically linked Pythons |
| quirks replicated, not fixed | drop-in means bug-for-bug: smallest-box untyped ints (5 -> Byte), char <-> int, truthiness bools, silent narrowing truncation, string-flattened exceptions |
| dual-track release | Deephaven drives this, but jpy has other users (ESA SNAP et al.) on older stacks. FFM jpy ships as a new major version beside a maintained JNI line; the old build matrix retires only when the JNI line does |

## Improvements over the C jpy

Additive changes: code that works on the C jpy keeps working the same way.
Details and rules in `ffm/DESIGN.md` §11.

1. **Public static non-final fields are visible and writable from Python,
   live**, through the class and through instances (decided 2026-10-01).
   The C jpy skips them because its metatype hook crashed the interpreter.
   The FFM jpy adds a real metaclass, `jpy.JTypeMeta`; `jpy.JType` stays the
   base class of every Java class. Only `type(T)` changes, from `type` to
   `jpy.JTypeMeta`.
2. **Class-level access to a Java member resolves an unresolved type.** The
   C jpy raises `AttributeError` until an instance attribute was touched.
3. **No compiled code at all.** One universal wheel plus a jar, instead of
   ~38 binary wheels; heap arrays are pinned through FFM calls into JNI.

## Architecture

All Java and Python, with no compiled code. Two pieces still go through JNI,
reached from Python with ctypes or from Java with FFM:

1. **Python-first bootstrap**: a pure-Python loader calls
   `JNI_CreateJavaVM` through ctypes. Java then adds the bridge to the
   running `jpy` module through FFM. No compiled Python extension.
2. **Heap-array pinning**: jpy exposes Java primitive arrays to Python
   through the buffer protocol, zero-copy. Every jpy user doing
   `np.frombuffer(java_array)` or `memoryview(java_array)` relies on this,
   and writes through the view land in the Java array. FFM alone cannot
   give a stable pointer to a Java heap array. Copying instead is slower for
   large arrays and breaks write-through for every user, so pinning is a
   core jpy feature, not a Deephaven detail. Deephaven's vectorized UDFs are
   the heaviest user (verified in deephaven-core source): the engine copies
   chunks into heap scratch arrays (`FillContextPython.copyToArray`), Python
   wraps them with `np.frombuffer`, and results come back through numpy
   writing into the Java return array.

   **It needs no compiled shim.** FFM can call JNI's own function table
   directly: a Java registry method returns the array as a jobject through
   `CallStaticObjectMethodA`, and `GetPrimitiveArrayCritical` pins it, all
   as FFM downcalls. Measured in `ffm-prototype/src/ffm/M10.java`: in-place
   pin, stable across a full GC, 761 ns per pin+unpin (0.19 ns/row per
   4096-row chunk), 10/10 clean runs. JDK 22's G1 region pinning (JEP 423)
   means pinned arrays no longer stall the GC.

**Distribution**: one pure-Python `py3-none-any` wheel (loader + jar as
package data) instead of today's ~38 binary wheels. Zero compiled code of
any kind: no Python extensions, no per-platform JNI libraries.

## Evidence so far (all committed, all green)

Prototype on macOS arm64, JDK 25, CPython 3.12.12:

- CPython embedded from pure Java; both bootstrap directions work
- Upcall stubs as Python builtins and as `PyType_FromSpec` type slots;
  40,000 calls from 4 Python threads (JVM auto-attach); 10/10 clean teardowns
- Exception translation both ways; GIL as a try-with-resources guard
- Conversion parity suite (M8): 39 checks replicating jpy's rules and quirks,
  under the real `org.jpy` names

Benchmarks (trivial bodies, per call):

| path | JNI jpy | FFM prototype |
|---|---|---|
| Python -> Java | ~409 ns | ~106 ns |
| Java -> Python | ~1447 ns | ~71-93 ns |

Real-engine measurement (embedded Deephaven 43.0-snapshot, `update` on
static tables): scalar Python UDF costs 1916 ns/row of which ~1878 ns
(98%) is bridge+boxing; the Python body itself is 36 ns. Auto-vectorized:
305 ns/row, almost no per-row crossings.

Free-threaded comparison (same 3.13.9t interpreter, GIL off): single-thread
deltas match the GIL-build ratios (P->J 333 ns JNI vs 99 ns FFM; J->P 1310
vs 79). At 4 Python threads both bridges hit the identical ~5.6 Mcalls/s
ceiling — free-threaded CPython's own refcount contention, not the bridge —
so bridge choice is irrelevant to FT scalability. 4 Java threads calling
Python scaled ~1.7x, which a GIL build cannot do. The FFM prototype ran on
3.13t with zero code changes.

## Performance ROI (honest version)

- Scalar per-row Python UDFs: projected **4-6x** (landing zone
  300-500 ns/row; dilutes to 2x with a 1 us UDF body)
- Vectorized UDFs, listeners, Barrage, interactive use: **~unchanged**
- The GIL remains the ceiling for Python-heavy work; FFM does not address it

**Performance alone does not justify the project.** The case is: delete
~18k lines of C, collapse ~38 wheels to 1, turn segfault-class bugs into
Java exceptions, and move off JNI while the JDK progressively restricts it.
Speedups are a bonus concentrated in the scalar-UDF path.

## Status

| session | result |
|---|---|
| 1. Type system | **done 2026-09-30**: `jpy_gettype_test.py` 8/8 on 3.12, 3.13, 3.14, 3.13t, 3.14t; design note `ffm/DESIGN.md` approved 2026-10-01 |
| 2. Objects, methods, fields | **done 2026-10-01**: `jpy_obj_test.py` 2/2, `jpy_field_test.py` 3/3 (already green after session 1) |
| 3. Overload resolution | **done 2026-10-01**: `jpy_overload_test.py` 18/18, `jpy_typeres_test.py` 3/3; added `jpy.cast`, `jpy.array` |
| 4. Conversions wired into calls | **done 2026-10-01**: `jpy_typeconv_test.py` 11/11, `jpy_retval_test.py` 12/12, `jpy_modretparam_test.py` 7/7; added `jpy.convert`, `jpy.type_callbacks`, `jpy.JMethod`, Python buffers as primitive-array arguments with write-back, return-parameter identity. All green on 3.12, 3.13, 3.14, 3.13t, 3.14t |

Full suite after session 4 (2026-10-01): **99/154 pass, 0 failures**, 55
errors, 0 crashes; 12 of 22 files fully green. FFM extras 27/27. The
remaining errors: the buffer protocol on Java arrays (9, session 5) and the
Java-to-Python half, `org.jpy.PyLib`/`PyObject` (most of the rest, session
6), plus `jpy.diag` and `VerboseExceptions` (session 7).

Full-suite baseline after session 1 (2026-10-01, CPython 3.12 + numpy,
`ffm/suite.py`): all 22 Python test files from setup.py, 154 tests: **70
pass**, 6 fail, 78 error, **0 crashes, 0 timeouts**. Files fully green:
gettype, field, retval, typeres, translation, obj, mt. Every failure traces
to a feature a later session owns: Java-side `org.jpy.PyObject`/`PyLib`/
`PyInputMode` (43, session 6), `jpy.array` (25, session 5), `jpy.convert`/
`cast` (9), `diag`/`VerboseExceptions` (3, session 7), return/mutable
parameters via `type_callbacks` (6, session 4). None is a type-creation bug.
The Java JUnit tests (Java to Python) do not compile yet: they need
`org.jpy.PyLib`, `PyObject`, `PyModule`, `PyInputMode` (session 6).

## Execution plan: 9 hands-off agent sessions

Each session ends with committed code and a pass/fail verdict defined by the
named test files (never by judgment), plus a short report; tests that resist
two focused attempts are skipped and listed, not hidden.

1. **Type system** — `jpy_gettype_test.py`. Ends with a design note for
   reviewer veto before session 2 (the one decision with a long shadow: how
   Java-class metadata maps onto Python type objects)
2. **Objects, methods, fields** — `jpy_obj_test.py`, `jpy_field_test.py`
3. **Overload resolution** — `jpy_overload_test.py`, `jpy_typeres_test.py`
4. **Conversions wired into calls** — `jpy_typeconv_test.py`, `jpy_retval_test.py`
5. **Arrays + buffer protocol + pinning shim** — `jpy_array_test.py`
6. **Java-side lifecycle** — `PyObjectTest`, `PyModuleTest`, cleanup/reachability
7. **Proxies + exception translation** — `PyProxyTest`, exception tests
8. **Full sweep** — both suites on 3.12, then 3.13/3.14, then 3.13t/3.14t
9. **Linux and Windows** — the loader (jvm.dll via ctypes), libpython discovery, and the shim build are untested off macOS; needs Linux/Windows boxes or CI

## Cost and risks

- **Effort**: 15-19 engineer-weeks equivalent. As agent sessions: ~2-4
  calendar weeks, order of 50-150M tokens, ~10 minutes of human review per
  session.
- **Front-loaded risk**: sessions 1-3 (type system, overloads) hold any
  design surprises; the back half is mechanical.
- **Free-threaded Python** (session 8 tail) may end in "conclusive failure
  with findings" — a legitimate outcome, mirrored by the JNI jpy's own
  recent free-threading work. Partially de-risked: the prototype already
  runs on 3.13t unchanged, though the full type system will face real
  concurrency questions the prototype does not.
- **Dual-track overhead**: maintaining both implementations during the
  overlap raises total burden before the matrix savings cash in.
- **Pinning risk**: calling JNI from inside an FFM call is outside what the
  JNI spec describes (it assumes classic native methods). It works on
  HotSpot/JDK 25 only with two rules, both learned from crashes: bootstrap
  application classes through `ClassLoader.getSystemClassLoader()`, and make
  every JNI handle permanent in the very next call, since any Java code
  running invalidates temporary handles. If a future JDK breaks this, the
  fallback is a small compiled JNI library inside the jar (per platform).
  Copying is not a real fallback: it breaks write-through for every jpy
  user. Deephaven could move its scratch arrays off-heap to avoid pinning on
  its hot path, but that does not help other jpy users.

## Decision requested

Green-light session 1 (type system against `jpy_gettype_test.py`), with the
session-1 design note as the first review gate.
