"""
Pure-Python bootstrap for the FFM bridge: the replacement for jpy's compiled
extension module in Python-first mode.

Creates a JVM in-process with ctypes + JNI_CreateJavaVM (the one piece of JNI
the FFM design keeps), then calls ffm.Bootstrap.install() through the JNI
invocation API. install() uses FFM to register the 'jbridge' module into this
already-running interpreter. After start_jvm(), `import jbridge` just works.

No compiled Python extension anywhere: this file is version-independent
pure Python, so one wheel covers every CPython the jar supports.
"""

import ctypes
import os
from pathlib import Path

JAVA_HOME = os.environ.get("JAVA_HOME", str(Path.home() / ".sdkman/candidates/java/25.0.3-tem"))
LIBJVM = str(Path(JAVA_HOME) / "lib" / "server" / "libjvm.dylib")
CLASSES = str(Path(__file__).resolve().parent.parent / "out")

JNI_VERSION_10 = 0x000A0000
JNI_OK = 0

# JNIEnv function-table indices, extracted from this JDK's jni.h
# (tools/jni_indices.py). Fixed by the JNI spec.
IDX_FIND_CLASS = 6
IDX_EXCEPTION_DESCRIBE = 16
IDX_GET_STATIC_METHOD_ID = 113
IDX_CALL_STATIC_VOID_A = 143
IDX_EXCEPTION_CHECK = 228


class JavaVMOption(ctypes.Structure):
    _fields_ = [("optionString", ctypes.c_char_p), ("extraInfo", ctypes.c_void_p)]


class JavaVMInitArgs(ctypes.Structure):
    _fields_ = [("version", ctypes.c_int32), ("nOptions", ctypes.c_int32),
                ("options", ctypes.POINTER(JavaVMOption)), ("ignoreUnrecognized", ctypes.c_uint8)]


def _env_fn(env, index, restype, *argtypes):
    """Fetch JNIEnv vtable slot `index` as a callable: env is JNINativeInterface_**."""
    vtable = ctypes.cast(env, ctypes.POINTER(ctypes.POINTER(ctypes.c_void_p))).contents
    fn = ctypes.cast(vtable[index], ctypes.CFUNCTYPE(restype, ctypes.c_void_p, *argtypes))
    return lambda *args: fn(env, *args)


def start_jvm(classpath=CLASSES, options=()):
    jvm_lib = ctypes.CDLL(LIBJVM)
    create = jvm_lib.JNI_CreateJavaVM
    create.restype = ctypes.c_int32
    create.argtypes = [ctypes.POINTER(ctypes.c_void_p), ctypes.POINTER(ctypes.c_void_p),
                       ctypes.c_void_p]

    opt_strings = [f"-Djava.class.path={classpath}",
                   "--enable-native-access=ALL-UNNAMED", *options]
    opts = (JavaVMOption * len(opt_strings))()
    for i, s in enumerate(opt_strings):
        opts[i].optionString = s.encode()
    args = JavaVMInitArgs(JNI_VERSION_10, len(opt_strings), opts, 1)

    jvm = ctypes.c_void_p()
    env = ctypes.c_void_p()
    rc = create(ctypes.byref(jvm), ctypes.byref(env), ctypes.byref(args))
    if rc != JNI_OK:
        raise RuntimeError(f"JNI_CreateJavaVM failed: {rc}")

    find_class = _env_fn(env, IDX_FIND_CLASS, ctypes.c_void_p, ctypes.c_char_p)
    get_static_mid = _env_fn(env, IDX_GET_STATIC_METHOD_ID, ctypes.c_void_p,
                             ctypes.c_void_p, ctypes.c_char_p, ctypes.c_char_p)
    call_static_void = _env_fn(env, IDX_CALL_STATIC_VOID_A, None,
                               ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p)
    exception_check = _env_fn(env, IDX_EXCEPTION_CHECK, ctypes.c_uint8)
    exception_describe = _env_fn(env, IDX_EXCEPTION_DESCRIBE, None)

    cls = find_class(b"ffm/Bootstrap")
    if not cls:
        exception_describe()
        raise RuntimeError("ffm.Bootstrap not found on class path " + classpath)
    mid = get_static_mid(cls, b"install", b"()V")
    if not mid:
        exception_describe()
        raise RuntimeError("ffm.Bootstrap.install()V not found")
    call_static_void(cls, mid, None)
    if exception_check():
        exception_describe()
        raise RuntimeError("Bootstrap.install() threw")
    return jvm
