/*
 * Copyright (C) 2014 Brockmann Consult GmbH
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation; either version 3 of the License,
 * or (at your option) any later version. This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program; if not, see
 * http://www.gnu.org/licenses/
 */

#include <jni.h>
#include <Python.h>

#include "jpy_module.h"
#include "jpy_diag.h"
#include "jpy_jtype.h"
#include "jpy_jobj.h"
#include "jpy_conv.h"

#include "org_jpy_PyLib.h"
#include "org_jpy_PyLib_Diag.h"

// Note: Native org.jpy.PyLib function definition headers in this file are formatted according to the header
// generated by javah. This makes it easier to follow up changes in the header.

PyObject* PyLib_GetAttributeObject(JNIEnv* jenv, PyObject* pyValue, jstring jName);
PyObject* PyLib_CallAndReturnObject(JNIEnv *jenv, PyObject* pyValue, jboolean isMethodCall, jstring jName, jint argCount, jobjectArray jArgs, jobjectArray jParamClasses);
void PyLib_HandlePythonException(JNIEnv* jenv);
void PyLib_RedirectStdOut(void);

static int JPy_InitThreads = 0;

//#define JPy_JNI_DEBUG 1
#define JPy_JNI_DEBUG 0

// Make sure the following contants are same as in enum org.jpy.PyInputMode
#define JPy_IM_STATEMENT  256
#define JPy_IM_SCRIPT     257
#define JPy_IM_EXPRESSION 258

#define JPy_GIL_AWARE

#ifdef JPy_GIL_AWARE
    #define JPy_BEGIN_GIL_STATE  { PyGILState_STATE gilState; if (!JPy_InitThreads) {JPy_InitThreads = 1; PyEval_InitThreads(); PyEval_SaveThread(); } gilState = PyGILState_Ensure();
    #define JPy_END_GIL_STATE    PyGILState_Release(gilState); }
#else
    #define JPy_BEGIN_GIL_STATE
    #define JPy_END_GIL_STATE
#endif


/**
 * Called if the JVM loads this module.
 * Will only called if this module's code is linked into a shared library and loaded by a Java VM.
 */
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* jvm, void* reserved)
{
    if (JPy_JNI_DEBUG) printf("JNI_OnLoad: enter: jvm=%p, JPy_JVM=%p, JPy_MustDestroyJVM=%d, Py_IsInitialized()=%d\n",
                              jvm, JPy_JVM, JPy_MustDestroyJVM, Py_IsInitialized());

    if (JPy_JVM == NULL) {
        JPy_JVM = jvm;
        JPy_MustDestroyJVM = JNI_FALSE;
    } else if (JPy_JVM == jvm) {
        if (JPy_JNI_DEBUG) printf("JNI_OnLoad: warning: same JVM already running\n");
    } else {
        if (JPy_JNI_DEBUG) printf("JNI_OnLoad: warning: different JVM already running (expect weird things!)\n");
    }

    if (JPy_JNI_DEBUG) printf("JNI_OnLoad: exit: jvm=%p, JPy_JVM=%p, JPy_MustDestroyJVM=%d, Py_IsInitialized()=%d\n",
                              jvm, JPy_JVM, JPy_MustDestroyJVM, Py_IsInitialized());

    if (JPy_JNI_DEBUG) fflush(stdout);

    return JPY_JNI_VERSION;
}


/**
 * Called if the JVM unloads this module.
 * Will only called if this module's code is linked into a shared library and loaded by a Java VM.
 */
JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* jvm, void* reserved)
{
    JPy_DIAG_PRINT(JPy_DIAG_F_ALL, "JNI_OnUnload: enter: jvm=%p, JPy_JVM=%p, JPy_MustDestroyJVM=%d, Py_IsInitialized()=%d\n",
                   jvm, JPy_JVM, JPy_MustDestroyJVM, Py_IsInitialized());

    Py_Finalize();

    if (!JPy_MustDestroyJVM) {
        JPy_ClearGlobalVars(JPy_GetJNIEnv());
        JPy_JVM = NULL;
    }

    JPy_DIAG_PRINT(JPy_DIAG_F_ALL, "JNI_OnUnload: exit: jvm=%p, JPy_JVM=%p, JPy_MustDestroyJVM=%d, Py_IsInitialized()=%d\n",
                   jvm, JPy_JVM, JPy_MustDestroyJVM, Py_IsInitialized());
}



/*
 * Class:     org_jpy_PyLib
 * Method:    isPythonRunning
 * Signature: ()Z
 */
JNIEXPORT jboolean JNICALL Java_org_jpy_PyLib_isPythonRunning
  (JNIEnv* jenv, jclass jLibClass)
{
    int init;
    init = Py_IsInitialized();
    return init && JPy_Module != NULL;
}

/*
 * Class:     org_jpy_PyLib
 * Method:    startPython0
 * Signature: ([Ljava/lang/String;)Z
 */
JNIEXPORT jboolean JNICALL Java_org_jpy_PyLib_startPython0
  (JNIEnv* jenv, jclass jLibClass, jobjectArray jPathArray)
{
    int pyInit = Py_IsInitialized();

    JPy_DIAG_PRINT(JPy_DIAG_F_ALL, "PyLib_startPython: entered: jenv=%p, pyInit=%d, JPy_Module=%p\n", jenv, pyInit, JPy_Module);

    if (!pyInit) {
        Py_Initialize();
        PyLib_RedirectStdOut();
        pyInit = Py_IsInitialized();
    }

    if (pyInit) {

        if (JPy_DiagFlags != 0) {
            printf("PyLib_startPython: global Python interpreter information:\n");
            #if defined(JPY_COMPAT_33P)
            printf("  Py_GetProgramName()     = \"%ls\"\n", Py_GetProgramName());
            printf("  Py_GetPrefix()          = \"%ls\"\n", Py_GetPrefix());
            printf("  Py_GetExecPrefix()      = \"%ls\"\n", Py_GetExecPrefix());
            printf("  Py_GetProgramFullPath() = \"%ls\"\n", Py_GetProgramFullPath());
            printf("  Py_GetPath()            = \"%ls\"\n", Py_GetPath());
            printf("  Py_GetPythonHome()      = \"%ls\"\n", Py_GetPythonHome());
            #elif defined(JPY_COMPAT_27)
            printf("  Py_GetProgramName()     = \"%s\"\n", Py_GetProgramName());
            printf("  Py_GetPrefix()          = \"%s\"\n", Py_GetPrefix());
            printf("  Py_GetExecPrefix()      = \"%s\"\n", Py_GetExecPrefix());
            printf("  Py_GetProgramFullPath() = \"%s\"\n", Py_GetProgramFullPath());
            printf("  Py_GetPath()            = \"%s\"\n", Py_GetPath());
            printf("  Py_GetPythonHome()      = \"%s\"\n", Py_GetPythonHome());
            #endif
            printf("  Py_GetVersion()         = \"%s\"\n", Py_GetVersion());
            printf("  Py_GetPlatform()        = \"%s\"\n", Py_GetPlatform());
            printf("  Py_GetCompiler()        = \"%s\"\n", Py_GetCompiler());
            printf("  Py_GetBuildInfo()       = \"%s\"\n", Py_GetBuildInfo());
        }

        // If we've got jPathArray, add all entries to Python's "sys.path"
        //
        if (jPathArray != NULL) {
            PyObject* pyPathList;
            PyObject* pyPath;
            jstring jPath;
            jsize i, pathCount;

            pathCount = (*jenv)->GetArrayLength(jenv, jPathArray);
            //printf(">> pathCount=%d\n", pathCount);
            if (pathCount > 0) {

                JPy_BEGIN_GIL_STATE

                pyPathList = PySys_GetObject("path");
                //printf(">> pyPathList=%p, len=%ld\n", pyPathList, PyList_Size(pyPathList));
                if (pyPathList != NULL) {
                    Py_INCREF(pyPathList);
                    for (i = pathCount - 1; i >= 0; i--) {
                        jPath = (*jenv)->GetObjectArrayElement(jenv, jPathArray, i);
                        //printf(">> i=%d, jPath=%p\n", i, jPath);
                        if (jPath != NULL) {
                            pyPath = JPy_FromJString(jenv, jPath);
                            //printf(">> i=%d, pyPath=%p\n", i, pyPath);
                            if (pyPath != NULL) {
                                PyList_Insert(pyPathList, 0, pyPath);
                            }
                        }
                    }
                    Py_DECREF(pyPathList);
                }
                //printf(">> pyPathList=%p, len=%ld\n", pyPathList, PyList_Size(pyPathList));
                //printf(">> pyPathList=%p, len=%ld (check)\n", PySys_GetObject("path"), PyList_Size(PySys_GetObject("path")));

                JPy_END_GIL_STATE
            }
        }

        // if the global JPy_Module is NULL, then the 'jpy' extension module has not been imported yet.
        //
        if (JPy_Module == NULL) {
            PyObject* pyModule;

            JPy_BEGIN_GIL_STATE

            // We import 'jpy' so that Python can call our PyInit_jpy() which sets up a number of
            // required global variables (including JPy_Module, see above).
            //
            pyModule = PyImport_ImportModule("jpy");
            //printf(">> pyModule=%p\n", pyModule);
            if (pyModule == NULL) {
                JPy_DIAG_PRINT(JPy_DIAG_F_ALL, "PyLib_startPython: failed to import module 'jpy'\n");
                if (JPy_DiagFlags != 0 && PyErr_Occurred()) {
                    PyErr_Print();
                }
                PyLib_HandlePythonException(jenv);
            }

            JPy_END_GIL_STATE
        }
    }

    JPy_DIAG_PRINT(JPy_DIAG_F_ALL, "PyLib_startPython: exiting: jenv=%p, pyInit=%d, JPy_Module=%p\n", jenv, pyInit, JPy_Module);

    //printf(">> JPy_Module=%p\n", JPy_Module);

    if (!pyInit) {
        (*jenv)->ThrowNew(jenv, JPy_RuntimeException_JClass, "Failed to initialize Python interpreter.");
        return JNI_FALSE;
    }
    if (JPy_Module == NULL) {
        (*jenv)->ThrowNew(jenv, JPy_RuntimeException_JClass, "Failed to initialize Python 'jpy' module.");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}
  

/*
 * Class:     org_jpy_PyLib
 * Method:    stopPython
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_org_jpy_PyLib_stopPython
  (JNIEnv* jenv, jclass jLibClass)
{
    JPy_DIAG_PRINT(JPy_DIAG_F_ALL, "Java_org_jpy_PyLib_stopPython: entered: JPy_Module=%p\n", JPy_Module);

    if (Py_IsInitialized()) {
        Py_Finalize();
    }

    JPy_DIAG_PRINT(JPy_DIAG_F_ALL, "Java_org_jpy_PyLib_stopPython: exiting: JPy_Module=%p\n", JPy_Module);
}


/*
 * Class:     org_jpy_PyLib
 * Method:    getPythonVersion
 * Signature: ()Ljava/lang/String;
 */
JNIEXPORT jstring JNICALL Java_org_jpy_PyLib_getPythonVersion
  (JNIEnv* jenv, jclass jLibClass)
{
    const char* version;

    version = Py_GetVersion();
    if (version == NULL) {
        return NULL;
    }

    return (*jenv)->NewStringUTF(jenv, version);
}


/*
 * Class:     org_jpy_python_PyLib
 * Method:    execScript
 * Signature: (Ljava/lang/String;)J
 */
JNIEXPORT jint JNICALL Java_org_jpy_PyLib_execScript
  (JNIEnv* jenv, jclass jLibClass, jstring jScript)
{
    const char* scriptChars;
    int retCode;

    JPy_BEGIN_GIL_STATE

    scriptChars = (*jenv)->GetStringUTFChars(jenv, jScript, NULL);
    JPy_DIAG_PRINT(JPy_DIAG_F_EXEC, "Java_org_jpy_PyLib_execScript: script='%s'\n", scriptChars);
    retCode = PyRun_SimpleString(scriptChars);
    if (retCode < 0) {
        JPy_DIAG_PRINT(JPy_DIAG_F_ALL, "Java_org_jpy_PyLib_execScript: error: PyRun_SimpleString(\"%s\") returned %d\n", scriptChars, retCode);
        // Note that we cannot retrieve last Python exception after a calling PyRun_SimpleString, see documentation of PyRun_SimpleString.
    }
    (*jenv)->ReleaseStringUTFChars(jenv, jScript, scriptChars);

    JPy_END_GIL_STATE

    return retCode;
}


PyObject* PyLib_ConvertJavaMapToPythonDict(jobject jMap)
{
    if (jMap == NULL) {
        //return NULL;
    }

    return PyDict_New();
}

void PyLib_ReleasePythonDict(PyObject* pyDict, jobject jMap)
{

}

JNIEXPORT jlong JNICALL Java_org_jpy_PyLib_executeCode
  (JNIEnv* jenv, jclass jLibClass, jstring jCode, jint jStart, jobject jGlobals, jobject jLocals)
{
    const char* codeChars;
    PyObject* pyReturnValue;
    PyObject* pyGlobals;
    PyObject* pyLocals;
    PyObject* pyMainModule;
    int start;

    JPy_BEGIN_GIL_STATE

    pyMainModule = PyImport_AddModule("__main__");
    if (pyMainModule == NULL) {
        return 0L;
    }

    codeChars = (*jenv)->GetStringUTFChars(jenv, jCode, NULL);

    pyGlobals = PyModule_GetDict(pyMainModule);

    //pyGlobals = PyLib_ConvertJavaMapToPythonDict(jGlobals);
    pyLocals = PyLib_ConvertJavaMapToPythonDict(jLocals);

    start = jStart == JPy_IM_STATEMENT ? Py_single_input :
            jStart == JPy_IM_SCRIPT ? Py_file_input :
            Py_eval_input;

    pyReturnValue = PyRun_String(codeChars, jStart, pyGlobals, pyLocals);
    if (pyReturnValue == NULL) {
        PyLib_HandlePythonException(jenv);
    }

    (*jenv)->ReleaseStringUTFChars(jenv, jCode, codeChars);

    PyLib_ReleasePythonDict(pyGlobals, jGlobals);
    PyLib_ReleasePythonDict(pyLocals, jLocals);

    JPy_END_GIL_STATE

    return (jlong) pyReturnValue;
}

/*
 * Class:     org_jpy_python_PyLib
 * Method:    incRef
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_org_jpy_PyLib_incRef
  (JNIEnv* jenv, jclass jLibClass, jlong objId)
{
    PyObject* pyObject;
    Py_ssize_t refCount;

    pyObject = (PyObject*) objId;

    if (Py_IsInitialized()) {
        JPy_BEGIN_GIL_STATE

        refCount = pyObject->ob_refcnt;
        JPy_DIAG_PRINT(JPy_DIAG_F_MEM, "Java_org_jpy_PyLib_incRef: pyObject=%p, refCount=%d, type='%s'\n", pyObject, refCount, Py_TYPE(pyObject)->tp_name);
        Py_INCREF(pyObject);

        JPy_END_GIL_STATE
    } else {
        JPy_DIAG_PRINT(JPy_DIAG_F_ALL, "Java_org_jpy_PyLib_incRef: error: no interpreter: pyObject=%p\n", pyObject);
    }
}

/*
 * Class:     org_jpy_python_PyLib
 * Method:    decRef
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_org_jpy_PyLib_decRef
  (JNIEnv* jenv, jclass jLibClass, jlong objId)
{
    PyObject* pyObject;
    Py_ssize_t refCount;

    pyObject = (PyObject*) objId;

    if (Py_IsInitialized()) {
        JPy_BEGIN_GIL_STATE

        refCount = pyObject->ob_refcnt;
        if (refCount <= 0) {
            JPy_DIAG_PRINT(JPy_DIAG_F_ALL, "Java_org_jpy_PyLib_decRef: error: refCount <= 0: pyObject=%p, refCount=%d\n", pyObject, refCount);
        } else {
            JPy_DIAG_PRINT(JPy_DIAG_F_MEM, "Java_org_jpy_PyLib_decRef: pyObject=%p, refCount=%d, type='%s'\n", pyObject, refCount, Py_TYPE(pyObject)->tp_name);
            Py_DECREF(pyObject);
        }

        JPy_END_GIL_STATE
    } else {
        JPy_DIAG_PRINT(JPy_DIAG_F_ALL, "Java_org_jpy_PyLib_decRef: error: no interpreter: pyObject=%p\n", pyObject);
    }
}


/*
 * Class:     org_jpy_python_PyLib
 * Method:    getIntValue
 * Signature: (J)I
 */
JNIEXPORT jint JNICALL Java_org_jpy_PyLib_getIntValue
  (JNIEnv* jenv, jclass jLibClass, jlong objId)
{
    PyObject* pyObject;
    jint value;

    JPy_BEGIN_GIL_STATE

    pyObject = (PyObject*) objId;
    value = (jint) JPy_AS_CLONG(pyObject);

    JPy_END_GIL_STATE

    return value;
}

/*
 * Class:     org_jpy_python_PyLib
 * Method:    getDoubleValue
 * Signature: (J)D
 */
JNIEXPORT jdouble JNICALL Java_org_jpy_PyLib_getDoubleValue
  (JNIEnv* jenv, jclass jLibClass, jlong objId)
{
    PyObject* pyObject;
    jdouble value;

    JPy_BEGIN_GIL_STATE

    pyObject = (PyObject*) objId;
    value = (jdouble) PyFloat_AsDouble(pyObject);

    JPy_END_GIL_STATE

    return value;
}

/*
 * Class:     org_jpy_python_PyLib
 * Method:    getStringValue
 * Signature: (J)Ljava/lang/String;
 */
JNIEXPORT jstring JNICALL Java_org_jpy_PyLib_getStringValue
  (JNIEnv* jenv, jclass jLibClass, jlong objId)
{
    PyObject* pyObject;
    jstring jString;

    JPy_BEGIN_GIL_STATE

    pyObject = (PyObject*) objId;

    if (JPy_AsJString(jenv, pyObject, &jString) < 0) {
        jString = NULL;
        JPy_DIAG_PRINT(JPy_DIAG_F_ALL, "Java_org_jpy_PyLib_getStringValue: error: failed to convert Python object to Java String\n");
        PyLib_HandlePythonException(jenv);
    }

    JPy_END_GIL_STATE

    return jString;
}

/*
 * Class:     org_jpy_python_PyLib
 * Method:    getObjectValue
 * Signature: (J)Ljava/lang/Object;
 */
JNIEXPORT jobject JNICALL Java_org_jpy_PyLib_getObjectValue
  (JNIEnv* jenv, jclass jLibClass, jlong objId)
{
    PyObject* pyObject;
    jobject jObject;

    JPy_BEGIN_GIL_STATE

    pyObject = (PyObject*) objId;

    if (JObj_Check(pyObject)) {
        jObject = ((JPy_JObj*) pyObject)->objectRef;
    } else {
        if (JPy_AsJObject(jenv, pyObject, &jObject) < 0) {
            jObject = NULL;
            JPy_DIAG_PRINT(JPy_DIAG_F_ALL, "Java_org_jpy_PyLib_getObjectValue: error: failed to convert Python object to Java Object\n");
            PyLib_HandlePythonException(jenv);
        }
    }

    JPy_END_GIL_STATE

    return jObject;
}

/*
 * Class:     org_jpy_PyLib
 * Method:    getObjectArrayValue
 * Signature: (JLjava/lang/Class;)[Ljava/lang/Object;
 */
JNIEXPORT jobjectArray JNICALL Java_org_jpy_PyLib_getObjectArrayValue
  (JNIEnv* jenv, jclass jLibClass, jlong objId, jclass itemClassRef)
{
    PyObject* pyObject;
    jobject jObject;

    JPy_BEGIN_GIL_STATE

    pyObject = (PyObject*) objId;

    if (pyObject == Py_None) {
        jObject = NULL;
    } else if (JObj_Check(pyObject)) {
        jObject = ((JPy_JObj*) pyObject)->objectRef;
    } else if (PySequence_Check(pyObject)) {
        PyObject* pyItem;
        jobject jItem;
        jint i, length;

        length = PySequence_Length(pyObject);

        jObject = (*jenv)->NewObjectArray(jenv, length, itemClassRef, NULL);

        for (i = 0; i < length; i++) {
            pyItem = PySequence_GetItem(pyObject, i);
            if (pyItem == NULL) {
                (*jenv)->DeleteLocalRef(jenv, jObject);
                jObject = NULL;
                goto error;
            }
            if (JPy_AsJObject(jenv, pyItem, &jItem) < 0) {
                (*jenv)->DeleteLocalRef(jenv, jObject);
                jObject = NULL;
                JPy_DIAG_PRINT(JPy_DIAG_F_ALL, "Java_org_jpy_PyLib_getObjectArrayValue: error: failed to convert Python item to Java Object\n");
                PyLib_HandlePythonException(jenv);
                goto error;
            }
            Py_XDECREF(pyItem);
            (*jenv)->SetObjectArrayElement(jenv, jObject, i, jItem);
            if ((*jenv)->ExceptionCheck(jenv)) {
                (*jenv)->DeleteLocalRef(jenv, jObject);
                jObject = NULL;
                goto error;
            }
        }
    } else {
        jObject = NULL;
        (*jenv)->ThrowNew(jenv, JPy_RuntimeException_JClass, "python object cannot be converted to Object[]");
    }

error:
    JPy_END_GIL_STATE

    return jObject;
}


/*
 * Class:     org_jpy_python_PyLib
 * Method:    getModule
 * Signature: (Ljava/lang/String;)J
 */
JNIEXPORT jlong JNICALL Java_org_jpy_PyLib_importModule
  (JNIEnv* jenv, jclass jLibClass, jstring jName)
{
    PyObject* pyName;
    PyObject* pyModule;
    const char* nameChars;

    JPy_BEGIN_GIL_STATE

    nameChars = (*jenv)->GetStringUTFChars(jenv, jName, NULL);
    JPy_DIAG_PRINT(JPy_DIAG_F_EXEC, "Java_org_jpy_PyLib_importModule: name='%s'\n", nameChars);
    /* Note: pyName is a new reference */
    pyName = JPy_FROM_CSTR(nameChars);
    /* Note: pyModule is a new reference */
    pyModule = PyImport_Import(pyName);
    if (pyModule == NULL) {
        PyLib_HandlePythonException(jenv);
    }
    Py_XDECREF(pyName);
    (*jenv)->ReleaseStringUTFChars(jenv, jName, nameChars);

    JPy_END_GIL_STATE

    return (jlong) pyModule;
}




/*
 * Class:     org_jpy_python_PyLib
 * Method:    getAttributeValue
 * Signature: (JLjava/lang/String;)J
 */
JNIEXPORT jlong JNICALL Java_org_jpy_PyLib_getAttributeObject
  (JNIEnv* jenv, jclass jLibClass, jlong objId, jstring jName)
{
    PyObject* pyObject;
    PyObject* pyValue;

    JPy_BEGIN_GIL_STATE

    pyObject = (PyObject*) objId;
    pyValue = PyLib_GetAttributeObject(jenv, pyObject, jName);

    JPy_END_GIL_STATE

    return (jlong) pyValue;
}

/*
 * Class:     org_jpy_python_PyLib
 * Method:    getAttributeValue
 * Signature: (JLjava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;
 */
JNIEXPORT jobject JNICALL Java_org_jpy_PyLib_getAttributeValue
  (JNIEnv* jenv, jclass jLibClass, jlong objId, jstring jName, jclass jValueClass)
{
    PyObject* pyObject;
    PyObject* pyValue;
    jobject jReturnValue;

    JPy_BEGIN_GIL_STATE

    pyObject = (PyObject*) objId;

    pyValue = PyLib_GetAttributeObject(jenv, pyObject, jName);
    if (pyValue == NULL) {
        jReturnValue = NULL;
        goto error;
    }

    if (JPy_AsJObjectWithClass(jenv, pyValue, &jReturnValue, jValueClass) < 0) {
        jReturnValue = NULL;
        JPy_DIAG_PRINT(JPy_DIAG_F_ALL, "Java_org_jpy_PyLib_getAttributeValue: error: failed to convert attribute value\n");
        PyLib_HandlePythonException(jenv);
    }

error:
    JPy_END_GIL_STATE

    return jReturnValue;
}


/*
 * Class:     org_jpy_python_PyLib
 * Method:    setAttributeValue
 * Signature: (JLjava/lang/String;J)V
 */
JNIEXPORT void JNICALL Java_org_jpy_PyLib_setAttributeValue
  (JNIEnv* jenv, jclass jLibClass, jlong objId, jstring jName, jobject jValue, jclass jValueClass)
{
    PyObject* pyObject;
    const char* nameChars;
    PyObject* pyValue;
    JPy_JType* valueType;

    JPy_BEGIN_GIL_STATE

    pyObject = (PyObject*) objId;

    nameChars = (*jenv)->GetStringUTFChars(jenv, jName, NULL);
    JPy_DIAG_PRINT(JPy_DIAG_F_EXEC, "Java_org_jpy_PyLib_setAttributeValue: objId=%p, name='%s', jValue=%p, jValueClass=%p\n", pyObject, nameChars, jValue, jValueClass);

    if (jValueClass != NULL) {
        valueType = JType_GetType(jenv, jValueClass, JNI_FALSE);
    } else {
        valueType = NULL;
    }

    if (valueType != NULL) {
        pyValue = JPy_FromJObjectWithType(jenv, jValue, valueType);
    } else {
        pyValue = JPy_FromJObject(jenv, jValue);
    }

    if (pyValue == NULL) {
        JPy_DIAG_PRINT(JPy_DIAG_F_ALL, "Java_org_jpy_PyLib_setAttributeValue: error: attribute '%s': Java object not convertible\n", nameChars);
        PyLib_HandlePythonException(jenv);
        goto error;
    }

    if (PyObject_SetAttrString(pyObject, nameChars, pyValue) < 0) {
        JPy_DIAG_PRINT(JPy_DIAG_F_ALL, "Java_org_jpy_PyLib_setAttributeValue: error: PyObject_SetAttrString failed on attribute '%s'\n", nameChars);
        PyLib_HandlePythonException(jenv);
        goto error;
    }

error:
    (*jenv)->ReleaseStringUTFChars(jenv, jName, nameChars);

    JPy_END_GIL_STATE
}


/*
 * Class:     org_jpy_python_PyLib
 * Method:    call
 * Signature: (JZLjava/lang/String;I[Ljava/lang/Object;[Ljava/lang/Class;Ljava/lang/Class;)Ljava/lang/Object;
 */
JNIEXPORT jlong JNICALL Java_org_jpy_PyLib_callAndReturnObject
  (JNIEnv *jenv, jclass jLibClass, jlong objId, jboolean isMethodCall, jstring jName, jint argCount, jobjectArray jArgs, jobjectArray jParamClasses)
{
    PyObject* pyObject;
    PyObject* pyReturnValue;

    JPy_BEGIN_GIL_STATE

    pyObject = (PyObject*) objId;
    pyReturnValue = PyLib_CallAndReturnObject(jenv, pyObject, isMethodCall, jName, argCount, jArgs, jParamClasses);

    JPy_END_GIL_STATE

    return (jlong) pyReturnValue;
}


/*
 * Class:     org_jpy_python_PyLib
 * Method:    callAndReturnValue
 * Signature: (JZLjava/lang/String;I[Ljava/lang/Object;[Ljava/lang/Class;Ljava/lang/Class;)Ljava/lang/Object;
 */
JNIEXPORT jobject JNICALL Java_org_jpy_PyLib_callAndReturnValue
  (JNIEnv *jenv, jclass jLibClass, jlong objId, jboolean isMethodCall, jstring jName, jint argCount, jobjectArray jArgs, jobjectArray jParamClasses, jclass jReturnClass)
{
    PyObject* pyObject;
    PyObject* pyReturnValue;
    jobject jReturnValue;

    JPy_BEGIN_GIL_STATE

    pyObject = (PyObject*) objId;

    pyReturnValue = PyLib_CallAndReturnObject(jenv, pyObject, isMethodCall, jName, argCount, jArgs, jParamClasses);
    if (pyReturnValue == NULL) {
        jReturnValue = NULL;
        goto error;
    }

    if (JPy_AsJObjectWithClass(jenv, pyReturnValue, &jReturnValue, jReturnClass) < 0) {
        JPy_DIAG_PRINT(JPy_DIAG_F_ALL, "Java_org_jpy_PyLib_callAndReturnValue: error: failed to convert attribute value\n");
        PyLib_HandlePythonException(jenv);
        Py_DECREF(pyReturnValue);
        jReturnValue = NULL;
        goto error;
    }

error:
    JPy_END_GIL_STATE

    return jReturnValue;
}


/*
 * Class:     org_jpy_python_PyLib
 * Method:    getDiagFlags
 * Signature: ()I
 */
JNIEXPORT jint JNICALL Java_org_jpy_PyLib_00024Diag_getFlags
  (JNIEnv *jenv, jclass classRef)
{
    return JPy_DiagFlags;
}

/*
 * Class:     org_jpy_python_PyLib
 * Method:    setDiagFlags
 * Signature: (I)V
 */
JNIEXPORT void JNICALL Java_org_jpy_PyLib_00024Diag_setFlags
  (JNIEnv *jenv, jclass classRef, jint flags)
{
    JPy_DiagFlags = flags;
}

////////////////////////////////////////////////////////////////////////////////////
// Helpers that also throw Java exceptions


PyObject* PyLib_GetAttributeObject(JNIEnv* jenv, PyObject* pyObject, jstring jName)
{
    PyObject* pyValue;
    const char* nameChars;

    nameChars = (*jenv)->GetStringUTFChars(jenv, jName, NULL);
    JPy_DIAG_PRINT(JPy_DIAG_F_EXEC, "PyLib_GetAttributeObject: objId=%p, name='%s'\n", pyObject, nameChars);
    /* Note: pyValue is a new reference */
    pyValue = PyObject_GetAttrString(pyObject, nameChars);
    if (pyValue == NULL) {
        JPy_DIAG_PRINT(JPy_DIAG_F_ALL, "PyLib_GetAttributeObject: error: attribute not found '%s'\n", nameChars);
        PyLib_HandlePythonException(jenv);
    }
    (*jenv)->ReleaseStringUTFChars(jenv, jName, nameChars);
    return pyValue;
}

PyObject* PyLib_CallAndReturnObject(JNIEnv *jenv, PyObject* pyObject, jboolean isMethodCall, jstring jName, jint argCount, jobjectArray jArgs, jobjectArray jParamClasses)
{
    PyObject* pyCallable;
    PyObject* pyArgs;
    PyObject* pyArg;
    PyObject* pyReturnValue;
    const char* nameChars;
    jint i;
    jobject jArg;
    jclass jParamClass;
    JPy_JType* paramType;

    pyReturnValue = NULL;

    nameChars = (*jenv)->GetStringUTFChars(jenv, jName, NULL);

    JPy_DIAG_PRINT(JPy_DIAG_F_EXEC, "PyLib_CallAndReturnObject: objId=%p, isMethodCall=%d, name='%s', argCount=%d\n", pyObject, isMethodCall, nameChars, argCount);

    pyArgs = NULL;

    // Note: pyCallable is a new reference
    pyCallable = PyObject_GetAttrString(pyObject, nameChars);
    if (pyCallable == NULL) {
        JPy_DIAG_PRINT(JPy_DIAG_F_ALL, "PyLib_CallAndReturnObject: error: function or method not found: '%s'\n", nameChars);
        PyLib_HandlePythonException(jenv);
        goto error;
    }

    if (!PyCallable_Check(pyCallable)) {
        JPy_DIAG_PRINT(JPy_DIAG_F_ALL, "PyLib_CallAndReturnObject: error: object is not callable: '%s'\n", nameChars);
        PyLib_HandlePythonException(jenv);
        goto error;
    }

    pyArgs = PyTuple_New(argCount);
    for (i = 0; i < argCount; i++) {
        jArg = (*jenv)->GetObjectArrayElement(jenv, jArgs, i);

        if (jParamClasses != NULL) {
            jParamClass = (*jenv)->GetObjectArrayElement(jenv, jParamClasses, i);
        } else {
            jParamClass = NULL;
        }

        if (jParamClass != NULL) {
            paramType = JType_GetType(jenv, jParamClass, JNI_FALSE);
            if (paramType == NULL) {
                JPy_DIAG_PRINT(JPy_DIAG_F_ALL, "PyLib_CallAndReturnObject: error: callable '%s': argument %d: failed to retrieve type\n", nameChars, i);
                PyLib_HandlePythonException(jenv);
                goto error;
            }
            pyArg = JPy_FromJObjectWithType(jenv, jArg, paramType);
            (*jenv)->DeleteLocalRef(jenv, jParamClass);
        } else {
            pyArg = JPy_FromJObject(jenv, jArg);
        }

        (*jenv)->DeleteLocalRef(jenv, jArg);

        if (pyArg == NULL) {
            JPy_DIAG_PRINT(JPy_DIAG_F_ALL, "PyLib_CallAndReturnObject: error: callable '%s': argument %d: failed to convert Java into Python object\n", nameChars, i);
            PyLib_HandlePythonException(jenv);
            goto error;
        }

        // pyArg reference stolen here
        PyTuple_SetItem(pyArgs, i, pyArg);
    }

    // Check why: for some reason, we don't need the following code to invoke object methods.
    /*
    if (isMethodCall) {
        PyObject* pyMethod;

        pyMethod = PyMethod_New(pyCallable, pyObject);
        if (pyMethod == NULL) {
            JPy_DIAG_PRINT(JPy_DIAG_F_EXEC, "PyLib_CallAndReturnObject: error: callable '%s': no memory\n", nameChars);
            PyLib_HandlePythonException(jenv);
            goto error;
        }
        Py_DECREF(pyCallable);
        pyCallable = pyMethod;
    }
    */

    pyReturnValue = PyObject_CallObject(pyCallable, argCount > 0 ? pyArgs : NULL);
    if (pyReturnValue == NULL) {
        JPy_DIAG_PRINT(JPy_DIAG_F_ALL, "PyLib_CallAndReturnObject: error: callable '%s': call returned NULL\n", nameChars);
        PyLib_HandlePythonException(jenv);
        goto error;
    }

    Py_INCREF(pyReturnValue);

error:
    (*jenv)->ReleaseStringUTFChars(jenv, jName, nameChars);
    Py_XDECREF(pyCallable);
    Py_XDECREF(pyArgs);

    return pyReturnValue;
}

#if defined(JPY_COMPAT_33P)

char* PyLib_ObjToChars(PyObject* pyObj, PyObject** pyNewRef)
{
    char* chars = NULL;
    if (pyObj != NULL) {
        PyObject* pyObjStr = PyObject_Str(pyObj);
        if (pyObjStr != NULL) {
            PyObject* pyObjUtf8 = PyUnicode_AsEncodedString(pyObjStr, "utf-8", "replace");
            if (pyObjUtf8 != NULL) {
                chars = PyBytes_AsString(pyObjUtf8);
                *pyNewRef = pyObjUtf8;
            }
            Py_XDECREF(pyObjStr);
        }
    }
    return chars;
}

#elif defined(JPY_COMPAT_27)

char* PyLib_ObjToChars(PyObject* pyObj, PyObject** pyNewRef)
{
    char* chars = NULL;
    if (pyObj != NULL) {
        PyObject* pyObjStr = PyObject_Str(pyObj);
        if (pyObjStr != NULL) {
            chars = PyBytes_AsString(pyObjStr);
            *pyNewRef = pyObjStr;
        }
    }
    return chars;
}

#else

#error JPY_VERSION_ERROR

#endif

#define JPY_NOT_AVAILABLE_MSG "<not available>"
#define JPY_NOT_AVAILABLE_MSG_LEN strlen(JPY_NOT_AVAILABLE_MSG)
#define JPY_ERR_BASE_MSG "Error in Python interpreter"
#define JPY_NO_INFO_MSG JPY_ERR_BASE_MSG ", no information available"
#define JPY_INFO_ALLOC_FAILED_MSG JPY_ERR_BASE_MSG ", failed to allocate information text"

void PyLib_HandlePythonException(JNIEnv* jenv)
{
    PyObject* pyType = NULL;
    PyObject* pyValue = NULL;
    PyObject* pyTraceback = NULL;

    PyObject* pyTypeUtf8 = NULL;
    PyObject* pyValueUtf8 = NULL;
    PyObject* pyLinenoUtf8 = NULL;
    PyObject* pyFilenameUtf8 = NULL;
    PyObject* pyNamespaceUtf8 = NULL;
    char* typeChars = NULL;
    char* valueChars = NULL;
    char* linenoChars = NULL;
    char* filenameChars = NULL;
    char* namespaceChars = NULL;

    if (PyErr_Occurred() == NULL) {
        return;
    }

    PyErr_Fetch(&pyType, &pyValue, &pyTraceback);
    //printf("M1: pyType=%p, pyValue=%p, pyTraceback=%p\n", pyType, pyValue, pyTraceback);
    //printf("U1: pyType=%s, pyValue=%s, pyTraceback=%s\n", Py_TYPE(pyType)->tp_name, Py_TYPE(pyValue)->tp_name, pyTraceback != NULL ? Py_TYPE(pyTraceback)->tp_name : "?");
    PyErr_NormalizeException(&pyType, &pyValue, &pyTraceback);
    //printf("M2: pyType=%p, pyValue=%p, pyTraceback=%p\n", pyType, pyValue, pyTraceback);
    //printf("U2: pyType=%s, pyValue=%s, pyTraceback=%s\n", Py_TYPE(pyType)->tp_name, Py_TYPE(pyValue)->tp_name, pyTraceback != NULL ? Py_TYPE(pyTraceback)->tp_name : "?");

    typeChars = PyLib_ObjToChars(pyType, &pyTypeUtf8);
    valueChars = PyLib_ObjToChars(pyValue, &pyValueUtf8);

    if (pyTraceback != NULL) {
        PyObject* pyFrame = NULL;
        PyObject* pyCode = NULL;
        linenoChars = PyLib_ObjToChars(PyObject_GetAttrString(pyTraceback, "tb_lineno"), &pyLinenoUtf8);
        pyFrame = PyObject_GetAttrString(pyTraceback, "tb_frame");
        if (pyFrame != NULL) {
            pyCode = PyObject_GetAttrString(pyFrame, "f_code");
            if (pyCode != NULL) {
                filenameChars = PyLib_ObjToChars(PyObject_GetAttrString(pyCode, "co_filename"), &pyFilenameUtf8);
                namespaceChars = PyLib_ObjToChars(PyObject_GetAttrString(pyCode, "co_name"), &pyNamespaceUtf8);
            }
        }
        Py_XDECREF(pyCode);
        Py_XDECREF(pyFrame);
    }

    //printf("U2: typeChars=%s, valueChars=%s, linenoChars=%s, filenameChars=%s, namespaceChars=%s\n",
    //       typeChars, valueChars, linenoChars, filenameChars, namespaceChars);

    if (typeChars != NULL || valueChars != NULL
        || linenoChars != NULL || filenameChars != NULL || namespaceChars != NULL) {
        char* javaMessage;
        javaMessage = PyMem_New(char,
                                (typeChars != NULL ? strlen(typeChars) : JPY_NOT_AVAILABLE_MSG_LEN)
                               + (valueChars != NULL ? strlen(valueChars) : JPY_NOT_AVAILABLE_MSG_LEN)
                               + (linenoChars != NULL ? strlen(linenoChars) : JPY_NOT_AVAILABLE_MSG_LEN)
                               + (filenameChars != NULL ? strlen(filenameChars) : JPY_NOT_AVAILABLE_MSG_LEN)
                               + (namespaceChars != NULL ? strlen(namespaceChars) : JPY_NOT_AVAILABLE_MSG_LEN) + 80);
        if (javaMessage != NULL) {
            sprintf(javaMessage,
                    JPY_ERR_BASE_MSG ":\n"
                    "Type: %s\n"
                    "Value: %s\n"
                    "Line: %s\n"
                    "Namespace: %s\n"
                    "File: %s",
                    typeChars != NULL ? typeChars : JPY_NOT_AVAILABLE_MSG,
                    valueChars != NULL ? valueChars : JPY_NOT_AVAILABLE_MSG,
                    linenoChars != NULL ? linenoChars : JPY_NOT_AVAILABLE_MSG,
                    namespaceChars != NULL ? namespaceChars : JPY_NOT_AVAILABLE_MSG,
                    filenameChars != NULL ? filenameChars : JPY_NOT_AVAILABLE_MSG);
            (*jenv)->ThrowNew(jenv, JPy_RuntimeException_JClass, javaMessage);
            PyMem_Del(javaMessage);
        } else {
            (*jenv)->ThrowNew(jenv, JPy_RuntimeException_JClass, JPY_INFO_ALLOC_FAILED_MSG);
        }
    } else {
        (*jenv)->ThrowNew(jenv, JPy_RuntimeException_JClass, JPY_NO_INFO_MSG);
    }

    Py_XDECREF(pyType);
    Py_XDECREF(pyValue);
    Py_XDECREF(pyTraceback);
    Py_XDECREF(pyTypeUtf8);
    Py_XDECREF(pyValueUtf8);
    Py_XDECREF(pyLinenoUtf8);
    Py_XDECREF(pyFilenameUtf8);
    Py_XDECREF(pyNamespaceUtf8);

    PyErr_Clear();
}

////////////////////////////////////////////////////////////////////////////////////////////////
// Redirect stdout

static PyObject* JPrint_write(PyObject* self, PyObject* args)
{
    if (stdout != NULL) {
        const char* text;
        if (!PyArg_ParseTuple(args, "s", &text)) {
            return NULL;
        }
        fprintf(stdout, "%s", text);
    }
    return Py_BuildValue("");
}

static PyObject* JPrint_flush(PyObject* self, PyObject* args)
{
    if (stdout != NULL) {
        fflush(stdout);
    }
    return Py_BuildValue("");
}

static PyMethodDef JPrint_Functions[] = {

    {"write",       (PyCFunction) JPrint_write, METH_VARARGS,
                    "Internal function. Used to print to stdout in embedded mode."},

    {"flush",       (PyCFunction) JPrint_flush, METH_VARARGS,
                    "Internal function. Used to flush to stdout in embedded mode."},

    {NULL, NULL, 0, NULL} /*Sentinel*/
};

#define JPY_STDOUT_MODULE_NAME "jpy_stdout"
#define JPY_STDOUT_MODULE_DOC  "Redirect 'stdout' to the console in embedded mode"

#if defined(JPY_COMPAT_33P)
static struct PyModuleDef JPrint_ModuleDef =
{
    PyModuleDef_HEAD_INIT,
    JPY_STDOUT_MODULE_NAME, /* Name of the Python JPy_Module */
    JPY_STDOUT_MODULE_DOC,  /* Module documentation */
    -1,                 /* Size of per-interpreter state of the JPy_Module, or -1 if the JPy_Module keeps state in global variables. */
    JPrint_Functions,    /* Structure containing global jpy-functions */
    NULL,     // m_reload
    NULL,     // m_traverse
    NULL,     // m_clear
    NULL      // m_free
};
#endif

void PyLib_RedirectStdOut(void)
{
    PyObject* module;
#if defined(JPY_COMPAT_33P)
    module = PyModule_Create(&JPrint_ModuleDef);
#elif defined(JPY_COMPAT_27)
    module = Py_InitModule3(JPY_STDOUT_MODULE_NAME, JPrint_Functions, JPY_STDOUT_MODULE_DOC);
#else
    #error JPY_VERSION_ERROR
#endif
    PySys_SetObject("stdout", module);
    PySys_SetObject("stderr", module);
}



