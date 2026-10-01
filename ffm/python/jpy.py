"""
jpy on the Java FFM API (CPython 3.12+, JDK 25+).

This module is pure Python. create_jvm() starts a JVM inside this process
through the JNI invocation API (reached with ctypes; the one piece of JNI the
FFM design keeps) and calls org.jpy.ffm.Bootstrap.install(), which fills this
module in from Java: get_type, JType, JOverloadedMethod, JField, ...
"""

import ctypes
import os
import sys

# Filled by Java. Same names and roles as in the C jpy module.
types = {}
type_callbacks = {}
type_translations = {}


class JException(Exception):
    pass


_jvm = None  # JavaVM*, as an int

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


class _JavaVMOption(ctypes.Structure):
    _fields_ = [("optionString", ctypes.c_char_p), ("extraInfo", ctypes.c_void_p)]


class _JavaVMInitArgs(ctypes.Structure):
    _fields_ = [("version", ctypes.c_int32),
                ("nOptions", ctypes.c_int32),
                ("options", ctypes.POINTER(_JavaVMOption)),
                ("ignoreUnrecognized", ctypes.c_uint8)]


def has_jvm():
    """has_jvm() - Check if the JVM is available."""
    return _jvm is not None


def _vtable_fn(obj, index, restype, *argtypes):
    """Slot `index` of a JNI function table; obj is a JNIEnv* or JavaVM* (pointer to table pointer)."""
    if isinstance(obj, int):
        obj = ctypes.c_void_p(obj)
    table = ctypes.cast(obj, ctypes.POINTER(ctypes.POINTER(ctypes.c_void_p))).contents
    fn = ctypes.cast(table[index], ctypes.CFUNCTYPE(restype, ctypes.c_void_p, *argtypes))
    return lambda *args: fn(obj, *args)


def _libpython_path():
    """The path of the libpython image this interpreter runs from."""
    override = os.environ.get('JPY_PYTHON_LIB')
    if override:
        return override
    if sys.platform == 'win32':
        buf = ctypes.create_unicode_buffer(32768)
        ctypes.windll.kernel32.GetModuleFileNameW(ctypes.c_void_p(sys.dllhandle), buf, len(buf))
        return buf.value

    class _DlInfo(ctypes.Structure):
        _fields_ = [("dli_fname", ctypes.c_char_p), ("dli_fbase", ctypes.c_void_p),
                    ("dli_sname", ctypes.c_char_p), ("dli_saddr", ctypes.c_void_p)]

    info = _DlInfo()
    libc = ctypes.CDLL(None)
    address = ctypes.cast(ctypes.pythonapi.Py_IncRef, ctypes.c_void_p)
    if not libc.dladdr(address, ctypes.byref(info)) or not info.dli_fname:
        raise RuntimeError("jpy: cannot locate the libpython shared library of this interpreter")
    path = os.path.realpath(info.dli_fname.decode())
    if os.path.realpath(path) == os.path.realpath(sys.executable):
        raise RuntimeError("jpy: this Python is statically linked; the FFM jpy needs a shared libpython")
    return path


def _ffm_classpath():
    """Where the jpy FFM Java classes live: an explicit override, a bundled jar, or the dev build."""
    override = os.environ.get('JPY_FFM_CLASSPATH')
    if override:
        return override
    here = os.path.dirname(os.path.abspath(__file__))
    jar = os.path.join(here, 'jpy-ffm.jar')
    if os.path.exists(jar):
        return jar
    return os.path.normpath(os.path.join(here, '..', 'build', 'classes'))


def _create_java_vm_fn():
    """JNI_CreateJavaVM from an already loaded libjvm, else from JAVA_HOME."""
    try:
        return ctypes.CDLL(None).JNI_CreateJavaVM
    except (AttributeError, OSError):
        pass
    java_home = os.environ.get('JAVA_HOME')
    if not java_home:
        raise RuntimeError("jpy: no JVM library loaded and JAVA_HOME is not set")
    if sys.platform == 'win32':
        lib = os.path.join(java_home, 'bin', 'server', 'jvm.dll')
    elif sys.platform == 'darwin':
        lib = os.path.join(java_home, 'lib', 'server', 'libjvm.dylib')
    else:
        lib = os.path.join(java_home, 'lib', 'server', 'libjvm.so')
    return ctypes.CDLL(lib, mode=ctypes.RTLD_GLOBAL).JNI_CreateJavaVM


def _with_ffm_options(options):
    classpath = _ffm_classpath()
    result = []
    has_classpath = False
    for option in options:
        if option.startswith('-Djava.class.path='):
            option = option + os.pathsep + classpath
            has_classpath = True
        result.append(option)
    if not has_classpath:
        result.append('-Djava.class.path=' + classpath)
    result.append('-Djpy.pythonLib=' + _libpython_path())
    result.append('--enable-native-access=ALL-UNNAMED')
    return result


def create_jvm(options):
    """create_jvm(options) - Create the Java VM from the given list of options."""
    global _jvm
    if _jvm is not None:
        return None
    if isinstance(options, (str, bytes)) or not hasattr(options, '__len__'):
        raise ValueError("create_jvm: argument 1 (options) must be a sequence of Java VM option strings")
    all_options = _with_ffm_options([str(o) for o in options])

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
    return None


def _install(env):
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
    jvalues = (ctypes.c_int64 * 1)(id(sys.modules[__name__]))
    call_static_void(bootstrap, install, ctypes.cast(jvalues, ctypes.c_void_p))
    if exception_check():
        fail("jpy: FFM bridge installation failed")


def destroy_jvm():
    """destroy_jvm() - Destroy the current Java VM."""
    global _jvm
    if _jvm is not None:
        _vtable_fn(_jvm, _VM_DESTROY_JAVA_VM, ctypes.c_int32)()
        _jvm = None
    return None
