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

## Architecture

~95% Java. Two small JNI survivors, both spec-stable:

1. **Python-first bootstrap**: a ~100-line pure-Python ctypes loader calls
   `JNI_CreateJavaVM` and hands off; Java then registers the `jpy` module
   into the running interpreter via FFM. No compiled Python extension.
2. **Array-pinning shim** (~150 lines of JNI, shipped inside the jar):
   FFM cannot produce a stable pointer to a Java heap array, and the
   vectorized-UDF path needs exactly that — verified in deephaven-core
   source: the engine copies chunks into heap scratch arrays
   (`FillContextPython.copyToArray`) which Python wraps zero-copy
   (`np.frombuffer`) and writes results back through. The aliasing is
   semantic; a drop-in must keep it. Pins are short-lived (one UDF call per
   chunk) and JDK 22's G1 region pinning (JEP 423) makes them benign.
   Long term, allocating that scratch off-heap (`MemorySegment`) removes the
   pin entirely with a localized engine change.

**Update: the pinning shim needs no compiled C.** FFM can call JNI's own
function table directly: a Java registry method returns the array as a
jobject through `CallStaticObjectMethodA`, and `GetPrimitiveArrayCritical`
pins it, all as FFM downcalls. Measured in `ffm-prototype/src/ffm/M10.java`:
in-place pin, stable across a full GC, 761 ns per pin+unpin (0.19 ns/row per
4096-row chunk), 10/10 clean runs. It works outside what the JNI spec
describes (JNI assumes native method frames), so it depends on two rules
recorded in the research doc and on HotSpot behavior staying as it is.

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
| 1. Type system | **done 2026-09-30**: `jpy_gettype_test.py` 8/8 on 3.12, 3.13, 3.14, 3.13t, 3.14t; design note `ffm/DESIGN.md` awaiting review |

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
- **Platform risk**: if the JDK ever retires JNI critical regions, the shim
  needs the off-heap-scratch engine change described above.

## Decision requested

Green-light session 1 (type system against `jpy_gettype_test.py`), with the
session-1 design note as the first review gate.
