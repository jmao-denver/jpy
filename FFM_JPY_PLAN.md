# Plan: FFM-based jpy (drop-in successor to JNI jpy)

Status: proposal for peer review. Companion document: `FFM_REWRITE_RESEARCH.md`
(full analysis, measurements, prototype results). Prototype code:
`ffm-prototype/` on branch `worktree-ffm-prototype`.

## Goal

A rewrite of jpy on the Java FFM API (java.lang.foreign; final since JDK 22, targeted at JDK 25 LTS)
that is a drop-in for the current JNI jpy:

- same `jpy` module API in Python, same `org.jpy` API in Java
- definition of done: **jpy's own test suites (24 Python test files, 11 Java
  test classes) pass unmodified** against the FFM build

## Scope decisions (made, with rationale)

| decision | rationale |
|---|---|
| CPython 3.12+ only | 3.12 added buffer slots to `PyType_FromSpec` and `PyType_FromMetaclass`; a 3.12 floor keeps the implementation functions-only — no version-specific struct layouts anywhere |
| JDK 25+ (LTS) | decided 2026-10-01: JDK 25 is the first LTS with FFM final (FFM is final since 22, which is not LTS); a single LTS floor keeps the support matrix small |
| shared libpython required | matches today's Java-first requirement; drops the dlsym fallback. Confirmed 2026-10-02 as a narrow gap with an easy workaround: it affects only Python-first on Linux with a Python whose executable has libpython built in, such as Ubuntu's and Debian's own `python3` and uv's Linux builds (checked in Docker). C jpy works there because a C extension finds Python's functions in the executable. Workaround: a Linux Python built with a shared libpython (the official `python` Docker images; any `--enable-shared` build), or Java-first. The FFM jpy stops at once with a clear message on such a Python. Java-first is unaffected: Deephaven's server images already install Ubuntu's separate `libpython` package for it |
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
3. **A Java holder that outlives a `jpy.byte_buffer` wrapper gets an
   exception instead of reading freed memory** (decided 2026-10-01). The
   documented rule is unchanged. Data races on shared memory stay the
   application's job.
4. **No compiled code at all.** One universal wheel plus a jar, instead of
   ~38 binary wheels.

## Architecture

All Java and Python, with no compiled code. One piece still goes through
JNI, reached from Python with ctypes:

1. **Python-first bootstrap**: a pure-Python loader calls
   `JNI_CreateJavaVM` through ctypes. Java then adds the bridge to the
   running `jpy` module through FFM. No compiled Python extension.

Java-first needs no JNI at all: `org.jpy.PyLib` loads libpython, calls
`Py_Initialize` and imports `jpy` through FFM (session 6).

**Correction (2026-10-01, session 5): the buffer protocol needs no
pinning.** Earlier versions of this plan said jpy exposes Java primitive
arrays zero-copy, that every `np.frombuffer(java_array)` user relies on
write-through, and that Deephaven's vectorized UDFs write results back
through numpy. All three were wrong, found by measuring instead of reading:

- The current C jpy does not pin. `GetPrimitiveArrayCritical` is disabled
  in `jpy_jarray.c`; it uses `Get<Type>ArrayElements`, which copies on
  HotSpot. Measured on the C jpy: a view is a snapshot taken at the first
  export; Python writes are invisible to Java and Java writes are invisible
  to the view; the copy is written back only when the Python wrapper is
  deallocated, and only if some export was writable. `memoryview()` and
  `np.frombuffer()` get read-only views.
- Deephaven's vectorized UDF wrapper (`deephaven/_udf.py`) does not use the
  buffer protocol at all: it reads arguments with `zip(*args[2:])` and writes
  results with `chunk_result[i] = ret`, both through jpy's element-wise
  sequence protocol. `np.frombuffer` is used elsewhere, read-only.

So the FFM jpy ports the copy semantics directly, in pure FFM (native
memory plus `MemorySegment.copy`), and `ffm/tests/ffm_buffer_test.py`
passes identically on both the C jpy and the FFM jpy. The FFM-to-JNI
pinning technique (`ffm-prototype/src/ffm/M10.java`) still works, but is not
needed for parity.

**Decided 2026-10-01: no pinning feature**, neither opt-in nor scoped. A
`with jpy.pinned(arr)` block cannot bound the lifetime of what Python
derives from the view (`result = np.frombuffer(view)` outlives the block).
At the end of the block that leaves two choices: unpin anyway, which turns a
surviving numpy array into silent memory corruption, or keep the pin until
the last view dies, which is an unbounded pin released on an arbitrary
thread, the same risk as a global switch. Copy semantics stay.

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

## Measured on the full port (2026-10-01, after session 7)

`ffm/bench/run.sh`: the same script on the C jpy and the FFM jpy, CPython
3.12.12, JDK 25, macOS arm64, ns per call, median of 5 runs, two runs
agreeing within ~10%. No performance work done yet (no caching, plain
reflection, no `critical` downcalls).

| case | C jpy | FFM jpy |
|---|---|---|
| Python baseline: call a Python function | 25 | 25 |
| static, no args | 365 | 230-250 |
| instance, 1 int | 410 | 335 |
| `String.length()` | 385 | 300 |
| 5 overloads, int / str / float arg | 390-570 | 265-430 |
| `Math.max(3, 4)` | 405 | 295 |
| 3 `Object` args (boxing) | 1130 | 505 |
| returns a Java object (`lst.get(0)`) | 1075 | 430 |
| constructor `Integer(5)` | 595 | 470 |
| attribute only, `s.length` (bind, no call) | 80 | 165 |
| Java -> Python, `PyObject` result | 1375 | 490-535 |
| Java -> Python, `Integer` result | 2050-2320 | 280-300 |

Python to Java is 1.2-2.6x faster, Java to Python 2.6-8x. The one
regression is attribute lookup on a Java object (`s.length` without the
call): about 85 ns slower, because the C jpy's `tp_getattro` is C and ours
is an upcall into Java doing several downcalls. Calls are faster overall
despite it. The prototype's ~106 ns per Python-to-Java call is not reached:
the full port does overload scoring and many small CPython downcalls per
call. Those are the levers if more speed is wanted.

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
| 5. Arrays + buffer protocol | **done 2026-10-01**: `jpy_array_test.py` 23/23 on 3.12, 3.13, 3.14, 3.13t, 3.14t; copy semantics measured on the C jpy and ported in pure FFM (no pinning, no JNI); added `jpy.byte_buffer`; `ffm/tests/ffm_buffer_test.py` parity tests pass on both the C and FFM jpy |
| 6. Java-side lifecycle | **done 2026-10-01**: all 81 of jpy's JUnit tests (the 8 classes Maven runs, `PyProxyTest` included) and every Python test that crosses back into Java (`eval_exec`, `mt_eval_exec`, `reachability_fence`, `cleanup_thread`, `typeconv_test_pyobj`, `java_embeddable`) pass on 3.12, 3.13, 3.14, 3.13t, 3.14t. `org.jpy.PyLib` ported to FFM (`PyLibImpl`), Java-first startup without JNI |
| 7. Diagnostics + exception translation | **done 2026-10-01**: `jpy_diag_test.py` 2/2, `jpy_exception_test.py` 5/5, FFM extra `ffm_exceptions_test.py` 7/7 (the C jpy's exact verbose format, line by line), all on 3.12, 3.13, 3.14, 3.13t, 3.14t. Added `jpy.diag`, `jpy.VerboseExceptions`, Java cause chains. Not ported: the C jpy's diagnostic trace printouts |
| 8. Full sweep | **done 2026-10-01**: `ffm/sweep.sh`. On 3.12, 3.13, 3.14, 3.13t and 3.14t, each in a venv with numpy 2.5.3: jpy's Python suite **154/154**, FFM extras 49/49, jpy's JUnit tests 81/81. On 3.13t and 3.14t the GIL stays off with jpy and numpy loaded, and 3 more full runs each were green. New FFM extra `ffm_threads_test.py`: 16 threads at once create and resolve Java types, call overloads, convert values and call back from Java into Python; 10/10 runs green on each free-threaded build |

Full suite after session 7 (2026-10-01): **154/154 pass** on 3.12, all 22
files green. FFM extras 44/44. JUnit 81/81 on all five interpreters.

| 9. Linux and Windows | **done 2026-10-02**: `.github/workflows/ffm.yml` on `jmao-denver/jpy` branch `ffm-jpy`, run 37058526470: all 20 jobs green, Linux x64, Linux arm64, Windows x64 and macOS arm64 × 3.12, 3.13, 3.14, 3.13t, 3.14t, JDK 25. Each runs `ffm/ci.py`: jpy's Python suite 154/154, FFM extras 49/49, jpy's JUnit tests 81/81, except on Windows two JUnit tests of jpy's own that assume lowercase hex in Python's default repr (`PyObjectTest.strNotDefined`, `PyProxyTest.doesNotHaveStrToString`); C jpy's CI never runs JUnit on Windows. Fixed for Windows: `PyBool_FromLong` (C `long` is 32-bit there), finding `jvm.dll`. Linux is also scripted in Docker (`ffm/docker/run.sh`) |

The definition of done is met: jpy's own test suites pass unmodified on
Linux, Windows and macOS, on every supported interpreter, except the two
Windows-only test bugs above.

Deephaven Core, first try (2026-10-02), Python-first with the embedded
Deephaven 43.0 server, the FFM jpy wheel (`ffm/package.py`) swapped for the
C jpy in a copy of the same venv, JDK 25:
- A 21-check smoke test (tables, Python UDFs, pandas/numpy with nulls,
  joins, errors) gives identical results on both jpys.
- Scalar Python UDF: about 2x faster (1040 vs 2015 ns per row).
- Auto-vectorized Python UDF: about 25% slower (398 vs 314 ns per row).
  Deephaven reads and writes Java arrays one element at a time from Python
  there, and each element access is an upcall into Java that also does
  type lookups and boxing. Top performance item.
- Deephaven 43's default JVM options include
  `-XX:GCLockerRetryAllocationCount`, which JDK 25 no longer accepts, so
  Deephaven needs that removed to run on JDK 25 with either jpy.

Introspection parity (2026-10-01): about 800 facts about types, methods and
fields compared with the C jpy on all five interpreters
(`ffm/tests/ffm_introspection_test.py`). Five differences fixed; five kept,
all from heap types and the metaclass (`ffm/DESIGN.md` §15). FFM extras
45/45.

Full suite after session 6 (2026-10-01): **151/154 pass, 0 failures**, 3
errors, 0 crashes; 20 of 22 files fully green. FFM extras 37/37. JUnit:
81/81 on all five interpreters, run as jpy's CI runs them, with
`-Djpy.stopIsNoOp=true` (Python is never restarted; restarting is the
application's responsibility, bcdev/jpy#70). The 3 errors are `jpy.diag`
(2) and `jpy.VerboseExceptions` (1), both session 7.
`EmbeddableTestJunit` is not counted: Maven does not run it, and it fails on
the C jpy too (`EmbeddableTest.assertFalse` asserts the opposite).

Full suite after session 5 (2026-10-01): **108/154 pass, 0 failures**, 46
errors, 0 crashes; 13 of 22 files fully green. FFM extras 37/37 (byte_buffer late-access check added). Remaining
errors: the Java-to-Python half, `org.jpy.PyLib`/`PyObject` (session 6),
and `jpy.diag`/`VerboseExceptions` (session 7).

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
5. **Arrays + buffer protocol** — `jpy_array_test.py`
6. **Java-side lifecycle** — `PyObjectTest`, `PyModuleTest`, cleanup/reachability
7. **Diagnostics + exception translation** — `jpy_diag_test.py`, `jpy.VerboseExceptions` in `jpy_exception_test.py`, Java cause chains (`PyProxyTest` already passed in session 6)
8. **Full sweep** — both suites on 3.12, then 3.13/3.14, then 3.13t/3.14t
9. **Linux and Windows** — the loader (jvm.dll via ctypes) and libpython discovery are untested off macOS; needs Linux/Windows boxes or CI

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
- **Pinning risk: retired.** Session 5 showed the C jpy copies rather than
  pins, so the FFM jpy needs no pinning and no JNI beyond the bootstrap. A
  pinning feature was considered and rejected (see Architecture); the
  FFM-to-JNI technique stays documented in the research doc for the record.

## Decision requested

Green-light session 1 (type system against `jpy_gettype_test.py`), with the
session-1 design note as the first review gate.
