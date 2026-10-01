package org.jpy.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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
    static final MemorySegment ARRAY_GETBUFFER = stub("arrayGetbuffer", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
    static final MemorySegment ARRAY_RELEASEBUFFER = stub("arrayReleasebuffer", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

    // ---- stubs for jpy.JOverloadedMethod and jpy.JField ----

    static final MemorySegment OM_CALL = stub("omCall", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    static final MemorySegment OM_REPR = stub("omRepr", FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MemorySegment OM_STR = stub("omStr", FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MemorySegment OM_DEALLOC = stub("omDealloc", FunctionDescriptor.ofVoid(ADDRESS));
    static final MemorySegment FIELD_REPR = stub("fieldRepr", FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MemorySegment FIELD_DEALLOC = stub("fieldDealloc", FunctionDescriptor.ofVoid(ADDRESS));
    static final MemorySegment FIELD_STR = stub("fieldStr", FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MemorySegment FIELD_GET = stub("fieldGet", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

    // ---- stubs for jpy.JTypeMeta, the metaclass of all Java types ----

    static final MemorySegment META_GETATTRO = stub("metaGetattro", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    static final MemorySegment META_SETATTRO = stub("metaSetattro", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    // ---- stubs for jpy.JMethod and the attributes of jpy.JOverloadedMethod ----

    private static final FunctionDescriptor GETTER = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor VARARGS = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS);
    static final MemorySegment JM_REPR = stub("jmRepr", FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MemorySegment JM_STR = stub("jmStr", FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MemorySegment JM_GET = stub("jmGet", GETTER);
    static final MemorySegment OM_GET = stub("omGet", GETTER);

    static MemorySegment metaType;
    static MemorySegment overloadedMethodType;
    static MemorySegment fieldType;
    static MemorySegment methodType;

    /** Before Py_Finalize; see Bootstrap.uninstall. */
    static void reset() {
        metaType = overloadedMethodType = fieldType = methodType = null;
    }

    /** PyGetSetDef[] { name, get, set, doc, closure } + sentinel; closure carries an attribute id. */
    private static MemorySegment getsets(MemorySegment getter, String... names) {
        Arena forever = Arena.global();
        MemorySegment defs = forever.allocate(40L * (names.length + 1), 8);
        for (int i = 0; i < names.length; i++) {
            long off = 40L * i;
            defs.set(ADDRESS, off, forever.allocateFrom(names[i]));
            defs.set(ADDRESS, off + 8, getter);
            defs.set(ADDRESS, off + 16, NULL);
            defs.set(ADDRESS, off + 24, NULL);
            defs.set(ADDRESS, off + 32, MemorySegment.ofAddress(i));
        }
        return defs;
    }

    /** A tp_doc string; PyType_FromSpec copies it. */
    private static long doc(String text) {
        return Arena.global().allocateFrom(text).address();
    }

    /** PyMethodDef[] { name, meth, flags, doc } + sentinel, all METH_VARARGS. */
    private static MemorySegment methodDefs(String[] names, String[] javaMethods, String[] docs) {
        Arena forever = Arena.global();
        MemorySegment defs = forever.allocate(32L * (names.length + 1), 8);
        for (int i = 0; i < names.length; i++) {
            long off = 32L * i;
            defs.set(ADDRESS, off, forever.allocateFrom(names[i]));
            defs.set(ADDRESS, off + 8, stub(javaMethods[i], VARARGS));
            defs.set(JAVA_INT, off + 16, CPython.METH_VARARGS);
            defs.set(ADDRESS, off + 24, forever.allocateFrom(docs[i]));
        }
        return defs;
    }

    /** Creates jpy.JTypeMeta, jpy.JType, jpy.JOverloadedMethod and jpy.JField and adds them to the module. */
    static void createModuleTypes(MemorySegment module) {
        // Immutable like the C jpy's static types: setting an attribute on them raises TypeError.
        long fixed = CPython.Py_TPFLAGS_IMMUTABLETYPE;
        long noNew = CPython.Py_TPFLAGS_DISALLOW_INSTANTIATION | fixed;
        // A real heap subclass of `type`; Java types are created through PyType_FromMetaclass with it.
        metaType = JTypes.fromSpec("jpy.JTypeMeta", CPython.Py_TPFLAGS_BASETYPE | fixed, List.of(
                new long[]{CPython.Py_tp_getattro, META_GETATTRO.address()},
                new long[]{CPython.Py_tp_setattro, META_SETATTRO.address()}), CPython.PyType_Type);
        CPython.setAttr(module, "JTypeMeta", metaType);
        JTypes.rootType = JTypes.fromSpec("jpy.JType", CPython.Py_TPFLAGS_BASETYPE | noNew, List.of(
                new long[]{CPython.Py_tp_doc, doc("Java Meta Type")}), null);
        overloadedMethodType = JTypes.fromSpec("jpy.JOverloadedMethod", noNew, List.of(
                new long[]{CPython.Py_tp_doc, doc("Java Overloaded Method")},
                new long[]{CPython.Py_tp_call, OM_CALL.address()},
                new long[]{CPython.Py_tp_repr, OM_REPR.address()},
                new long[]{CPython.Py_tp_str, OM_STR.address()},
                new long[]{CPython.Py_tp_dealloc, OM_DEALLOC.address()},
                new long[]{CPython.Py_tp_getset, getsets(OM_GET, "decl_class", "name", "methods").address()}), null);
        methodType = JTypes.fromSpec("jpy.JMethod", noNew, List.of(
                new long[]{CPython.Py_tp_doc, doc("Java Method Wrapper")},
                new long[]{CPython.Py_tp_repr, JM_REPR.address()},
                new long[]{CPython.Py_tp_str, JM_STR.address()},
                new long[]{CPython.Py_tp_getset, getsets(JM_GET, "name", "param_count", "is_static").address()},
                new long[]{CPython.Py_tp_methods, methodDefs(
                        new String[]{"get_param_type", "is_param_mutable", "is_param_output", "is_param_return",
                                "set_param_mutable", "set_param_output", "set_param_return"},
                        new String[]{"jmGetParamType", "jmIsParamMutable", "jmIsParamOutput", "jmIsParamReturn",
                                "jmSetParamMutable", "jmSetParamOutput", "jmSetParamReturn"},
                        new String[]{"Gets the type of the parameter given by index",
                                "Tests if the method parameter given by index is mutable",
                                "Tests if the method parameter given by index is a mere output value (and not read from)",
                                "Tests if the method parameter given by index is the return value",
                                "Sets whether the method parameter given by index is mutable",
                                "Sets whether the method parameter given by index is a mere output value (and not read from)",
                                "Sets whether the method parameter given by index is the return value"}).address()}), null);
        CPython.setAttr(module, "JMethod", methodType);
        fieldType = JTypes.fromSpec("jpy.JField", noNew, List.of(
                new long[]{CPython.Py_tp_doc, doc("Java Field Wrapper")},
                new long[]{CPython.Py_tp_repr, FIELD_REPR.address()},
                new long[]{CPython.Py_tp_str, FIELD_STR.address()},
                new long[]{CPython.Py_tp_dealloc, FIELD_DEALLOC.address()},
                new long[]{CPython.Py_tp_getset, getsets(FIELD_GET, "name", "is_static", "is_final").address()}), null);
        CPython.setAttr(module, "JType", JTypes.rootType);
        CPython.setAttr(module, "JOverloadedMethod", overloadedMethodType);
        CPython.setAttr(module, "JField", fieldType);
    }

    // ------------------------------------------------------------------
    // jpy.JTypeMeta slots (class-level attribute access on Java types)
    // ------------------------------------------------------------------

    /**
     * T.name: resolves T on first class-level access (the C jpy only resolves on instance access),
     * serves public static non-final fields live (the C jpy skips them), and otherwise behaves
     * exactly like type.__getattribute__.
     */
    static MemorySegment metaGetattro(MemorySegment type, MemorySegment name) {
        try {
            JavaType jt = JTypes.byPyType(type.address());
            if (jt != null) {
                // Python's own introspection (T.__dict__, T.__name__, T.__mro__, ...) must not
                // resolve: jpy_typeres_test.py checks via T.__dict__ that types resolve late.
                if (!jt.resolved && !jt.resolving && !isDunder(CPython.toJavaString(name))) {
                    JTypes.resolve(jt);
                }
                Field f = jt.resolved && hasStaticFields(jt)
                        ? JTypes.findStaticField(jt, CPython.toJavaString(name)) : null;
                if (f != null) {
                    return JFieldInfo.read(f, null);
                }
            }
            return CPython.typeGetAttrOrNull(type, name);
        } catch (Throwable t) {
            CPython.setPythonError(t);
            return NULL;
        }
    }

    /** T.name = value: writes public static non-final Java fields; everything else as type.__setattr__. */
    static int metaSetattro(MemorySegment type, MemorySegment name, MemorySegment value) {
        try {
            JavaType jt = JTypes.byPyType(type.address());
            if (jt != null && !value.equals(NULL)) {
                if (!jt.resolved && !jt.resolving) {
                    JTypes.resolve(jt);
                }
                Field f = jt.resolved && hasStaticFields(jt)
                        ? JTypes.findStaticField(jt, CPython.toJavaString(name)) : null;
                if (f != null) {
                    JFieldInfo.writeStatic(f, value);
                    return 0;
                }
            }
            return CPython.typeSetAttr(type, name, value);
        } catch (Throwable t) {
            CPython.setPythonError(t);
            return -1;
        }
    }

    private static boolean isDunder(String name) {
        return name.length() > 4 && name.startsWith("__") && name.endsWith("__");
    }

    /** Cheap pre-check so ordinary attribute access never converts the name to a Java string. */
    private static boolean hasStaticFields(JavaType jt) {
        for (JavaType t = jt; t != null; t = t.superType) {
            if (!t.staticFields.isEmpty()) {
                return true;
            }
        }
        return false;
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
            ArrayExports.onDealloc(self, JObjects.get(self));
            JpyModule.releaseByteBuffer(self);
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
                // obj.STATIC_FIELD reads a public static non-final field, as Java allows
                if (jt != null && jt.resolved && hasStaticFields(jt) && CPython.errMatches(CPython.PyExc_AttributeError)) {
                    Field f = JTypes.findStaticField(jt, CPython.toJavaString(name));
                    if (f != null) {
                        CPython.errClear();
                        return JFieldInfo.read(f, null);
                    }
                }
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
                // obj.STATIC_FIELD = v writes a public static non-final field, as Java allows
                JavaType jt = JObjects.javaTypeOf(self);
                if (jt != null && !value.equals(NULL)) {
                    if (!jt.resolved) {
                        JTypes.resolve(jt);
                    }
                    Field f = hasStaticFields(jt) ? JTypes.findStaticField(jt, CPython.toJavaString(name)) : null;
                    if (f != null) {
                        JFieldInfo.writeStatic(f, value);
                        return 0;
                    }
                }
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

    /** bf_getbuffer on Java primitive arrays (JArray_GetBufferProc). */
    static int arrayGetbuffer(MemorySegment self, MemorySegment view, int flags) {
        try {
            JavaType jt = arrayTypeOf(self);
            ArrayExports.getBuffer(self, view, flags, requireJavaObject(self), jt.componentType.clazz);
            return 0;
        } catch (Throwable t) {
            CPython.setPythonError(t);
            return -1;
        }
    }

    /** bf_releasebuffer. Must not raise. */
    static void arrayReleasebuffer(MemorySegment self, MemorySegment view) {
        try {
            ArrayExports.releaseBuffer(self);
        } catch (Throwable t) {
            t.printStackTrace();
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

    /** jpy.JOverloadedMethod.decl_class / .name / .methods. */
    static MemorySegment omGet(MemorySegment self, MemorySegment closure) {
        try {
            OverloadSet os = OverloadSet.BY_PYOBJ.get(self.address());
            if (os == null) {
                throw CPython.runtimeError("internal error: unknown jpy.JOverloadedMethod");
            }
            switch ((int) closure.address()) {
                case 0 -> {
                    CPython.incRef(os.declaringType.pyType);
                    return os.declaringType.pyType;
                }
                case 1 -> {
                    return CPython.newStr(os.name);
                }
                default -> {
                    MemorySegment[] items = new MemorySegment[os.methods.size()];
                    for (int i = 0; i < items.length; i++) {
                        items[i] = os.methods.get(i).pyObj();
                        CPython.incRef(items[i]);
                    }
                    return CPython.newList(items);
                }
            }
        } catch (Throwable t) {
            CPython.setPythonError(t);
            return NULL;
        }
    }

    // ------------------------------------------------------------------
    // jpy.JMethod: JMethod_repr/str, members and the param_* methods
    // ------------------------------------------------------------------

    private static OverloadSet.JMethod jmethod(MemorySegment self) {
        OverloadSet.JMethod m = OverloadSet.JMethod.BY_PYOBJ.get(self.address());
        if (m == null) {
            throw CPython.runtimeError("internal error: unknown jpy.JMethod");
        }
        return m;
    }

    static MemorySegment jmRepr(MemorySegment self) {
        try {
            return CPython.newStr(jmethod(self).repr());
        } catch (Throwable t) {
            CPython.setPythonError(t);
            return NULL;
        }
    }

    static MemorySegment jmStr(MemorySegment self) {
        try {
            return CPython.newStr(jmethod(self).name);
        } catch (Throwable t) {
            CPython.setPythonError(t);
            return NULL;
        }
    }

    /** JMethod_members: name, param_count, is_static. */
    static MemorySegment jmGet(MemorySegment self, MemorySegment closure) {
        try {
            OverloadSet.JMethod m = jmethod(self);
            return switch ((int) closure.address()) {
                case 0 -> CPython.newStr(m.name);
                case 1 -> CPython.newLong(m.paramCount);
                default -> CPython.newBool(m.isStatic);
            };
        } catch (Throwable t) {
            CPython.setPythonError(t);
            return NULL;
        }
    }

    /** "i:<fn>" (and "ip:<fn>" when withValue): the index, range-checked as JMethod_CHECK_PARAMETER_INDEX does. */
    private static int paramIndex(OverloadSet.JMethod m, MemorySegment args, String fn, boolean withValue) {
        long n = CPython.tupleSize(args);
        int want = withValue ? 2 : 1;
        if (n != want) {
            throw CPython.typeError(fn + "() takes exactly " + want + " argument" + (want > 1 ? "s" : "") + " (" + n + " given)");
        }
        MemorySegment idx = CPython.tupleGet(args, 0);
        if (!CPython.isLong(idx)) {
            throw CPython.typeError("'" + CPython.typeName(idx) + "' object cannot be interpreted as an integer");
        }
        long index = CPython.asLong(idx);
        if (index < 0 || index >= m.paramCount) {
            throw new CPython.PyRaise(CPython.PyExc_IndexError, "invalid parameter index");
        }
        return (int) index;
    }

    private static boolean paramValue(MemorySegment args) {
        return CPython.isTrue(CPython.tupleGet(args, 1));
    }

    static MemorySegment jmGetParamType(MemorySegment self, MemorySegment args) {
        try {
            OverloadSet.JMethod m = jmethod(self);
            JavaType t = JTypes.getType(m.paramTypes[paramIndex(m, args, "get_param_type", false)], false);
            CPython.incRef(t.pyType);
            return t.pyType;
        } catch (Throwable t) {
            CPython.setPythonError(t);
            return NULL;
        }
    }

    static MemorySegment jmIsParamMutable(MemorySegment self, MemorySegment args) {
        try {
            OverloadSet.JMethod m = jmethod(self);
            return CPython.newBool(m.isMutable[paramIndex(m, args, "is_param_mutable", false)]);
        } catch (Throwable t) {
            CPython.setPythonError(t);
            return NULL;
        }
    }

    static MemorySegment jmIsParamOutput(MemorySegment self, MemorySegment args) {
        try {
            OverloadSet.JMethod m = jmethod(self);
            return CPython.newBool(m.isOutput[paramIndex(m, args, "is_param_output", false)]);
        } catch (Throwable t) {
            CPython.setPythonError(t);
            return NULL;
        }
    }

    static MemorySegment jmIsParamReturn(MemorySegment self, MemorySegment args) {
        try {
            OverloadSet.JMethod m = jmethod(self);
            return CPython.newBool(m.isReturn[paramIndex(m, args, "is_param_return", false)]);
        } catch (Throwable t) {
            CPython.setPythonError(t);
            return NULL;
        }
    }

    static MemorySegment jmSetParamMutable(MemorySegment self, MemorySegment args) {
        try {
            OverloadSet.JMethod m = jmethod(self);
            m.isMutable[paramIndex(m, args, "set_param_mutable", true)] = paramValue(args);
            return CPython.none();
        } catch (Throwable t) {
            CPython.setPythonError(t);
            return NULL;
        }
    }

    static MemorySegment jmSetParamOutput(MemorySegment self, MemorySegment args) {
        try {
            OverloadSet.JMethod m = jmethod(self);
            m.isOutput[paramIndex(m, args, "set_param_output", true)] = paramValue(args);
            return CPython.none();
        } catch (Throwable t) {
            CPython.setPythonError(t);
            return NULL;
        }
    }

    /** JMethod_set_param_return: setting True also makes it the return parameter; False leaves that index. */
    static MemorySegment jmSetParamReturn(MemorySegment self, MemorySegment args) {
        try {
            OverloadSet.JMethod m = jmethod(self);
            int index = paramIndex(m, args, "set_param_return", true);
            boolean value = paramValue(args);
            m.isReturn[index] = value;
            if (value) {
                m.returnParamIndex = index;
            }
            return CPython.none();
        } catch (Throwable t) {
            CPython.setPythonError(t);
            return NULL;
        }
    }

    private static JFieldInfo field(MemorySegment self) {
        JFieldInfo f = JFieldInfo.BY_PYOBJ.get(self.address());
        if (f == null) {
            throw CPython.runtimeError("internal error: unknown jpy.JField");
        }
        return f;
    }

    /**
     * JField_repr. The C jpy prints the JNI field ID as fid; there is none here, so fid is the
     * Field's identity hash, which is just as opaque.
     */
    static MemorySegment fieldRepr(MemorySegment self) {
        try {
            JFieldInfo f = field(self);
            int mods = f.field.getModifiers();
            return CPython.newStr("jpy.JField(name='" + f.name + "', is_static=" + (Modifier.isStatic(mods) ? 1 : 0)
                    + ", is_final=" + (Modifier.isFinal(mods) ? 1 : 0)
                    + ", fid=0x" + Integer.toHexString(System.identityHashCode(f.field)) + ")");
        } catch (Throwable t) {
            CPython.setPythonError(t);
            return NULL;
        }
    }

    /** JField_str: the field name. */
    static MemorySegment fieldStr(MemorySegment self) {
        try {
            return CPython.newStr(field(self).name);
        } catch (Throwable t) {
            CPython.setPythonError(t);
            return NULL;
        }
    }

    /** JField_members: name, is_static, is_final (closure = index). */
    static MemorySegment fieldGet(MemorySegment self, MemorySegment closure) {
        try {
            JFieldInfo f = field(self);
            int mods = f.field.getModifiers();
            return switch ((int) closure.address()) {
                case 0 -> CPython.newStr(f.name);
                case 1 -> CPython.newBool(Modifier.isStatic(mods));
                default -> CPython.newBool(Modifier.isFinal(mods));
            };
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
