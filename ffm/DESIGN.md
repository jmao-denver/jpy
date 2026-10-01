# FFM jpy: design note (session 1 review gate)

Status: session 1 complete. `jpy_gettype_test.py` passes unmodified (8/8) on
CPython 3.12, 3.13, 3.14, 3.13t and 3.14t with one build, JDK 25, macOS arm64.
This note lists the decisions later sessions build on. Veto any of them
before session 2 starts; after that they get expensive to change.

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
and no `jdl` module to `PyLibInitializer`.

libpython is found with `dladdr` on a Python API symbol, not `sysconfig`.
uv's Python builds report a baked-in `/install` prefix there.

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

## 8. Threading

A Java lock guards type creation and resolution. If the lock is busy, the
waiting thread releases the GIL while it waits (`Gil.PyAwareLock`), so the
lock owner, which may need the GIL to finish, can always progress. The C jpy
uses a `PyMutex` on free-threaded builds and relies on the GIL otherwise.

## 9. Open items owned by later sessions

- `type_callbacks` and `jpy.JMethod` objects (sessions 3 and 4)
- `cast`, `convert`, `array`, `byte_buffer`, `diag`, `VerboseExceptions`
- buffer protocol, primitive-array parameters from Python buffers, and
  heap-array pinning through FFM calls into JNI's function table, with no
  compiled shim (session 5; proven in `ffm-prototype/src/ffm/M10.java`)
- Java-to-Python direction: `org.jpy.PyLib`, `PyObject`, `PyModule`,
  `PyObject` arguments and return values (session 6)
- proxies, verbose exceptions and cause chains (session 7)

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

## How to reproduce

```
ffm/build.sh                         # compile FFM Java -> ffm/build/classes, FFM fixtures -> ffm/build/fixtures
ffm/build-fixtures.sh                # jpy's Java test fixtures -> target/test-classes
ffm/test.sh jpy_gettype_test.py      # one jpy test file, unmodified, on 3.12
ffm/matrix.sh jpy_gettype_test.py    # same on 3.12, 3.13, 3.14, 3.13t, 3.14t
<numpy-python> ffm/suite.py          # jpy's 22 test files, then the FFM extras in ffm/tests
```

`ffm/tests/` holds unittest files for behavior only the FFM jpy has (the C
jpy fails them by design, so they stay out of `src/test/python` while both
implementations exist): `ffm_bridge_test.py` (identity, calls, overloads,
fields, arrays, errors) and `ffm_static_fields_test.py` (§11).
