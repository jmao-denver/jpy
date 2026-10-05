# FFM jpy: design note (session 1 review gate)

Status: sessions 1-8 complete. jpy's Python suite (154/154) and JUnit tests
(81/81) pass unmodified on CPython 3.12, 3.13, 3.14, 3.13t and 3.14t, with
one build, JDK 25, macOS arm64 (`ffm/sweep.sh`). Linux and Windows are
session 9. This note was approved on 2026-10-01; sections 11-15 were added
since.

## 1. Object model: the C jpy's classes, plus a real metaclass

Every Java class becomes a Python heap type built with
`PyType_FromMetaclass`. Its Python base is the Python type of its Java
superclass. Interfaces get `java.lang.Object` as base (bcdev/jpy#57).
`java.lang.Object` and the primitive types derive from `jpy.JType`, a plain
base class, so every Java class derives from `jpy.JType` exactly as in the
C jpy. Deephaven relies on this: 8 `isinstance(obj, jpy.JType)` checks mean
"is this a Java object", and 44 files use `jpy.JType` as the type hint.

**Decided 2026-10-01:** Java types are instances of a new metaclass,
`jpy.JTypeMeta`, a real heap subclass of `type` built with
`PyType_FromSpecWithBases(spec, &PyType_Type)`. Its `__getattribute__` and
`__setattr__` are Java upcalls that delegate to `type`'s own slots for
everything they do not handle.

| expression | C jpy | FFM jpy |
|---|---|---|
| `type(java_obj)` | the Java class | same |
| `issubclass(String, jpy.JType)` | True | same |
| `isinstance(java_obj, jpy.JType)` | True | same |
| `type(String)` | `type` | `jpy.JTypeMeta` |
| `type(jpy.JTypeMeta)` | n/a | `type` |
| `str(String)` | `<class 'java.lang.String'>` | same |

The only visible change is `type(T)`. It is the same pattern as
`abc.ABCMeta` or `enum.EnumMeta`. Nothing in jpy's tests or in
deephaven-core depends on `type(T) is type`.

Why the C jpy has no working metaclass: its `JType` metatype derives from
`object`, not `type`. The code built Java types as hand-filled static type
structs and tried to switch their type to `JType` afterwards. Its own
comment in `JType_InitSlots` says that crashed the interpreter, so the
metatype's hooks were never called. A real `type` subclass through the 3.12
API does not have that problem: `ffm-prototype/src/ffm/M11.java` proves it
(10/10 on 3.12, passes on 3.13t), and the full jpy suite runs unchanged.

The metaclass enables the improvements in §11.

## 2. All bridge state lives in Java, keyed by Python object address

The C jpy stores state in C struct fields (`JPy_JObj.objectRef`,
`JPy_JType.classRef`, ...). The FFM jpy never reads or writes a CPython
struct. Instead, Java maps key everything by the Python object's address:

| Python object | Java state |
|---|---|
| instance of a Java type | the wrapped Java object (`JObjects`) |
| Java type object | `JavaType` (`JTypes`) |
| `jpy.JOverloadedMethod` | `OverloadSet` |
| `jpy.JField` | `JFieldInfo` |

Entries are added when the Python object is created or initialized and
removed in its `tp_dealloc` upcall, so an address is never reused while its
entry exists. Cost: one hash lookup per access. Benefit: no struct layout
knowledge, so one build serves every 3.12+ interpreter, free-threaded
included. Session 1 confirms this on five interpreters.

Consequence: "is this a Java object" means "has a table entry". A Python
subclass of a Java type whose `__init__` calls the Java constructor counts as
a Java object. In the C jpy it does not. This is a small deviation towards
more permissive behavior.

## 3. Type creation and resolution follow the C jpy's timing

- `get_type(name)` resolves the type (constructors, methods, fields) and its
  superclasses. Super and component types are created unresolved, as in
  current C jpy (`master`, 2.2.0-dev).
- Resolving a type creates, unresolved, the Python types of every parameter,
  return and field type. This is eager, like the C jpy, and it is
  observable: `jpy_typeres_test.py` checks `jpy.types` for them.
- Unresolved types resolve on the first attribute access on an instance, as
  in the C jpy, and also on the first class-level access to a Java member
  (improvement 2 in §11). Python's own introspection names (`T.__dict__`,
  `T.__name__`, `T.__mro__`, any `__dunder__`) do not resolve, which keeps
  the laziness `jpy_typeres_test.py` checks through `T.__dict__`.
- Methods come from `Class.getMethods()` (public, inherited, no bridges).
  Fields come from `getDeclaredFields()` (public only): static finals become
  plain values in the type dict and instance fields become `jpy.JField`, as
  in the C jpy. Static non-final fields, which the C jpy skips, are served
  live (improvement 1 in §11).
- The bridge writes its own dict entries (methods, constants, fields,
  `jclass`) through `type`'s setattr directly, bypassing `jpy.JTypeMeta`'s
  hook, so building or resolving a type never triggers resolution or
  static-field writes by accident.

## 4. Calls: jpy's scoring, reflection, GIL released

- Overload selection is a line-by-line port of `JMethod_MatchPyArgs`,
  `JOverloadedMethod_FindMethod{0}` and the `JType_MatchPyArgAs*` scorers,
  including var-args and the walk into superclass overloads. Session 3 will
  verify it against `jpy_overload_test.py`. It is ported now, not stubbed.
- Invocation uses `java.lang.reflect.Method.invoke` and
  `Constructor.newInstance`, with the GIL released around the call (the C
  jpy's `Py_BEGIN_ALLOW_THREADS`).
- Access control: JNI ignores Java access rules; reflection does not.
  `Access.usable` maps a public method declared in a non-public class to the
  same method on an accessible supertype, else tries `setAccessible`.
  **Risk:** a JDK-internal class with no public supertype declaring the
  method will fail where JNI worked. Fix if it shows up: `--add-opens`, or
  `MethodHandles.privateLookupIn`.
- Performance work (MethodHandle caching, METH_FASTCALL) waits until the
  suites pass.

## 5. The Python side is pure Python

`ffm/python/jpy.py` defines `create_jvm`, `destroy_jvm`, `has_jvm`,
`types`, `type_callbacks`, `type_translations` and `JException`.
`create_jvm` starts the JVM with ctypes and `JNI_CreateJavaVM`, then calls
`org.jpy.ffm.Bootstrap.install(id(module))`, which adds the Java-implemented
functions and types to the same module object. `ffm/python/jpyutil.py` is
the current `jpyutil.py` with one change: it passes the exact libpython path
and an empty `jdl` path to `PyLibInitializer` (there is no `jdl` module).

`create_jvm` puts the jar bundled in the wheel (`jpy-ffm.jar`) first on
the class path, so its classes win over a C jpy jar the caller may still
list. One exception (decided 2026-10-05): when the first jpy jar on the
caller's class path is already an FFM jpy jar, for example
`org.jpyconsortium:jpy` 3.x from Maven in an application such as Deephaven,
the caller's class path is used as is. The JVM then sees one copy of the
`org.jpy` classes. "jpy jar" means an entry whose name contains `jpy` and
that holds `org/jpy/PyLib.class`, so `deephaven-jpy-ext` is skipped. An FFM
one also holds `org/jpy/ffm/Bootstrap.class`. `JPY_FFM_CLASSPATH` overrides
both and is always put first. Covered by `ffm/tests/ffm_classpath_test.py`.

Splitting `org.jpy.ffm` into its own jar would not avoid the duplicate.
`org.jpy.PyLib` calls the bridge and the bridge calls back into `org.jpy`,
so both halves are needed in both directions, and both the wheel and Maven
must carry the bridge.

`jpy.py` imports ctypes only inside
the functions that need it, which all run Python-first. Java-first, Java
may stop and restart Python, and CPython aborts when `_ctypes` is imported
again after `Py_Finalize`.

libpython is found with `dladdr` on a Python API symbol, not `sysconfig`.
uv's Python builds report a baked-in `/install` prefix there.

Python-first works on any Python, including one with libpython built into
its executable (decided 2026-10-05, reversing the 2026-10-02 call). On Linux
that includes Ubuntu's and Debian's own `python3`, uv's Linux builds, and the
Python in Deephaven's server image. There `dladdr` finds the executable
itself, so `jpy.py` passes `-Djpy.pythonLib=process`. `CPython` then finds the
Python API with `dlsym(RTLD_DEFAULT)` (`Dl.processLookup`), which searches
everything the process has loaded, the executable included. This is how C
jpy's extension module finds Python's functions too. Opening a libpython file
found on disk would be wrong: uv ships one next to its executable, and
binding to it would mean a second, uninitialized copy of Python in the
process. `JPY_PYTHON_LIB=process` forces the lookup on any POSIX Python;
`ffm/tests/ffm_process_lookup_test.py` uses it. Windows Pythons always run
from a `python3XX.dll`.

Java-first still needs a libpython file, as with C jpy: there is no Python in
the process until Java loads one. `jpy.pythonLib=process` there fails with a
clear message. CI runs the static case on Ubuntu's `/usr/bin/python3`, x64
and arm64, Python-first only (`.github/workflows/ffm.yml`, job
`static-python`; locally `ffm/docker/Dockerfile.ubuntu`).

## 6. Two CPython details worth knowing

- `PyType_FromSpec` warns (and fails under `-W error`) for spec names without
  a dot, such as `int` or `[I`. A placeholder `__module__` member suppresses
  the warning. It is replaced by `'builtins'` right after creation. The spec
  name must stay dot-free, because type repr prints `tp_name` when
  `__module__` is `'builtins'`.
- `tp_hash` returning -1 means "error" to CPython, so a Java `hashCode()` of
  -1 becomes -2 (CPython's own convention). The C jpy returns -1 and
  triggers a `SystemError`.

## 7. Known deviations from the C jpy (all deliberate)

Small behavior differences that fix C jpy bugs. Feature-level gains are
listed separately in §11.

| C jpy | FFM jpy | why |
|---|---|---|
| int argument overflow leaves an `OverflowError` pending | same truncated value, error cleared | a pending error surfaces later as `SystemError` |
| `hashCode() == -1` breaks `hash()` | maps to -2 | see above |
| failing `type_translations` callback returns None with an error pending | error propagates | same reason |
| `PyLib.repr` failure returns null with the Python error still pending | returns null, error cleared | same reason |
| `PyLib.pyDictContains` never releases the converted key | released | reference leak |
| `PyLib.stopPython` from Python-first calls `PyEval_RestoreThread(NULL)` | does nothing | crash |
| `getIntValue`/`getLongValue` go through C `long`, 32-bit on Windows | 64-bit everywhere | Windows truncation; same on macOS and Linux |
| Python to Java works without the jpy jar; `org.jpy.PyObject` is optional | the jar is always needed | the bridge itself is Java; the wheel bundles the jar and `create_jvm` adds it (§5) |

## 8. Threading

A Java lock guards type creation and resolution. If the lock is busy, the
waiting thread releases the GIL while it waits (`Gil.PyAwareLock`), so the
lock owner, which may need the GIL to finish, can always progress. The C jpy
uses a `PyMutex` on free-threaded builds and relies on the GIL otherwise.

## 9. Open items owned by later sessions

Done in sessions 2-4: `type_callbacks`, `jpy.JMethod` (with the
`set_param_*` annotations), `cast`, `convert`, `array`, Python buffers as
primitive-array arguments (copied in, copied back when mutable), and
return-parameter identity. Done in session 5: the buffer protocol on Java
primitive arrays and `byte_buffer` (§12).

Done in session 6: the Java-to-Python direction (§13), including proxies,
which the plan had put in session 7.

Done in session 7: `jpy.diag`, `jpy.VerboseExceptions` and Java cause
chains (§14).

Still open: the C jpy's diagnostic trace messages (§14).

## 10. Found during session 1

The worktree started from `origin/master`, a stale 2022 fork (jpy 0.14). It
was rebased onto `master` (2.2.0-dev). The C code was re-checked against
`master`. Relevant changes were already matched or have now been applied:
unresolved super types, `jclassname`, and the `type_translations` changes.

## 11. Improvements over the C jpy

Changes that make the FFM jpy strictly more capable. Each one is additive:
code that works on the C jpy keeps working the same way.

1. **Public static non-final fields are visible and writable from Python,
   live** (decided 2026-10-01). The C jpy skips them entirely, because its
   metatype hook never worked. Reads and writes go to the Java field itself,
   through the class (`T.counter`, `T.counter = 5`) and through instances
   (`obj.counter`), and through subclasses to the declaring class. Rules
   that keep existing behavior intact:
   - A method, constant or instance field with the same name keeps the
     name, nearest class first, so nothing that resolves today changes.
   - `static final` fields stay plain values in the type dict, as in the
     C jpy.
   - Private and protected fields stay invisible.
   - Writes use the strict typed conversion: a wrong Python type raises
     `ValueError` and leaves the field unchanged. Instance-field writes keep
     the C jpy's lenient conversion for parity.

   Covered by `ffm/tests/ffm_static_fields_test.py` (13 tests, run by
   `ffm/suite.py`; also passing on 3.14t).
2. **Class-level access to a Java member resolves an unresolved type.** In
   the C jpy, `jpy.get_type(name, resolve=False).someStaticMethod` raises
   `AttributeError` until some instance attribute was touched. Dunder
   introspection still does not resolve (see §3).
3. **A Java holder that outlives a `jpy.byte_buffer` wrapper fails loudly**
   (decided 2026-10-01). Both jpys document the rule: the Python object must
   outlive the Java ByteBuffer. In the C jpy, breaking it reads freed or
   reused memory. In the FFM jpy, the buffer's memory lives in a shared
   `Arena` that is closed before `PyBuffer_Release`, so a late Java access
   throws `IllegalStateException`. If a Java thread still holds the segment
   in a native call, the close fails and the export is leaked, never freed
   under the caller. The rule and its help text are unchanged.

   Data races stay the application's job. "Read-only" limits the Java side
   only, so Python may still write the memory while Java reads it. That gives
   wrong values, not a crash, the same as any shared memory.

## 12. Buffer protocol on Java arrays: copy semantics, as measured

Measured on the C jpy before porting (session 5), because reading
`jpy_jarray.c` was not enough: it contains a disabled
`GetPrimitiveArrayCritical` (pinning) path and an active
`Get<Type>ArrayElements` path, which copies on HotSpot.

| step | C jpy | FFM jpy |
|---|---|---|
| first export of a Java array | copies it to native memory | same (`ArrayExports`) |
| later exports | share that copy | same |
| Python writes through a writable view | stay in the copy | same |
| Java writes after the first export | not visible in views | same |
| a view is released | nothing | same |
| wrapper deallocated | copy written back if any export was writable, then freed | same |
| `memoryview()`, `np.frombuffer()` | read-only views | same |
| formats | `B` (boolean), `H` (char), `b`, `h`, `i`, `q`, `f`, `d` | same |

`ffm/tests/ffm_buffer_test.py` encodes this table, and those tests pass on
both the C jpy and the FFM jpy. Implementation: native memory from a shared `Arena`
per exported array, `MemorySegment.copy` both ways, slots `bf_getbuffer` and
`bf_releasebuffer` on primitive-array types. No pinning, no JNI.

Known C jpy bug kept for parity: the write-back at dealloc overwrites any
change Java made to the array after the first export. Fixing it (for
example, writing back when the last writable view is released) is a
candidate improvement, not yet decided.

Decided 2026-10-01: no zero-copy pinning feature, opt-in or scoped. A
scoped pin cannot bound the lifetime of objects Python derives from the
view, so it either corrupts memory when a derived numpy array outlives the
scope, or degrades into an unbounded pin. See the plan doc.

`jpy.byte_buffer(obj)` wraps a Python object's contiguous buffer as a
read-only direct `java.nio.ByteBuffer` through `MemorySegment.asByteBuffer`
(the C jpy uses JNI's `NewDirectByteBuffer`). The `Py_buffer` is released
when the wrapper is deallocated, as in the C jpy. Access after that throws
(§11 item 3).

## 13. Java to Python: org.jpy.PyLib on FFM (session 6)

jpy's Java API is unchanged: `PyLib`, `PyObject`, `PyModule`,
`PyInputMode`, `PyDictWrapper`, `PyListWrapper`, `PyProxyHandler` and the
JSR-223 engine compile from `src/main/java` as they are. Two parts are
marked `@Deprecated(since = "3.0", forRemoval = true)` (decided 2026-10-05),
because nothing uses them: the parameter annotations in `org.jpy.annotations`,
which no jpy ever reads, and the JSR-223 engine in `org.jpy.jsr223`, which jpy
itself calls not functional and does not register as a service. Only jpy's own
tests use them, so they stay in 3.0 and go in a later release. `ffm/build.sh`
replaces two classes with copies under `ffm/java/org/jpy`:

- `PyLib`: every former `native` method calls `org.jpy.ffm.PyLibImpl`, a
  function-by-function port of `org_jpy_PyLib.c`. Same names, same
  signatures, same pointer-as-`long` convention, same exception messages
  ("Error in Python interpreter:" plus `traceback.format_exception`), and
  `KeyError`/`StopIteration` for those Python errors.
- `DL`: same API, through FFM `dlopen` instead of the `jdl` library.

The bridge needs a few package-private members (`new PyObject(long,
boolean)`, the `KeyError` and `StopIteration` constructors,
`PyDictWrapper.getPointer`). `PyLib`'s static initializer hands them to
`org.jpy.ffm.PyObjects` as hooks, so `org.jpy`'s public API does not grow.

Each entry point takes the GIL with `PyGILState_Ensure` and refuses with a
`RuntimeException` while the interpreter is finalizing, as the C jpy's
`JPy_BEGIN_GIL_STATE` does. A pending Python error is read and translated
while the GIL is still held.

Python-first and Java-first both work:

| | Python-first (`jpyutil.init_jvm`) | Java-first (`PyLib.startPython`) |
|---|---|---|
| libpython | already loaded; `jpy.py` passes `-Djpy.pythonLib` (a path, or `process` when built into the executable) | `jpy.pythonLib` property, `dlopen`ed `RTLD_GLOBAL` |
| `jpy` module | installed by `create_jvm` | imported by `PyLib`, then `Bootstrap.installEmbedded` sets `jpy._embedded` |
| `jpy.has_jvm()` | true | true, and `create_jvm` does nothing |
| `PyLib.stopPython` | does nothing (Java does not own Python) | `Bootstrap.uninstall`, then `Py_Finalize` |

`jpy.jpyLib` names `jpy.py`. Its directory goes on `sys.path`, the way the
C jpy uses the directory of its extension module. Java-first, the
interpreter finds its standard library through `PYTHONHOME`
(`ffm/junit.sh` sets it from the chosen Python's `sys.base_prefix`).

Ported details worth knowing:
- `executeScript` compiles the file's bytes with its name
  (`Py_CompileStringExFlags`, then `PyEval_EvalCode`). The C jpy hands a
  `FILE*` to `PyRun_File`. The parse is the same, coding cookie included,
  and so are tracebacks, with no C runtime `FILE*` crossing the boundary.
- Embedded mode replaces `sys.stdout` and `sys.stderr` with a module
  `jpy_stdout` whose `write` prints to the process's stdout, as the C jpy
  does. The module is written in Python here.
- `getCurrentGlobals`/`getCurrentLocals` use `PyEval_GetFrameGlobals` and
  `PyEval_GetFrameLocals` on 3.13+, like the C jpy.
- A Java `OutOfMemoryError` while allocating a Java array for Python
  (`jpy.array`, sequence arguments) raises Python's `MemoryError`, as JNI's
  failing `New<Type>Array` does in the C jpy. `jpy_cleanup_thread_test.py`
  checks it.

**Restarting Python is the application's responsibility, as in the C jpy.**
`PyLib.stopPython` documents that every PyObject must be released first and
that jpy cannot enforce it, and that restarting can crash (bcdev/jpy#70).
jpy's CI therefore runs the JUnit tests with `-Djpy.stopIsNoOp=true`
(`setup.py`, `test_maven`), so Python is never stopped and `LifeCycleTest`
skips. `ffm/junit.sh` does the same. Without the flag, jpy's own test rule
`TestStatePrinter` runs `System.gc()` after `stopPython`, the next
`startPython` starts a new cleanup thread, and that thread decRefs objects
from the old interpreter. That aborts the FFM jpy, and `TestStatePrinter`'s
own comment says it makes the C jpy crash too. Decided 2026-10-01: no
mechanism that drops pointers at stop to make restarts survivable. It would
trade the crash for a leak and still not cover threads that are mid-call,
so it would suggest restarts are safe when they are not.

`EmbeddableTestJunit` is not run: Maven does not run it either (its name
does not match surefire's patterns), and it fails on the C jpy too, because
`EmbeddableTest.assertFalse` throws when its argument is false.

## 14. Java exceptions in Python, jpy.diag (session 7)

A Java exception becomes a Python `RuntimeError`, as in the C jpy's
`JPy_HandleJavaException`. Its message is `Throwable.toString()`. With
`jpy.VerboseExceptions.enabled = True`, the message is the C jpy's verbose
format: `toString()`, one `\tat <frame>` line per frame, then each cause
as `caused by <toString()>` with its frames, where frames shared with the
enclosing exception become `\t... N more`.

Stack traces need one adjustment. A JNI call starts a fresh Java stack, so
in the C jpy the trace ends at the called method. The FFM jpy calls through
its own code and reflection, so those frames sit at the bottom of every
trace. `JavaErrors.callerFrames` cuts each trace in the chain at the first
`org.jpy.ffm` frame from the top, along with the reflection frames just
above it. `ffm/tests/ffm_exceptions_test.py` checks the result line by line
against the exact messages written in jpy's `jpy_exception_test.py`, which
itself only checks for "java.lang.NullPointerException".

`jpy.diag` and `jpy.VerboseExceptions` are small classes in `jpy.py` with
the C jpy's names, constants and checks: `flags` takes an int (else
`ValueError`), `enabled` takes a bool (else `ValueError`, with the C jpy's
message, which says 'flags'), and the `F_*` constants are read-only. The
values live in Java: the diag flags are the same variable as
`org.jpy.PyLib.Diag.getFlags/setFlags`, as `JPy_DiagFlags` is in the C jpy.
Set before the JVM exists, they are handed to Java by `create_jvm`.

Gap: any nonzero diag flag prints a Java exception's stack trace to stderr
when it crosses into Python, as the C jpy's `ExceptionDescribe` does. The C
jpy also prints `printf` trace lines for type resolution, method matching,
execution, memory, the JVM and errors. Those are not ported. Their text is
debugging output, not API, and no test reads it.

## 15. Introspection parity with the C jpy

`ffm/tests/ffm_introspection_test.py` compares about 800 introspection
facts (type names, flags, dicts, `dir()`, docs, method and overload objects,
their attributes and error messages, module names) with the C jpy's output,
recorded per interpreter by `ffm/tests/make_c_reference.sh` into
`ffm/tests/data`. The reference was recorded with JDK 25, since `dir()` of a
Java class lists that JDK's methods, so other JDKs skip the test.

Everything matches except these differences, which come from building
types with `PyType_FromSpec` (heap types) and from the metaclass:

| fact | C jpy | FFM jpy |
|---|---|---|
| `type(T)` | `type` | `jpy.JTypeMeta` (§1) |
| heap-type flag | off | on |
| `T.__basicsize__` | 24 (56 for primitive arrays) | 16: no per-object C struct |
| `'__module__'` in `T.__dict__` and `dir()` | absent | present; `T.__module__` is the same |
| `jpy.JTypeMeta` in the module | absent | present |

Fixed to match (2026-10-01): Java types and jpy's own types are immutable
(`T.x = 1` raises `TypeError`), so the bridge writes type dicts with
`PyType_GetDict` and `PyType_Modified`, as the C jpy writes `tp_dict`.
Primitive types have no `jclass`/`jclassname`. `jpy.JField` has the C repr,
`str`, and `name`/`is_static`/`is_final`. The C docstrings of `jpy.JType`,
`JOverloadedMethod`, `JMethod` and `JField`. The module no longer shows
`Diag`, `os` or `sys`.

## How to reproduce

```
ffm/build.sh                         # FFM bridge + jpy's src/main/java -> ffm/build/classes; FFM fixtures -> ffm/build/fixtures
ffm/build-fixtures.sh                # jpy's src/test/java and test resources -> target/test-classes
ffm/test.sh jpy_gettype_test.py      # one jpy test file, unmodified, on 3.12
ffm/matrix.sh jpy_gettype_test.py    # same on 3.12, 3.13, 3.14, 3.13t, 3.14t
<numpy-python> ffm/suite.py          # jpy's 22 test files, then the FFM extras in ffm/tests
ffm/junit.sh                         # jpy's JUnit tests (Java-first), as jpy's CI runs them
ffm/matrix.sh junit                  # same on all five interpreters
ffm/sweep.sh                         # everything above on all five interpreters, each in a venv with numpy
```

`ffm/tests/` holds unittest files for behavior only the FFM jpy has (the C
jpy fails them by design, so they stay out of `src/test/python` while both
implementations exist): `ffm_bridge_test.py` (identity, calls, overloads,
fields, arrays, errors), `ffm_static_fields_test.py` (§11),
`ffm_buffer_test.py` (§12; all but the late-access test also pass on the C
jpy), `ffm_exceptions_test.py` (§14), `ffm_introspection_test.py` (§15)
and `ffm_threads_test.py` (16 threads at once creating Java types, calling,
converting and calling back into Python; aimed at 3.13t and 3.14t).
