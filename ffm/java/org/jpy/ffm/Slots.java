package org.jpy.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Array;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.jpy.ffm.CPython.NULL;

/**
 * Type slots implemented in Java, exposed to CPython as FFM upcall stubs.
 *
 * Rule for every method here: nothing may be thrown out of it. An exception
 * escaping an upcall stub terminates the JVM. Failures set the Python error
 * indicator and return the slot's error value (NULL, -1).
 */
final class Slots {

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private static MemorySegment stub(String method, FunctionDescriptor fd) {
        try {
            return Linker.nativeLinker().upcallStub(
                    LOOKUP.findStatic(Slots.class, method, fd.toMethodType()), fd, Arena.global());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- stubs for Java object types (JObj_* in the C jpy) ----

    static final MemorySegment JOBJ_INIT = stub("jobjInit", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    static final MemorySegment JOBJ_DEALLOC = stub("jobjDealloc", FunctionDescriptor.ofVoid(ADDRESS));
    static final MemorySegment JOBJ_GETATTRO = stub("jobjGetattro", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    static final MemorySegment JOBJ_SETATTRO = stub("jobjSetattro", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    static final MemorySegment JOBJ_RICHCOMPARE = stub("jobjRichcompare", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
    static final MemorySegment JOBJ_HASH = stub("jobjHash", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    static final MemorySegment JOBJ_REPR = stub("jobjRepr", FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MemorySegment JOBJ_STR = stub("jobjStr", FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MemorySegment JOBJ_SQ_LENGTH = stub("jobjSqLength", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    static final MemorySegment JOBJ_SQ_ITEM = stub("jobjSqItem", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
    static final MemorySegment JOBJ_SQ_ASS_ITEM = stub("jobjSqAssItem", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS));

    // ---- stubs for jpy.JOverloadedMethod and jpy.JField ----

    static final MemorySegment OM_CALL = stub("omCall", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    static final MemorySegment OM_REPR = stub("omRepr", FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MemorySegment OM_STR = stub("omStr", FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MemorySegment OM_DEALLOC = stub("omDealloc", FunctionDescriptor.ofVoid(ADDRESS));
    static final MemorySegment FIELD_REPR = stub("fieldRepr", FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MemorySegment FIELD_DEALLOC = stub("fieldDealloc", FunctionDescriptor.ofVoid(ADDRESS));

    static MemorySegment overloadedMethodType;
    static MemorySegment fieldType;

    /** Creates jpy.JType, jpy.JOverloadedMethod and jpy.JField and adds them to the module. */
    static void createModuleTypes(MemorySegment module) {
        long noNew = CPython.Py_TPFLAGS_DISALLOW_INSTANTIATION;
        JTypes.rootType = JTypes.fromSpec("jpy.JType", CPython.Py_TPFLAGS_BASETYPE | noNew, List.of(), null);
        overloadedMethodType = JTypes.fromSpec("jpy.JOverloadedMethod", noNew, List.of(
                new long[]{CPython.Py_tp_call, OM_CALL.address()},
                new long[]{CPython.Py_tp_repr, OM_REPR.address()},
                new long[]{CPython.Py_tp_str, OM_STR.address()},
                new long[]{CPython.Py_tp_dealloc, OM_DEALLOC.address()}), null);
        fieldType = JTypes.fromSpec("jpy.JField", noNew, List.of(
                new long[]{CPython.Py_tp_repr, FIELD_REPR.address()},
                new long[]{CPython.Py_tp_dealloc, FIELD_DEALLOC.address()}), null);
        CPython.setAttr(module, "JType", JTypes.rootType);
        CPython.setAttr(module, "JOverloadedMethod", overloadedMethodType);
        CPython.setAttr(module, "JField", fieldType);
    }

    // ------------------------------------------------------------------
    // Java object slots
    // ------------------------------------------------------------------

    /** JObj_init: run the best-matching public constructor ("__jinit__"). */
    static int jobjInit(MemorySegment self, MemorySegment args, MemorySegment kwds) {
        try {
            JavaType jt = JObjects.javaTypeOf(self);
            OverloadSet ctors = jt != null ? jt.constructors : null;
            if (ctors == null) {
                throw CPython.runtimeError("no constructor found (missing JType attribute '__jinit__')");
            }
            OverloadSet.Found f = ctors.find(args, false);
            Object created = OverloadSet.construct(f.method(), args, f.isVarArgsArray());
            JObjects.put(self, created);
            return 0;
        } catch (Throwable t) {
            CPython.setPythonError(t);
            return -1;
        }
    }

    /** JObj_dealloc. Must not raise. */
    static void jobjDealloc(MemorySegment self) {
        try {
            JObjects.remove(self);
            CPython.freeHeapInstance(self);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    /** JObj_getattro: resolve the type on first use, bind methods, read fields. */
    static MemorySegment jobjGetattro(MemorySegment self, MemorySegment name) {
        try {
            JavaType jt = JObjects.javaTypeOf(self);
            if (jt != null && !jt.resolved) {
                JTypes.resolve(jt);
            }
            MemorySegment value = CPython.genericGetAttrOrNull(self, name);
            if (value.equals(NULL)) {
                return NULL;
            }
            if (OverloadSet.BY_PYOBJ.containsKey(value.address())) {
                try {
                    return CPython.bindMethod(value, self);
                } finally {
                    CPython.decRef(value);
                }
            }
            JFieldInfo field = JFieldInfo.BY_PYOBJ.get(value.address());
            if (field != null) {
                CPython.decRef(value);
                return field.get(requireJavaObject(self));
            }
            return value;
        } catch (Throwable t) {
            CPython.setPythonError(t);
            return NULL;
        }
    }

    /** JObj_setattro: writes to Java instance fields, everything else goes to the generic setter. */
    static int jobjSetattro(MemorySegment self, MemorySegment name, MemorySegment value) {
        try {
            MemorySegment old = CPython.genericGetAttrOrNull(self, name);
            if (old.equals(NULL)) {
                CPython.errClear();
            } else {
                JFieldInfo field = JFieldInfo.BY_PYOBJ.get(old.address());
                CPython.decRef(old);
                if (field != null) {
                    if (value.equals(NULL)) {
                        throw CPython.runtimeError("cannot delete Java field '" + field.name + "'");
                    }
                    field.set(requireJavaObject(self), value);
                    return 0;
                }
            }
            return CPython.genericSetAttr(self, name, value);
        } catch (Throwable t) {
            CPython.setPythonError(t);
            return -1;
        }
    }

    /** JObj_richcompare. Non-Java operands compare False for every operator, as in the C jpy. */
    static MemorySegment jobjRichcompare(MemorySegment a, MemorySegment b, int op) {
        try {
            Object oa = JObjects.get(a);
            Object ob = JObjects.get(b);
            if (oa == null || ob == null) {
                return CPython.newBool(false);
            }
            boolean result = switch (op) {
                case CPython.Py_LT -> compareTo(oa, ob) == -1;
                case CPython.Py_LE -> compareTo(oa, ob) <= 0;
                case CPython.Py_GT -> compareTo(oa, ob) == 1;
                case CPython.Py_GE -> compareTo(oa, ob) >= 0;
                case CPython.Py_EQ -> javaEquals(oa, ob);
                case CPython.Py_NE -> !javaEquals(oa, ob);
                default -> throw CPython.runtimeError("internal error: unrecognized opid");
            };
            return CPython.newBool(result);
        } catch (Throwable t) {
            CPython.setPythonError(t);
            return NULL;
        }
    }

    /** JObj_CompareTo: identity, then Comparable (exceptions swallowed), then identity order. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int compareTo(Object a, Object b) {
        int value;
        if (a == b) {
            return 0;
        } else if (a instanceof Comparable c) {
            try {
                value = c.compareTo(b);
            } catch (RuntimeException e) {
                value = 0;
            }
        } else {
            value = Integer.compare(System.identityHashCode(a), System.identityHashCode(b));
        }
        return Integer.signum(value);
    }

    /** JObj_Equals: identity or equals(), exceptions swallowed. */
    private static boolean javaEquals(Object a, Object b) {
        try {
            return a == b || a.equals(b);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** JObj_hash: hashCode(). -1 is CPython's error marker, so it becomes -2. */
    static long jobjHash(MemorySegment self) {
        try {
            int h = requireJavaObject(self).hashCode();
            return h == -1 ? -2 : h;
        } catch (Throwable t) {
            CPython.setPythonError(t);
            return -1;
        }
    }

    /** JObj_repr: "<tp_name>(objectRef=0x...)". */
    static MemorySegment jobjRepr(MemorySegment self) {
        try {
            Object o = JObjects.get(self);
            String ref = o == null ? "0x0" : "0x" + Integer.toHexString(System.identityHashCode(o));
            return CPython.newStr(CPython.typeName(self) + "(objectRef=" + ref + ")");
        } catch (Throwable t) {
            CPython.setPythonError(t);
            return NULL;
        }
    }

    /** JObj_str: toString(). */
    static MemorySegment jobjStr(MemorySegment self) {
        try {
            Object o = JObjects.get(self);
            if (o == null) {
                return CPython.none();
            }
            String s = o.toString();
            return s == null ? CPython.none() : CPython.newStr(s);
        } catch (Throwable t) {
            CPython.setPythonError(t);
            return NULL;
        }
    }

    /** JObj_sq_length (array types only). */
    static long jobjSqLength(MemorySegment self) {
        try {
            return Array.getLength(requireJavaObject(self));
        } catch (Throwable t) {
            CPython.setPythonError(t);
            return -1;
        }
    }

    /** JObj_sq_item (array types only): items convert with the component type as declared type. */
    static MemorySegment jobjSqItem(MemorySegment self, long index) {
        try {
            JavaType jt = arrayTypeOf(self);
            Object array = requireJavaObject(self);
            if (index < 0 || index >= Array.getLength(array)) {
                throw new CPython.PyRaise(CPython.PyExc_IndexError, "Java array index out of bounds");
            }
            return Convert.toPython(Array.get(array, (int) index), jt.componentType.clazz);
        } catch (Throwable t) {
            CPython.setPythonError(t);
            return NULL;
        }
    }

    /** JObj_sq_ass_item (array types only). */
    static int jobjSqAssItem(MemorySegment self, long index, MemorySegment value) {
        try {
            JavaType jt = arrayTypeOf(self);
            if (value.equals(NULL)) {
                throw CPython.runtimeError("cannot delete items of Java arrays");
            }
            Class<?> component = jt.componentType.clazz;
            Object item = component.isPrimitive()
                    ? Convert.toJavaArg(value, component)
                    : Convert.toJavaObject(value, component, false);
            try {
                Array.set(requireJavaObject(self), (int) index, item);
            } catch (RuntimeException e) {
                throw JavaErrors.toPython(e);
            }
            return 0;
        } catch (Throwable t) {
            CPython.setPythonError(t);
            return -1;
        }
    }

    private static JavaType arrayTypeOf(MemorySegment self) {
        JavaType jt = JObjects.javaTypeOf(self);
        if (jt == null || jt.componentType == null) {
            throw CPython.runtimeError("internal error: object is not an array");
        }
        return jt;
    }

    private static Object requireJavaObject(MemorySegment self) {
        Object o = JObjects.get(self);
        if (o == null) {
            throw CPython.runtimeError("Java object is not initialized");
        }
        return o;
    }

    // ------------------------------------------------------------------
    // jpy.JOverloadedMethod and jpy.JField slots
    // ------------------------------------------------------------------

    static MemorySegment omCall(MemorySegment self, MemorySegment args, MemorySegment kwargs) {
        try {
            OverloadSet os = OverloadSet.BY_PYOBJ.get(self.address());
            if (os == null) {
                throw CPython.runtimeError("internal error: unknown jpy.JOverloadedMethod");
            }
            return os.call(args);
        } catch (Throwable t) {
            CPython.setPythonError(t);
            return NULL;
        }
    }

    static MemorySegment omRepr(MemorySegment self) {
        try {
            OverloadSet os = OverloadSet.BY_PYOBJ.get(self.address());
            return CPython.newStr(os == null ? "jpy.JOverloadedMethod(?)" : os.repr());
        } catch (Throwable t) {
            CPython.setPythonError(t);
            return NULL;
        }
    }

    /** JOverloadedMethod_str: the method name. */
    static MemorySegment omStr(MemorySegment self) {
        try {
            OverloadSet os = OverloadSet.BY_PYOBJ.get(self.address());
            return CPython.newStr(os == null ? "?" : os.name);
        } catch (Throwable t) {
            CPython.setPythonError(t);
            return NULL;
        }
    }

    static void omDealloc(MemorySegment self) {
        try {
            OverloadSet.BY_PYOBJ.remove(self.address());
            CPython.freeHeapInstance(self);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    static MemorySegment fieldRepr(MemorySegment self) {
        try {
            JFieldInfo f = JFieldInfo.BY_PYOBJ.get(self.address());
            String s = f == null ? "jpy.JField(?)"
                    : "jpy.JField(class='" + f.declaringType.name + "', name='" + f.name + "')";
            return CPython.newStr(s);
        } catch (Throwable t) {
            CPython.setPythonError(t);
            return NULL;
        }
    }

    static void fieldDealloc(MemorySegment self) {
        try {
            JFieldInfo.BY_PYOBJ.remove(self.address());
            CPython.freeHeapInstance(self);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private Slots() {
    }
}
