# FFM prototype: jpy without JNI

Proof-of-concept for rewriting jpy on the Java FFM API (java.lang.foreign).
Zero C code: CPython 3.12 is driven entirely from Java. See
`../FFM_REWRITE_RESEARCH.md` for the full analysis; this directory is the
experimental evidence.

## Requirements

- JDK 22+ (built and run with Temurin 25.0.3 via sdkman)
- uv-managed CPython 3.12 with `libpython3.12.dylib`
  (path is baked into `Py.java`; override with `-Dlibpython=...`)
- `PYTHONHOME` must point at the Python prefix (run.sh sets it; the
  uv/python-build-standalone dylib has a baked-in `/install` prefix and dies
  with "No module named 'encodings'" without it)

## Run

```bash
./run.sh M1   # start CPython from pure Java, run code, finalize
./run.sh M2   # 5 representative PyLib natives + error translation
./run.sh M3   # Python->Java upcalls: builtin fn, type from spec, 4 Python threads
./run.sh M5   # benchmarks
```

## Results (Apple M-series, JDK 25, CPython 3.12.12)

All milestones pass. Shutdown: 10/10 clean full-lifecycle runs, no crash dumps.
40,000 upcalls from 4 Python `threading` threads with JVM auto-attach: all
delivered, sums correct on both sides.

| measurement | ns/op |
|---|---|
| single FFM downcall (incRef+decRef pair / 2) | ~4 |
| Java -> Python full call `py_add(1,2)` (tuple + call + decref) | 71–93 |
| Python -> Java upcall `java_add(1)` (METH_VARARGS) | 106–119 |
| Python -> Java upcall (METH_FASTCALL, direct segment arg reads) | ~104 |
| Python -> Python baseline `py_inc(1)` | ~36 |
| **jpy 2.1 (JNI) Python -> Java `Integer.sum(1,2)`** | **~409** |

Caveat: the jpy number includes overload resolution and conversion machinery
the prototype doesn't have; the FFM numbers are the floor, not a finished
bridge. The gap (4x) is the budget available for overload matching in Java.

## Files

- `src/ffm/Py.java` — the binding layer: downcalls, GIL guard
  (`try (Gil g = Gil.lock())`), error translation
  (`PyErr_GetRaisedException` -> Java `PyException`)
- `src/ffm/M1.java` … `M5.java` — the milestones
- `run.sh` — compile + run with `--enable-native-access=ALL-UNNAMED`

## Python-first bootstrap (pyloader/)

The other start mode, replacing the compiled extension module with pure Python:

```bash
cd pyloader && python3.12 test_bridge.py
```

`jbridge_loader.py` (~100 lines, pure Python) ctypes-loads libjvm, calls
`JNI_CreateJavaVM` — the one piece of JNI the design keeps — and invokes
`ffm.Bootstrap.install()` through the JNI invocation API. `install()` uses FFM
to register a `jbridge` module into the already-running interpreter; after
that, `import jbridge` works and its functions run in Java. Verified: calls,
error translation, docstrings.

Two preconditions verified here: the uv python executable links
`libpython3.12.dylib` dynamically (so Java's `libraryLookup` of the same path
binds to the same interpreter state — a statically linked Python would need
dlsym(RTLD_DEFAULT) instead), and the JNIEnv vtable indices come from the
JDK's own jni.h (`tools/jni_indices.py`).

## What this proves / doesn't

Proves: embedding, downcalls, upcall stubs as PyCFunctions and type slots
(`PyType_FromSpec`), unknown-thread auto-attach, GIL discipline from Java,
exception translation, clean teardown, competitive per-call costs, and both
bootstrap directions (Java-first and Python-first) with zero compiled
Python-version-specific code.

Doesn't touch: zero-copy Java-array buffers (no FFM equivalent; needs a JNI
shim or copies), free-threaded builds, overload matching at scale.
