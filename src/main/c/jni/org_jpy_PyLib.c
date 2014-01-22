#include <jni.h>
#include <Python.h>

#include "jpy_module.h"
#include "jpy_diag.h"
#include "jpy_jtype.h"
#include "jpy_jobj.h"
#include "jpy_conv.h"

#include "org_jpy_PyLib.h"
#include "org_jpy_PyLib_Diag.h"

PyObject* PyLib_GetAttributeObject(JNIEnv* jenv, PyObject* pyValue, jstring jName);
PyObject* PyLib_CallAndReturnObject(JNIEnv *jenv, PyObject* pyValue, jboolean isMethodCall, jstring jName, jint argCount, jobjectArray jArgs, jobjectArray jParamClasses);

/**
 * Fetches the last Python exception occurred and raises a new Java exception.
 */
void JPy_HandlePythonException(JNIEnv* jenv);



// Note code in this file is formatted according to the header generated by javah. This makes it easier
// to follow up changes in the header.

/*
 * Class:     org_jpy_PyLib
 * Method:    isPythonRunning
 * Signature: ()Z
 */
JNIEXPORT jboolean JNICALL Java_org_jpy_PyLib_isPythonRunning
  (JNIEnv* jenv, jclass jLibClass)
{
    int retCode;
    retCode = Py_IsInitialized();
    JPy_DIAG_PRINT(JPy_DIAG_F_EXEC, "Java_org_jpy_PyLib_isPythonStarted: retCode=%d, JPy_Module=%p\n", retCode, JPy_Module);
    return retCode != 0 && JPy_Module != NULL;
}

/*
 * Class:     org_jpy_PyLib
 * Method:    startPython
 * Signature: ([Ljava/lang/String;)V
 */
JNIEXPORT void JNICALL Java_org_jpy_PyLib_startPython
  (JNIEnv* jenv, jclass jLibClass, jobjectArray options)
{
    JPy_DIAG_PRINT(JPy_DIAG_F_EXEC, "PyLib_startPython: entered\n");

    if (!Py_IsInitialized()) {
        Py_SetProgramName(L"java");
        Py_Initialize();
    }

    // if JPy_Module is still NULL, then the 'jpy' module has not been imported yet.
    //
    if (JPy_Module == NULL) {
        PyObject* pyModule;

        // We import 'jpy' so that Python can call our PyInit_jpy() which sets up a number of
        // required global variables (including JPy_Module, see above).
        //
        pyModule = PyImport_ImportModule("jpy");
        if (pyModule == NULL) {
            JPy_DIAG_PRINT(JPy_DIAG_F_ALL, "PyLib_startPython: failed to import module 'jpy'\n");
            if (JPy_DiagFlags != 0 && PyErr_Occurred()) {
                PyErr_Print();
            }
            JPy_HandlePythonException(jenv);
        }
    }

    JPy_DIAG_PRINT(JPy_DIAG_F_EXEC, "PyLib_startPython: exiting\n");
}
  

/*
 * Class:     org_jpy_PyLib
 * Method:    stopPython
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_org_jpy_PyLib_stopPython
  (JNIEnv* jenv, jclass jLibClass)
{
    JPy_DIAG_PRINT(JPy_DIAG_F_EXEC, "Java_org_jpy_PyLib_stopPython: entered\n");

    if (Py_IsInitialized()) {
        Py_Finalize();
    }

    JPy_DIAG_PRINT(JPy_DIAG_F_EXEC, "Java_org_jpy_PyLib_stopPython: exiting\n");
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

    scriptChars = (*jenv)->GetStringUTFChars(jenv, jScript, NULL);
    JPy_DIAG_PRINT(JPy_DIAG_F_EXEC, "Java_org_jpy_PyLib_execScript: script='%s'\n", scriptChars);
    retCode = PyRun_SimpleString(scriptChars);
    if (retCode < 0) {
        JPy_DIAG_PRINT(JPy_DIAG_F_ALL, "Java_org_jpy_PyLib_execScript: error: PyRun_SimpleString(\"%s\") returned %d\n", scriptChars, retCode);
        // Note that we cannot retrieve last Python exception after a calling PyRun_SimpleString, see documentation of PyRun_SimpleString.
    }
    (*jenv)->ReleaseStringUTFChars(jenv, jScript, scriptChars);
    return retCode;
}


/*
 * Class:     org_jpy_python_PyLib
 * Method:    decref
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_org_jpy_PyLib_decref
  (JNIEnv* jenv, jclass jLibClass, jlong objId)
{
    JPy_DIAG_PRINT(JPy_DIAG_F_MEM, "Java_org_jpy_PyLib_decref: objId=%p\n", (PyObject*) objId);
    Py_XDECREF((PyObject*) objId);
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
    pyObject = (PyObject*) objId;
    return (jint) PyLong_AsLong(pyObject);
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
    pyObject = (PyObject*) objId;
    return (jdouble) PyFloat_AsDouble(pyObject);
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

    pyObject = (PyObject*) objId;

    if (JPy_AsJString(jenv, pyObject, &jString) < 0) {
        JPy_DIAG_PRINT(JPy_DIAG_F_ALL, "Java_org_jpy_PyLib_getStringValue: error: failed to convert Python object to Java String\n");
        JPy_HandlePythonException(jenv);
        return NULL;
    }

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

    pyObject = (PyObject*) objId;

    if (JObj_Check(pyObject)) {
        jObject = ((JPy_JObj*) pyObject)->objectRef;
    } else {
        if (JPy_AsJObject(jenv, pyObject, &jObject) < 0) {
            JPy_DIAG_PRINT(JPy_DIAG_F_ALL, "Java_org_jpy_PyLib_getObjectValue: error: failed to convert Python object to Java Object\n");
            JPy_HandlePythonException(jenv);
            return NULL;
        }
    }

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

    nameChars = (*jenv)->GetStringUTFChars(jenv, jName, NULL);
    JPy_DIAG_PRINT(JPy_DIAG_F_EXEC, "Java_org_jpy_PyLib_importModule: name='%s'\n", nameChars);
    /* Note: pyName is a new reference */
    pyName = PyUnicode_FromString(nameChars);
    /* Note: pyModule is a new reference */
    pyModule = PyImport_Import(pyName);
    if (pyModule == NULL) {
        JPy_HandlePythonException(jenv);
    }
    Py_XDECREF(pyName);
    (*jenv)->ReleaseStringUTFChars(jenv, jName, nameChars);
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

    pyObject = (PyObject*) objId;
    pyValue = PyLib_GetAttributeObject(jenv, pyObject, jName);
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

    pyObject = (PyObject*) objId;

    pyValue = PyLib_GetAttributeObject(jenv, pyObject, jName);
    if (pyValue == NULL) {
        return NULL;
    }

    if (JPy_AsJObjectWithClass(jenv, pyValue, &jReturnValue, jValueClass) < 0) {
        JPy_DIAG_PRINT(JPy_DIAG_F_ALL, "Java_org_jpy_PyLib_getAttributeValue: error: failed to convert attribute value\n");
        JPy_HandlePythonException(jenv);
        return NULL;
    }

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
        JPy_HandlePythonException(jenv);
        goto error;
    }

    if (PyObject_SetAttrString(pyObject, nameChars, pyValue) < 0) {
        JPy_DIAG_PRINT(JPy_DIAG_F_ALL, "Java_org_jpy_PyLib_setAttributeValue: error: PyObject_SetAttrString failed on attribute '%s'\n", nameChars);
        JPy_HandlePythonException(jenv);
        goto error;
    }

error:
    (*jenv)->ReleaseStringUTFChars(jenv, jName, nameChars);
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

    pyObject = (PyObject*) objId;
    pyReturnValue = PyLib_CallAndReturnObject(jenv, pyObject, isMethodCall, jName, argCount, jArgs, jParamClasses);
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

    pyObject = (PyObject*) objId;

    pyReturnValue = PyLib_CallAndReturnObject(jenv, pyObject, isMethodCall, jName, argCount, jArgs, jParamClasses);
    if (pyReturnValue == NULL) {
        return NULL;
    }

    if (JPy_AsJObjectWithClass(jenv, pyReturnValue, &jReturnValue, jReturnClass) < 0) {
        JPy_DIAG_PRINT(JPy_DIAG_F_ALL, "Java_org_jpy_PyLib_callAndReturnValue: error: failed to convert attribute value\n");
        JPy_HandlePythonException(jenv);
        return NULL;
    }

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
        JPy_HandlePythonException(jenv);
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
        JPy_HandlePythonException(jenv);
        goto error;
    }

    if (!PyCallable_Check(pyCallable)) {
        JPy_DIAG_PRINT(JPy_DIAG_F_ALL, "PyLib_CallAndReturnObject: error: object is not callable: '%s'\n", nameChars);
        JPy_HandlePythonException(jenv);
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
                JPy_HandlePythonException(jenv);
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
            JPy_HandlePythonException(jenv);
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
            JPy_HandlePythonException(jenv);
            goto error;
        }
        Py_DECREF(pyCallable);
        pyCallable = pyMethod;
    }
    */

    pyReturnValue = PyObject_CallObject(pyCallable, argCount > 0 ? pyArgs : NULL);
    if (pyReturnValue == NULL) {
        JPy_DIAG_PRINT(JPy_DIAG_F_ALL, "PyLib_CallAndReturnObject: error: callable '%s': call returned NULL\n", nameChars);
        JPy_HandlePythonException(jenv);
        goto error;
    }

    Py_INCREF(pyReturnValue);

error:
    (*jenv)->ReleaseStringUTFChars(jenv, jName, nameChars);
    Py_XDECREF(pyCallable);
    Py_XDECREF(pyArgs);

    return pyReturnValue;
}


void JPy_HandlePythonException(JNIEnv* jenv)
{
    PyObject* pyType;
    PyObject* pyValue;
    PyObject* pyTraceback;

    PyObject* pyTypeStr;
    PyObject* pyValueStr;
    PyObject* pyTracebackStr;

    PyObject* pyTypeUtf8;
    PyObject* pyValueUtf8;
    PyObject* pyTracebackUtf8;

    char* typeChars;
    char* valueChars;
    char* tracebackChars;
    char* javaMessage;

    jint ret;

    //printf("JPy_HandlePythonException 0: jenv=%p\n", jenv);

    if (PyErr_Occurred() == NULL) {
        return;
    }

    // todo - The traceback string generated here does not make sense. Convert it to the actual traceback that Python prints to stderr.

    PyErr_Fetch(&pyType, &pyValue, &pyTraceback);
    PyErr_NormalizeException(&pyType, &pyValue, &pyTraceback);

    //printf("JPy_HandlePythonException 1: %p, %p, %p\n", pyType, pyValue, pyTraceback);

    pyTypeStr = pyType != NULL ? PyObject_Str(pyType) : NULL;
    pyValueStr = pyValue != NULL ? PyObject_Str(pyValue) : NULL;
    pyTracebackStr = pyTraceback != NULL ? PyObject_Str(pyTraceback) : NULL;

    //printf("JPy_HandlePythonException 2: %p, %p, %p\n", pyTypeStr, pyValueStr, pyTracebackStr);

    pyTypeUtf8 = pyTypeStr != NULL ? PyUnicode_AsEncodedString(pyTypeStr, "utf-8", "replace") : NULL;
    pyValueUtf8 = pyValueStr != NULL ? PyUnicode_AsEncodedString(pyValueStr, "utf-8", "replace") : NULL;
    pyTracebackUtf8 = pyTracebackStr != NULL ? PyUnicode_AsEncodedString(pyTracebackStr, "utf-8", "replace") : NULL;

    //printf("JPy_HandlePythonException 3: %p, %p, %p\n", pyTypeUtf8, pyValueUtf8, pyTracebackUtf8);

    typeChars = pyTypeUtf8 != NULL ? PyBytes_AsString(pyTypeUtf8) : NULL;
    valueChars = pyValueUtf8 != NULL ? PyBytes_AsString(pyValueUtf8) : NULL;
    tracebackChars = pyTracebackUtf8 != NULL ? PyBytes_AsString(pyTracebackUtf8) : NULL;

    //printf("JPy_HandlePythonException 4: %s, %s, %s\n", typeChars, valueChars, tracebackChars);

    javaMessage = PyMem_New(char,
                            (typeChars != NULL ? strlen(typeChars) : 8)
                           + (valueChars != NULL ? strlen(valueChars) : 8)
                           + (tracebackChars != NULL ? strlen(tracebackChars) : 8) + 80);
    if (javaMessage != NULL) {
        if (tracebackChars != NULL) {
            sprintf(javaMessage, "Python error: %s: %s\nTraceback: %s", typeChars, valueChars, tracebackChars);
        } else {
            sprintf(javaMessage, "Python error: %s: %s", typeChars, valueChars);
        }
        //printf("JPy_HandlePythonException 4a: JPy_RuntimeException_JClass=%p, javaMessage=\"%s\"\n", JPy_RuntimeException_JClass, javaMessage);
        ret = (*jenv)->ThrowNew(jenv, JPy_RuntimeException_JClass, javaMessage);
    } else {
        //printf("JPy_HandlePythonException 4b: JPy_RuntimeException_JClass=%p, valueChars=\"%s\"\n", JPy_RuntimeException_JClass, valueChars);
        ret = (*jenv)->ThrowNew(jenv, JPy_RuntimeException_JClass, valueChars);
    }

    PyMem_Del(javaMessage);

    Py_XDECREF(pyType);
    Py_XDECREF(pyValue);
    Py_XDECREF(pyTraceback);

    Py_XDECREF(pyTypeStr);
    Py_XDECREF(pyValueStr);
    Py_XDECREF(pyTracebackStr);

    Py_XDECREF(pyTypeUtf8);
    Py_XDECREF(pyValueUtf8);
    Py_XDECREF(pyTracebackUtf8);

    //printf("JPy_HandlePythonException 5: ret=%d\n", ret);

    PyErr_Clear();
}



