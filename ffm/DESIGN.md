# FFM jpy: design note (session 1 review gate)

Status: session 1 complete. `jpy_gettype_test.py` passes unmodified (8/8) on
CPython 3.12, 3.13, 3.14, 3.13t and 3.14t with one build, JDK 25, macOS arm64.
This note lists the decisions later sessions build on. Veto any of them
before session 2 starts; after that they get expensive to change.

## 1. Object model: mirror the C jpy exactly

Every Java class becomes a Python heap type built with
`PyType_FromSpecWithBases`. Its Python base is the Python type of its Java
superclass. Interfaces get `java.lang.Object` as base (bcdev/jpy#57).
`java.lang.Object` and the primitive types derive from `jpy.JType`, a plain
base class. Finished Java types are instances of `type`, as in the C jpy.

Why: this is the C jpy's object model, observable to users through `type()`,
`isinstance`, `issubclass`, MRO and `str(T)`. Copying it is the cheapest way
to parity.

Alternative rejected: a real metaclass (`jpy.JType` as the type of Java
types). Cleaner, and it would allow lazy resolution on class attribute
access, but `type(T)` would change.

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
- Unresolved types resolve on the first attribute access on an instance.
  Class-level access on an unresolved type does not resolve it. This is a C
  jpy quirk (its `JType_getattro` is never called). Kept.
- Methods come from `Class.getMethods()` (public, inherited, no bridges).
  Fields come from `getDeclaredFields()` (public only): static finals become
  plain values in the type dict, instance fields become `jpy.JField`, and
  static non-final fields are skipped. All as in the C jpy.

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
- buffer protocol, primitive-array parameters from Python buffers, and the
  heap-array pinning shim (session 5)
- Java-to-Python direction: `org.jpy.PyLib`, `PyObject`, `PyModule`,
  `PyObject` arguments and return values (session 6)
- proxies, verbose exceptions and cause chains (session 7)

## 10. Found during session 1

The worktree started from `origin/master`, a stale 2022 fork (jpy 0.14). It
was rebased onto `master` (2.2.0-dev). The C code was re-checked against
`master`. Relevant changes were already matched or have now been applied:
unresolved super types, `jclassname`, and the `type_translations` changes.

## How to reproduce

```
ffm/build.sh                         # compile FFM Java -> ffm/build/classes
ffm/build-fixtures.sh                # jpy's Java test fixtures -> target/test-classes
ffm/test.sh jpy_gettype_test.py      # one jpy test file, unmodified, on 3.12
ffm/matrix.sh jpy_gettype_test.py    # same on 3.12, 3.13, 3.14, 3.13t, 3.14t
ffm/py.sh ffm/smoke_session1.py      # 19 extra checks: calls, fields, errors, identity
```
