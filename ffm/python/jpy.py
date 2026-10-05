"""
jpy on the Java FFM API (CPython 3.12+, JDK 25+).

This module is pure Python. create_jvm() starts a JVM inside this process
through the JNI invocation API (reached with ctypes; the one piece of JNI the
FFM design keeps) and calls org.jpy.ffm.Bootstrap.install(), which fills this
module in from Java: get_type, JType, JOverloadedMethod, JField, ...
"""

# ctypes is imported only inside the functions that need it, which all run Python-first. Java-first,
# org.jpy.PyLib may stop and restart the interpreter, and re-importing _ctypes after Py_Finalize
# aborts the process (CPython 3.12).
import os as _os
import sys as _sys

# Filled by Java. Same names and roles as in the C jpy module.
types = {}
type_callbacks = {}
type_translations = {}


class JException(Exception):
    pass


# Until Java installs the bridge, jpy.diag and jpy.VerboseExceptions keep their values here;
# create_jvm hands them to Java, which owns them from then on (org.jpy.PyLib.Diag shares the flags).
_pending_diag_flags = 0
_pending_verbose_exceptions = False


class _Diag:
    """Controls output of diagnostic information for debugging"""
    __slots__ = ()

    F_OFF = 0x00   # Don't print any diagnostic messages
    F_TYPE = 0x01  # Type resolution: print diagnostic messages while generating Python classes from Java classes
    F_METH = 0x02  # Method resolution: print diagnostic messages while resolving Java overloaded methods
    F_EXEC = 0x04  # Execution: print diagnostic messages when Java code is executed
    F_MEM = 0x08   # Memory: print diagnostic messages when wrapped Java objects are allocated/deallocated
    F_JVM = 0x10   # JVM: print diagnostic information usage of the Java VM Invocation API
    F_ERR = 0x20   # Errors: print diagnostic information when erroneous states are detected
    F_ALL = 0xff   # Print any diagnostic messages

    @property
    def flags(self):
        """Combination of diagnostic flags (F_* constants). If != 0, diagnostic messages are printed out."""
        getter = globals().get('_get_diag_flags')
        return getter() if getter else _pending_diag_flags

    @flags.setter
    def flags(self, value):
        global _pending_diag_flags
        if not isinstance(value, int):
            raise ValueError("value for 'flags' must be an integer number")
        setter = globals().get('_set_diag_flags')
        if setter:
            setter(value)
        else:
            _pending_diag_flags = value


class VerboseExceptions:
    """Controls python exception verbosity"""
    __slots__ = ()

    @property
    def enabled(self):
        getter = globals().get('_get_verbose_exceptions')
        return getter() if getter else _pending_verbose_exceptions

    @enabled.setter
    def enabled(self, value):
        global _pending_verbose_exceptions
        if not isinstance(value, bool):
            # sic: the C jpy's message names 'flags'
            raise ValueError("value for 'flags' must be a boolean")
        setter = globals().get('_set_verbose_exceptions')
        if setter:
            setter(value)
        else:
            _pending_verbose_exceptions = value


# As in the C jpy, the module exposes the instances only; their classes print as jpy.Diag and
# jpy.VerboseExceptions but are not module attributes.
_Diag.__name__ = _Diag.__qualname__ = 'Diag'
diag = _Diag()
VerboseExceptions = VerboseExceptions()
del _Diag


_jvm = None  # JavaVM*, as an int; set only when this module created the JVM
_embedded = False  # set by Java when Java started this interpreter (org.jpy.PyLib.startPython)

_JNI_VERSION_10 = 0x000A0000
_JNI_OK = 0

# JNIEnv function table indices (fixed by the JNI specification).
_ENV_FIND_CLASS = 6
_ENV_EXCEPTION_DESCRIBE = 16
_ENV_EXCEPTION_CLEAR = 17
_ENV_GET_STATIC_METHOD_ID = 113
_ENV_CALL_STATIC_VOID_METHOD_A = 143
_ENV_EXCEPTION_CHECK = 228

# JavaVM (JNIInvokeInterface_) function table index.
_VM_DESTROY_JAVA_VM = 3


def _init_args_types():
    """JavaVMOption and JavaVMInitArgs as ctypes structures."""
    import ctypes

    class _JavaVMOption(ctypes.Structure):
        _fields_ = [("optionString", ctypes.c_char_p), ("extraInfo", ctypes.c_void_p)]

    class _JavaVMInitArgs(ctypes.Structure):
        _fields_ = [("version", ctypes.c_int32),
                    ("nOptions", ctypes.c_int32),
                    ("options", ctypes.POINTER(_JavaVMOption)),
                    ("ignoreUnrecognized", ctypes.c_uint8)]

    return _JavaVMOption, _JavaVMInitArgs


def has_jvm():
    """has_jvm() - Check if the JVM is available."""
    return _jvm is not None or _embedded


def _vtable_fn(obj, index, restype, *argtypes):
    """Slot `index` of a JNI function table; obj is a JNIEnv* or JavaVM* (pointer to table pointer)."""
    import ctypes
    if isinstance(obj, int):
        obj = ctypes.c_void_p(obj)
    table = ctypes.cast(obj, ctypes.POINTER(ctypes.POINTER(ctypes.c_void_p))).contents
    fn = ctypes.cast(table[index], ctypes.CFUNCTYPE(restype, ctypes.c_void_p, *argtypes))
    return lambda *args: fn(obj, *args)


def _libpython_path():
    """The path of the libpython image this interpreter runs from, or 'process' if it is built into the executable."""
    import ctypes
    override = _os.environ.get('JPY_PYTHON_LIB')
    if override:
        return override
    if _sys.platform == 'win32':
        buf = ctypes.create_unicode_buffer(32768)
        ctypes.windll.kernel32.GetModuleFileNameW(ctypes.c_void_p(_sys.dllhandle), buf, len(buf))
        return buf.value

    class _DlInfo(ctypes.Structure):
        _fields_ = [("dli_fname", ctypes.c_char_p), ("dli_fbase", ctypes.c_void_p),
                    ("dli_sname", ctypes.c_char_p), ("dli_saddr", ctypes.c_void_p)]

    info = _DlInfo()
    libc = ctypes.CDLL(None)
    address = ctypes.cast(ctypes.pythonapi.Py_IncRef, ctypes.c_void_p)
    if not libc.dladdr(address, ctypes.byref(info)) or not info.dli_fname:
        raise RuntimeError("jpy: cannot locate the libpython shared library of this interpreter")
    path = _os.path.realpath(info.dli_fname.decode())
    if path == _os.path.realpath(_sys.executable):
        # libpython is built into the executable (Ubuntu's python3, uv's Linux builds). There is
        # no file to open, so Java finds the Python API in the running process. Opening a
        # libpython file found on disk would load a second, separate copy of Python.
        return 'process'
    return path


def _ffm_classpath():
    """Where the jpy FFM Java classes live: an explicit override, a bundled jar, or the dev build."""
    override = _os.environ.get('JPY_FFM_CLASSPATH')
    if override:
        return override
    here = _os.path.dirname(_os.path.abspath(__file__))
    jar = _os.path.join(here, 'jpy-ffm.jar')
    if _os.path.exists(jar):
        return jar
    return _os.path.normpath(_os.path.join(here, '..', 'build', 'classes'))


def _create_java_vm_fn():
    """JNI_CreateJavaVM from an already loaded libjvm (jpyutil preloads it), else from JAVA_HOME."""
    import ctypes
    try:
        # Windows has no handle for "the whole process"; loading jvm.dll by name finds the loaded one.
        loaded = ctypes.CDLL('jvm.dll' if _sys.platform == 'win32' else None)
        return loaded.JNI_CreateJavaVM
    except (AttributeError, OSError):
        pass
    java_home = _os.environ.get('JAVA_HOME')
    if not java_home:
        raise RuntimeError("jpy: no JVM library loaded and JAVA_HOME is not set")
    if _sys.platform == 'win32':
        # jvm.dll loads DLLs that sit in the JDK's bin directory
        _os.add_dll_directory(_os.path.join(java_home, 'bin'))
        lib = _os.path.join(java_home, 'bin', 'server', 'jvm.dll')
    elif _sys.platform == 'darwin':
        lib = _os.path.join(java_home, 'lib', 'server', 'libjvm.dylib')
    else:
        lib = _os.path.join(java_home, 'lib', 'server', 'libjvm.so')
    return ctypes.CDLL(lib, mode=ctypes.RTLD_GLOBAL).JNI_CreateJavaVM


_PYLIB_CLASS = 'org/jpy/PyLib.class'
_BRIDGE_CLASS = 'org/jpy/ffm/Bootstrap.class'


def _jpy_kind(entry):
    """'ffm' for an FFM jpy jar or class directory, 'c' for a C jpy one, None for anything else."""
    if _os.path.isdir(entry):
        def has(name):
            return _os.path.isfile(_os.path.join(entry, *name.split('/')))
    elif _os.path.isfile(entry):
        import zipfile
        try:
            with zipfile.ZipFile(entry) as z:
                names = set(z.namelist())
        except (OSError, zipfile.BadZipFile):
            return None

        def has(name):
            return name in names
    else:
        return None
    if not has(_PYLIB_CLASS):
        return None
    return 'ffm' if has(_BRIDGE_CLASS) else 'c'


def _caller_has_ffm_jar(classpath):
    """
    True when the first jpy jar on the caller's class path is an FFM jpy jar, for example
    org.jpyconsortium:jpy 3.x from Maven on an application's class path. That jar's org.jpy classes
    are the ones the JVM loads, so the bundled jar is not needed. Only entries whose name contains
    'jpy' are opened, so a long class path stays cheap. Entries without org.jpy.PyLib, such as
    Deephaven's deephaven-jpy-ext jar, are skipped.
    """
    for entry in classpath.split(_os.pathsep):
        if 'jpy' in _os.path.basename(entry.rstrip('/\\')).lower():
            kind = _jpy_kind(entry)
            if kind is not None:
                return kind == 'ffm'
    return False


def _with_ffm_options(options):
    override = _os.environ.get('JPY_FFM_CLASSPATH')
    result = []
    has_classpath = False
    for option in options:
        if option.startswith('-Djava.class.path='):
            callers = option[len('-Djava.class.path='):]
            if override or not _caller_has_ffm_jar(callers):
                # First, so the FFM org.jpy classes win over a C jpy jar the caller may still list.
                option = '-Djava.class.path=' + _ffm_classpath() + _os.pathsep + callers
            has_classpath = True
        result.append(option)
    if not has_classpath:
        result.append('-Djava.class.path=' + _ffm_classpath())
    result.append('-Djpy.pythonLib=' + _libpython_path())
    result.append('--enable-native-access=ALL-UNNAMED')
    return result


def create_jvm(options):
    """create_jvm(options) - Create the Java VM from the given list of options."""
    global _jvm
    if has_jvm():
        return None
    if isinstance(options, (str, bytes)) or not hasattr(options, '__len__'):
        raise ValueError("create_jvm: argument 1 (options) must be a sequence of Java VM option strings")
    all_options = _with_ffm_options([str(o) for o in options])

    import ctypes
    _JavaVMOption, _JavaVMInitArgs = _init_args_types()
    c_options = (_JavaVMOption * len(all_options))()
    for i, option in enumerate(all_options):
        c_options[i].optionString = option.encode('utf-8')
    init_args = _JavaVMInitArgs(_JNI_VERSION_10, len(all_options), c_options, 0)

    create = _create_java_vm_fn()
    create.restype = ctypes.c_int32
    create.argtypes = [ctypes.POINTER(ctypes.c_void_p), ctypes.POINTER(ctypes.c_void_p), ctypes.c_void_p]
    jvm = ctypes.c_void_p()
    env = ctypes.c_void_p()
    if create(ctypes.byref(jvm), ctypes.byref(env), ctypes.byref(init_args)) != _JNI_OK:
        raise RuntimeError("jpy: failed to create Java VM")
    _jvm = jvm.value
    _install(env)
    _set_diag_flags(_pending_diag_flags)
    _set_verbose_exceptions(_pending_verbose_exceptions)
    return None


def _install(env):
    import ctypes
    find_class = _vtable_fn(env, _ENV_FIND_CLASS, ctypes.c_void_p, ctypes.c_char_p)
    get_static_method_id = _vtable_fn(env, _ENV_GET_STATIC_METHOD_ID, ctypes.c_void_p,
                                      ctypes.c_void_p, ctypes.c_char_p, ctypes.c_char_p)
    call_static_void = _vtable_fn(env, _ENV_CALL_STATIC_VOID_METHOD_A, None,
                                  ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p)
    exception_check = _vtable_fn(env, _ENV_EXCEPTION_CHECK, ctypes.c_uint8)
    exception_describe = _vtable_fn(env, _ENV_EXCEPTION_DESCRIBE, None)
    exception_clear = _vtable_fn(env, _ENV_EXCEPTION_CLEAR, None)

    def fail(message):
        if exception_check():
            exception_describe()
            exception_clear()
        raise RuntimeError(message)

    bootstrap = find_class(b"org/jpy/ffm/Bootstrap")
    if not bootstrap:
        fail("jpy: org.jpy.ffm.Bootstrap not found on the class path " + _ffm_classpath())
    install = get_static_method_id(bootstrap, b"install", b"(J)V")
    if not install:
        fail("jpy: org.jpy.ffm.Bootstrap.install(long) not found")
    jvalues = (ctypes.c_int64 * 1)(id(_sys.modules[__name__]))
    call_static_void(bootstrap, install, ctypes.cast(jvalues, ctypes.c_void_p))
    if exception_check():
        fail("jpy: FFM bridge installation failed")


def destroy_jvm():
    """destroy_jvm() - Destroy the current Java VM."""
    global _jvm
    if _jvm is not None:
        import ctypes
        _vtable_fn(_jvm, _VM_DESTROY_JAVA_VM, ctypes.c_int32)()
        _jvm = None
    return None
