package org.jpy.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The type registry: Java class to Python type and back, creation of the
 * Python types, and lazy resolution of their members. Ports JType_GetType,
 * JType_GetTypeForName, JType_InitSuperType, JType_InitSlots and
 * JType_ResolveType with its Process{Constructors,Methods,Fields} helpers.
 */
public final class JTypes {

    static final Gil.PyAwareLock LOCK = new Gil.PyAwareLock();

    private static final ConcurrentHashMap<Class<?>, JavaType> BY_CLASS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, JavaType> BY_PYTYPE = new ConcurrentHashMap<>();

    /** Own overload sets of each resolved type, as they appear in its dict. */
    private static final ConcurrentHashMap<JavaType, Map<String, OverloadSet>> OVERLOADS = new ConcurrentHashMap<>();

    /** jpy.JType: the root base class of java.lang.Object and the primitive types. */
    static MemorySegment rootType;
    static JavaType objectType;
    static JavaType classType;

    private static final Map<String, Class<?>> PRIMITIVES = Map.of(
            "boolean", boolean.class, "char", char.class, "byte", byte.class, "short", short.class,
            "int", int.class, "long", long.class, "float", float.class, "double", double.class,
            "void", void.class);

    /** Before Py_Finalize; see Bootstrap.uninstall. */
    static void reset() {
        BY_CLASS.clear();
        BY_PYTYPE.clear();
        OVERLOADS.clear();
        rootType = null;
        objectType = null;
        classType = null;
    }

    public static JavaType byPyType(long pyTypeAddress) {
        return BY_PYTYPE.get(pyTypeAddress);
    }

    static JavaType byClass(Class<?> c) {
        return BY_CLASS.get(c);
    }

    /** JType_GetTypeForName. */
    static JavaType getTypeForName(String typeName, boolean resolve) {
        Class<?> c = PRIMITIVES.get(typeName);
        if (c == null) {
            try {
                c = Class.forName(typeName.replace('/', '.'), true, JTypes.class.getClassLoader());
            } catch (Throwable t) {
                throw CPython.valueError("Java class '" + typeName + "' not found");
            }
        }
        return getType(c, resolve);
    }

    /** JType_GetType. */
    static JavaType getType(Class<?> c, boolean resolve) {
        JavaType jt = BY_CLASS.get(c);
        if (jt == null) {
            LOCK.lock();
            try {
                jt = BY_CLASS.get(c);
                if (jt == null) {
                    jt = create(c);
                }
            } finally {
                LOCK.unlock();
            }
        }
        if (resolve && !jt.resolved) {
            resolve(jt);
        }
        return jt;
    }

    private static JavaType create(Class<?> c) {
        JavaType superType = null;
        Class<?> superClass = c.getSuperclass();
        if (superClass != null) {
            superType = getType(superClass, false);
        } else if (c.isInterface() && objectType != null) {
            // bcdev/jpy#57: interfaces get java.lang.Object's methods
            superType = objectType;
        }
        JavaType componentType = c.isArray() ? getType(c.getComponentType(), false) : null;

        JavaType jt = new JavaType(c, superType, componentType);
        jt.pyType = createPyType(jt);
        BY_CLASS.put(c, jt);
        BY_PYTYPE.put(jt.pyType.address(), jt);
        CPython.dictSet(JpyModule.types, jt.name, jt.pyType);
        if (classType != null) {
            addClassAttribute(jt);
        }
        return jt;
    }

    /** JType_AddClassAttribute: T.jclass is the wrapped java.lang.Class, T.jclassname its name. */
    static void addClassAttribute(JavaType jt) {
        MemorySegment jclass = JObjects.wrap(jt.clazz, classType);
        try {
            publish(jt, "jclass", jclass);
        } finally {
            CPython.decRef(jclass);
        }
        MemorySegment jclassname = CPython.newStr(jt.name);
        try {
            publish(jt, "jclassname", jclassname);
        } finally {
            CPython.decRef(jclassname);
        }
    }

    /**
     * Puts a bridge-owned entry into a Java type's dict. Goes through type's own setattr, not
     * jpy.JTypeMeta's hook, so publishing never triggers resolution or static-field writes.
     */
    private static void publish(JavaType jt, String name, MemorySegment value) {
        CPython.typeSetAttr(jt.pyType, name, value);
        jt.dictNames.add(name);
    }

    /**
     * The public static non-final field 'name' visible from jt, or null. Walks the superclass
     * chain nearest first; a name the bridge put in a dict at that level (a method, a constant, an
     * instance field) wins, so nothing that resolves today changes meaning.
     */
    static Field findStaticField(JavaType jt, String name) {
        for (JavaType t = jt; t != null; t = t.superType) {
            if (t.dictNames.contains(name)) {
                return null;
            }
            Field f = t.staticFields.get(name);
            if (f != null) {
                return f;
            }
        }
        return null;
    }

    /** JType_InitSlots, as a heap type built from a PyType_Spec. */
    private static MemorySegment createPyType(JavaType jt) {
        MemorySegment base = jt.superType != null ? jt.superType.pyType : rootType;
        List<long[]> slots = new ArrayList<>();
        slots.add(new long[]{CPython.Py_tp_new, CPython.PyType_GenericNew_ADDR.address()});
        slots.add(new long[]{CPython.Py_tp_init, Slots.JOBJ_INIT.address()});
        slots.add(new long[]{CPython.Py_tp_dealloc, Slots.JOBJ_DEALLOC.address()});
        slots.add(new long[]{CPython.Py_tp_getattro, Slots.JOBJ_GETATTRO.address()});
        slots.add(new long[]{CPython.Py_tp_setattro, Slots.JOBJ_SETATTRO.address()});
        slots.add(new long[]{CPython.Py_tp_richcompare, Slots.JOBJ_RICHCOMPARE.address()});
        slots.add(new long[]{CPython.Py_tp_hash, Slots.JOBJ_HASH.address()});
        slots.add(new long[]{CPython.Py_tp_repr, Slots.JOBJ_REPR.address()});
        slots.add(new long[]{CPython.Py_tp_str, Slots.JOBJ_STR.address()});
        if (jt.componentType != null) {
            slots.add(new long[]{CPython.Py_sq_length, Slots.JOBJ_SQ_LENGTH.address()});
            slots.add(new long[]{CPython.Py_sq_item, Slots.JOBJ_SQ_ITEM.address()});
            slots.add(new long[]{CPython.Py_sq_ass_item, Slots.JOBJ_SQ_ASS_ITEM.address()});
            if (jt.componentType.isPrimitive && jt.componentType.clazz != void.class) {
                slots.add(new long[]{CPython.Py_bf_getbuffer, Slots.ARRAY_GETBUFFER.address()});
                slots.add(new long[]{CPython.Py_bf_releasebuffer, Slots.ARRAY_RELEASEBUFFER.address()});
            }
        }
        boolean dotless = jt.name.indexOf('.') < 0;
        if (dotless) {
            slots.add(new long[]{CPython.Py_tp_members, MODULE_PLACEHOLDER_MEMBER.address()});
        }
        MemorySegment pyType = fromSpec(jt.name, CPython.Py_TPFLAGS_BASETYPE, slots, base, Slots.metaType);
        if (dotless) {
            MemorySegment builtins = CPython.newStr("builtins");
            try {
                CPython.typeSetAttr(pyType, "__module__", builtins);
            } finally {
                CPython.decRef(builtins);
            }
        }
        return pyType;
    }

    /**
     * PyType_FromSpec warns (DeprecationWarning, an error under -W error) when the spec name has no
     * dot, as for "int" or "[I", unless __module__ is already in the new type's dict. A read-only
     * member named __module__ puts it there; it is replaced with 'builtins' right after creation,
     * which is what a static C type named without a dot reports. The spec name must stay dot-free:
     * type repr prints tp_name when __module__ is 'builtins', so str(T) stays "<class '[I'>".
     * struct PyMemberDef { const char* name; int type; Py_ssize_t offset; int flags; const char* doc; }
     */
    private static final MemorySegment MODULE_PLACEHOLDER_MEMBER;

    static {
        Arena forever = Arena.global();
        MemorySegment members = forever.allocate(40L * 2, 8);
        members.set(ADDRESS, 0, forever.allocateFrom("__module__"));
        members.set(JAVA_INT, 8, 1);   // Py_T_INT
        members.set(JAVA_LONG, 16, 0); // offset
        members.set(JAVA_INT, 24, 1);  // Py_READONLY
        members.set(ADDRESS, 32, CPython.NULL);
        MODULE_PLACEHOLDER_MEMBER = members;
    }

    static MemorySegment fromSpec(String name, long flags, List<long[]> slots, MemorySegment base) {
        return fromSpec(name, flags, slots, base, null);
    }

    /**
     * PyType_FromMetaclass (or PyType_FromSpecWithBases when metaclass is null) over a
     * PyType_Spec and PyType_Slot[] laid out in native memory.
     */
    static MemorySegment fromSpec(String name, long flags, List<long[]> slots, MemorySegment base, MemorySegment metaclass) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment slotArray = a.allocate(16L * (slots.size() + 1), 8);
            for (int i = 0; i < slots.size(); i++) {
                slotArray.set(JAVA_INT, 16L * i, (int) slots.get(i)[0]);
                slotArray.set(ADDRESS, 16L * i + 8, MemorySegment.ofAddress(slots.get(i)[1]));
            }
            slotArray.set(JAVA_INT, 16L * slots.size(), 0);
            slotArray.set(ADDRESS, 16L * slots.size() + 8, CPython.NULL);

            // struct PyType_Spec { const char* name; int basicsize; int itemsize; unsigned int flags; PyType_Slot* slots; }
            MemorySegment spec = a.allocate(32, 8);
            spec.set(ADDRESS, 0, a.allocateFrom(name));
            spec.set(JAVA_INT, 8, 0);
            spec.set(JAVA_INT, 12, 0);
            spec.set(JAVA_INT, 16, (int) flags);
            spec.set(ADDRESS, 24, slotArray);
            MemorySegment bases = base == null ? CPython.NULL : base;
            if (metaclass != null) {
                return CPython.check((MemorySegment) CPython.PyType_FromMetaclass.invokeExact(
                        metaclass, CPython.NULL, spec, bases));
            }
            return CPython.check((MemorySegment) CPython.PyType_FromSpecWithBases.invokeExact(spec, bases));
        } catch (Throwable t) {
            throw CPython.rethrow(t);
        }
    }

    // ------------------------------------------------------------------
    // Resolution
    // ------------------------------------------------------------------

    /** JType_ResolveType. */
    static void resolve(JavaType jt) {
        if (jt.resolved) {
            return;
        }
        LOCK.lock();
        try {
            if (jt.resolved || jt.resolving) {
                return;
            }
            jt.resolving = true;
            try {
                if (jt.superType != null && !jt.superType.resolved) {
                    resolve(jt.superType);
                }
                Map<String, OverloadSet> own = new LinkedHashMap<>();
                processConstructors(jt);
                processMethods(jt, own);
                OVERLOADS.put(jt, own);
                processFields(jt);
                jt.resolved = true;
            } finally {
                jt.resolving = false;
            }
        } finally {
            LOCK.unlock();
        }
    }

    /** JType_GetOverloadedMethod: this type's own overloads, else its super types', else java.lang.Object's. */
    static OverloadSet getOverloads(JavaType jt, String name, boolean useSuperClass) {
        Map<String, OverloadSet> own = OVERLOADS.get(jt);
        OverloadSet os = own != null ? own.get(name) : null;
        if (os != null || !useSuperClass) {
            return os;
        }
        if (jt.superType != null) {
            return getOverloads(jt.superType, name, true);
        }
        if (jt != objectType && objectType != null) {
            return getOverloads(objectType, name, false);
        }
        return null;
    }

    private static void processConstructors(JavaType jt) {
        OverloadSet ctors = null;
        for (Constructor<?> c : jt.clazz.getDeclaredConstructors()) {
            if (!Modifier.isPublic(c.getModifiers())) {
                continue;
            }
            Constructor<?> usable = Access.usable(c);
            createParamTypes(c.getParameterTypes());
            OverloadSet.JMethod jm = new OverloadSet.JMethod(usable, jt);
            if (!acceptMethod(jt, jm)) {
                continue;
            }
            if (ctors == null) {
                ctors = new OverloadSet(jt, OverloadSet.JINIT);
            }
            ctors.add(jm);
        }
        if (ctors != null) {
            publishOverloads(jt, ctors);
            jt.constructors = ctors;
        }
    }

    private static void processMethods(JavaType jt, Map<String, OverloadSet> own) {
        for (Method m : jt.clazz.getMethods()) {
            int mod = m.getModifiers();
            // bridge methods are excluded: covariant return types would make calls ambiguous
            if (!Modifier.isPublic(mod) || m.isBridge()) {
                continue;
            }
            createParamTypes(m.getParameterTypes());
            getType(m.getReturnType(), false);
            OverloadSet.JMethod jm = new OverloadSet.JMethod(Access.usable(m), jt);
            if (acceptMethod(jt, jm)) {
                own.computeIfAbsent(m.getName(), n -> new OverloadSet(jt, n)).add(jm);
            }
        }
        for (OverloadSet os : own.values()) {
            publishOverloads(jt, os);
        }
    }

    /**
     * JType_AcceptMethod: jpy.type_callbacks[type name](type, method) may annotate the method's
     * parameters, or reject it by returning None or False. A callback that raises is ignored, as in
     * the C jpy, but its error is cleared instead of being left pending.
     */
    private static boolean acceptMethod(JavaType jt, OverloadSet.JMethod jm) {
        MemorySegment callback = CPython.dictGet(JpyModule.typeCallbacks, jt.name);
        if (callback.equals(CPython.NULL) || !CPython.isCallable(callback)) {
            return true;
        }
        MemorySegment method = jm.pyObj();
        CPython.incRef(jt.pyType);
        CPython.incRef(method);
        MemorySegment args = CPython.newTuple(jt.pyType, method);
        try {
            MemorySegment result = CPython.call(callback, args, CPython.NULL);
            boolean reject = result.equals(CPython.Py_None) || result.equals(CPython.Py_False);
            CPython.decRef(result);
            return !reject;
        } catch (CPython.PyErrAlreadySet e) {
            CPython.errClear();
            return true;
        } finally {
            CPython.decRef(args);
        }
    }

    private static void createParamTypes(Class<?>[] paramTypes) {
        for (Class<?> p : paramTypes) {
            getType(p, false);
        }
    }

    /** Creates the jpy.JOverloadedMethod object and stores it in the type's dict. */
    private static void publishOverloads(JavaType jt, OverloadSet os) {
        MemorySegment py = CPython.allocInstance(Slots.overloadedMethodType);
        os.pyObj = py;
        OverloadSet.BY_PYOBJ.put(py.address(), os);
        try {
            publish(jt, os.name, py);
        } finally {
            CPython.decRef(py);
        }
    }

    /**
     * JType_ProcessClassFields: static finals become plain values, instance fields become
     * jpy.JField. Static non-final fields, which the C jpy skips, are recorded and served live
     * by jpy.JTypeMeta and the instance slots (an improvement over the C jpy).
     */
    private static void processFields(JavaType jt) {
        Field[] fields = jt.isInterface ? jt.clazz.getFields() : jt.clazz.getDeclaredFields();
        for (Field f : fields) {
            int mod = f.getModifiers();
            if (!Modifier.isPublic(mod)) {
                continue;
            }
            JavaType fieldType = getType(f.getType(), false);
            boolean isStatic = Modifier.isStatic(mod);
            boolean isFinal = Modifier.isFinal(mod);
            Field usable = Access.usable(f);
            if (isStatic && isFinal) {
                Object value;
                try {
                    value = usable.get(null);
                } catch (IllegalAccessException | ExceptionInInitializerError e) {
                    continue;
                }
                MemorySegment py = Convert.toPython(value, fieldType.clazz);
                try {
                    publish(jt, f.getName(), py);
                } finally {
                    CPython.decRef(py);
                }
            } else if (!isStatic) {
                JFieldInfo info = new JFieldInfo(jt, usable);
                MemorySegment py = CPython.allocInstance(Slots.fieldType);
                info.pyObj = py;
                JFieldInfo.BY_PYOBJ.put(py.address(), info);
                try {
                    publish(jt, f.getName(), py);
                } finally {
                    CPython.decRef(py);
                }
            } else {
                jt.staticFields.put(f.getName(), usable);
            }
        }
    }

    private JTypes() {
    }
}
