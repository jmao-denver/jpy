package org.jpy.ffm;

import java.lang.foreign.MemorySegment;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.jpy.ffm.CPython.Py_None;

/**
 * One jpy.JOverloadedMethod: all overloads of one name as seen from one
 * Java type's dict. Ports JOverloadedMethod_FindMethod{0},
 * JMethod_MatchPyArgs, the JType_MatchPyArgAs*Param scorers and
 * JMethod_InvokeMethod from the C jpy.
 */
final class OverloadSet {

    /** Python jpy.JOverloadedMethod objects, keyed by address. */
    static final ConcurrentHashMap<Long, OverloadSet> BY_PYOBJ = new ConcurrentHashMap<>();

    static final String JINIT = "__jinit__";

    /** One Java method or constructor, the C JPy_JMethod. */
    static final class JMethod {
        final Executable executable;
        final Class<?>[] paramTypes;
        /** void.class for constructors, as the C code never converts their return value. */
        final Class<?> returnType;
        /** Constructors count as static: their Python args do not include self. */
        final boolean isStatic;
        final boolean isVarArgs;
        final int paramCount;

        JMethod(Executable e) {
            this.executable = e;
            this.paramTypes = e.getParameterTypes();
            this.returnType = e instanceof Method m ? m.getReturnType() : void.class;
            this.isStatic = e instanceof Constructor || Modifier.isStatic(e.getModifiers());
            this.isVarArgs = e.isVarArgs();
            this.paramCount = paramTypes.length;
        }
    }

    final JavaType declaringType;
    final String name;
    final List<JMethod> methods = new ArrayList<>();
    MemorySegment pyObj;

    OverloadSet(JavaType declaringType, String name) {
        this.declaringType = declaringType;
        this.name = name;
    }

    /** JOverloadedMethod_AddMethod: fixed-arity overloads go before the first var-args one. */
    void add(JMethod m) {
        if (!m.isVarArgs) {
            for (int i = 0; i < methods.size(); i++) {
                if (methods.get(i).isVarArgs) {
                    methods.add(i, m);
                    return;
                }
            }
        }
        methods.add(m);
    }

    // ------------------------------------------------------------------
    // Finding the best overload
    // ------------------------------------------------------------------

    record Found(JMethod method, int matchValue, int matchCount, boolean isVarArgsArray) {
        static final Found NONE = new Found(null, 0, 0, false);
    }

    /** JOverloadedMethod_FindMethod0. */
    Found find0(MemorySegment args, int argCount) {
        int matchValueMax = -1;
        int matchCount = 0;
        JMethod best = null;
        boolean bestIsVarArgsArray = false;
        boolean[] isVarArgsArray = new boolean[1];
        for (JMethod m : methods) {
            if (m.isVarArgs && matchValueMax > 0 && !best.isVarArgs) {
                break;
            }
            int matchValue = matchArgs(m, argCount, args, isVarArgsArray);
            if (matchValue > 0) {
                if (matchValue > matchValueMax) {
                    matchValueMax = matchValue;
                    best = m;
                    matchCount = 1;
                    bestIsVarArgsArray = isVarArgsArray[0];
                } else if (matchValue == matchValueMax) {
                    matchCount++;
                }
                if (!m.isVarArgs && matchValue >= 100 * argCount) {
                    break;
                }
            }
        }
        if (best == null) {
            return Found.NONE;
        }
        return new Found(best, matchValueMax, matchCount, bestIsVarArgsArray);
    }

    /** JOverloadedMethod_FindMethod, including the walk through super types' overloads. */
    Found find(MemorySegment args, boolean visitSuperClass) {
        int argCount = (int) CPython.tupleSize(args);
        Found best = Found.NONE;
        OverloadSet current = this;
        while (true) {
            Found r = current.find0(args, argCount);
            if (r.method != null) {
                if (r.matchValue >= 100 * argCount && r.matchCount == 1) {
                    return r;
                } else if (r.matchValue > 0 && r.matchValue > best.matchValue) {
                    best = r;
                }
            }
            OverloadSet superSet = null;
            if (visitSuperClass && current.declaringType.superType != null) {
                superSet = JTypes.getOverloads(current.declaringType.superType, current.name, true);
            }
            if (superSet == null) {
                if (best.method == null) {
                    throw CPython.runtimeError("no matching Java method overloads found");
                } else if (best.matchCount > 1) {
                    throw CPython.runtimeError("ambiguous Java method call, too many matching method overloads found");
                }
                return best;
            }
            current = superSet;
        }
    }

    /** JMethod_MatchPyArgs. */
    int matchArgs(JMethod m, int argCount, MemorySegment args, boolean[] isVarArgArray) {
        isVarArgArray[0] = false;
        int matchValueSum;
        int i0;
        int iLast;
        if (m.isStatic) {
            if (m.isVarArgs) {
                if (argCount < m.paramCount - 1) return 0;
                iLast = m.paramCount - 1;
            } else {
                if (m.paramCount != argCount) return 0;
                if (m.paramCount == 0) return 100;
                iLast = argCount;
            }
            matchValueSum = 0;
            i0 = 0;
        } else {
            if (m.isVarArgs) {
                if (argCount < m.paramCount) return 0;
                iLast = m.paramCount;
            } else if (m.paramCount != argCount - 1) {
                return 0;
            } else {
                iLast = m.paramCount + 1;
            }
            MemorySegment self = CPython.tupleGet(args, 0);
            if (self.equals(Py_None)) return 0;
            if (JObjects.get(self) == null) return 0;
            i0 = 1;
            matchValueSum = matchObject(declaringType.clazz, self);
            if (matchValueSum == 0) return 0;
            if (m.paramCount == 0) return matchValueSum;
        }

        int p = 0;
        int i;
        for (i = i0; i < iLast; i++) {
            int matchValue = matchParam(m.paramTypes[p], CPython.tupleGet(args, i));
            if (matchValue == 0) return 0;
            matchValueSum += matchValue;
            p++;
        }
        if (m.isVarArgs) {
            int singleMatchValue = 0;
            if (argCount - i == 0) {
                matchValueSum += 10;
            } else if (argCount - i == 1) {
                singleMatchValue = matchParam(m.paramTypes[p], CPython.tupleGet(args, i));
            }
            int matchValue = matchVarArgs(m.paramTypes[p], args, i, argCount);
            if (matchValue == 0 && singleMatchValue == 0) return 0;
            if (matchValue > singleMatchValue) {
                matchValueSum += matchValue;
            } else {
                matchValueSum += singleMatchValue;
                isVarArgArray[0] = true;
            }
        }
        return matchValueSum;
    }

    /** The MatchPyArg function JType_InitParamDescriptorFunctions picks for a parameter type. */
    static int matchParam(Class<?> paramType, MemorySegment pyArg) {
        if (paramType == boolean.class) {
            if (CPython.isBool(pyArg)) return 100;
            if (CPython.isLong(pyArg)) return 10;
            return 0;
        }
        if (paramType == byte.class || paramType == char.class || paramType == short.class
                || paramType == int.class || paramType == long.class) {
            if (CPython.isLong(pyArg)) return 100;
            if (CPython.isBool(pyArg)) return 10;
            return 0;
        }
        if (paramType == float.class || paramType == double.class) {
            if (CPython.isFloat(pyArg)) return paramType == float.class ? 90 : 100;
            if (CPython.isNumber(pyArg)) return 50;
            if (CPython.isLong(pyArg)) return 10;
            if (CPython.isBool(pyArg)) return 1;
            return 0;
        }
        if (paramType == String.class) {
            if (pyArg.equals(Py_None)) return 1;
            if (CPython.isStr(pyArg)) return 100;
            return 0;
        }
        if (paramType == Constants.PY_OBJECT_CLASS) {
            return 10;
        }
        return matchObject(paramType, pyArg);
    }

    /** JType_MatchPyArgAsJObject. */
    static int matchObject(Class<?> paramType, MemorySegment pyArg) {
        if (pyArg.equals(Py_None)) {
            return 1;
        }
        Class<?> paramComponent = paramType.getComponentType();
        Object wrapped = JObjects.get(pyArg);
        if (wrapped != null) {
            JavaType argType = JObjects.javaTypeOf(pyArg);
            if (argType != null && argType.clazz == paramType) {
                return 100;
            }
            if (paramType.isInstance(wrapped)) {
                Class<?> argComponent = argType != null && argType.componentType != null
                        ? argType.componentType.clazz : null;
                if (argComponent == paramComponent) return 90;
                if (argComponent != null && paramComponent != null && paramComponent.isAssignableFrom(argComponent)) {
                    return 80;
                }
                return 10;
            }
            return 0;
        }

        if (paramComponent != null) {
            // TODO(session 5): primitive-array parameters matched against Python buffer objects
            if (CPython.isSequence(pyArg)) {
                if (String.class.isAssignableFrom(paramComponent)) {
                    long len = CPython.sequenceSize(pyArg);
                    for (long k = 0; k < len; k++) {
                        MemorySegment element = CPython.sequenceGet(pyArg, k);
                        boolean isStr = CPython.isStr(element);
                        CPython.decRef(element);
                        if (!isStr) return 0;
                    }
                    return 80;
                }
                return 10;
            }
        } else if (paramType == Object.class) {
            return 10;
        } else if (paramType == Boolean.class) {
            if (CPython.isBool(pyArg)) return 100;
            if (CPython.isLong(pyArg)) return 10;
        } else if (paramType == Character.class || paramType == Byte.class || paramType == Short.class
                || paramType == Integer.class || paramType == Long.class) {
            if (CPython.isLong(pyArg)) return 100;
            if (CPython.isBool(pyArg)) return 10;
        } else if (paramType == Float.class || paramType == Double.class) {
            if (CPython.isFloat(pyArg)) return 100;
            if (CPython.isLong(pyArg)) return 90;
            if (CPython.isBool(pyArg)) return 10;
        } else {
            if (CPython.isStr(pyArg)) {
                if (paramType.isAssignableFrom(String.class)) return 80;
            } else if (CPython.isBool(pyArg)) {
                if (paramType.isAssignableFrom(Boolean.class)) return 80;
            } else if (CPython.isLong(pyArg)) {
                if (paramType.isAssignableFrom(Integer.class) || paramType.isAssignableFrom(Long.class)) return 80;
            } else if (CPython.isFloat(pyArg)) {
                if (paramType.isAssignableFrom(Double.class) || paramType.isAssignableFrom(Float.class)) return 80;
            }
        }
        return 0;
    }

    /** The MatchVarArgPyArg functions: the minimum score over the trailing arguments. */
    static int matchVarArgs(Class<?> arrayType, MemorySegment args, int idx, int argCount) {
        Class<?> component = arrayType.getComponentType();
        if (component == null) {
            return 0;
        }
        int remaining = argCount - idx;
        if (remaining == 0) {
            return 10;
        }
        int minMatch = 100;
        for (int k = 0; k < remaining; k++) {
            MemorySegment item = CPython.tupleGet(args, idx + k);
            int matchValue;
            if (component == boolean.class) {
                matchValue = CPython.isBool(item) ? 100 : CPython.isLong(item) ? 10 : 0;
            } else if (component == byte.class || component == char.class || component == short.class
                    || component == int.class || component == long.class) {
                matchValue = CPython.isLong(item) ? 100 : CPython.isBool(item) ? 10 : 0;
            } else if (component == float.class || component == double.class) {
                matchValue = CPython.isFloat(item) ? (component == float.class ? 90 : 100)
                        : CPython.isNumber(item) ? 50
                        : CPython.isLong(item) ? 10
                        : CPython.isBool(item) ? 1 : 0;
            } else if (component == String.class) {
                matchValue = item.equals(Py_None) ? 1 : CPython.isStr(item) ? 100 : 0;
            } else if (component == Constants.PY_OBJECT_CLASS) {
                matchValue = 10;
            } else {
                matchValue = matchObject(component, item);
            }
            if (matchValue == 0) return 0;
            minMatch = Math.min(minMatch, matchValue);
        }
        return minMatch;
    }

    // ------------------------------------------------------------------
    // Invocation
    // ------------------------------------------------------------------

    /** JMethod_CreateJArgs. */
    static Object[] javaArgs(JMethod m, MemorySegment args, boolean isVarArgsArray) {
        Object[] jargs = new Object[m.paramCount];
        if (m.paramCount == 0) {
            return jargs;
        }
        int argCount = (int) CPython.tupleSize(args);
        int i0;
        int iLast;
        if (m.isVarArgs) {
            i0 = m.isStatic ? 0 : 1;
            iLast = m.isStatic ? m.paramCount - 1 : m.paramCount;
        } else {
            i0 = argCount - m.paramCount;
            if (i0 != 0 && i0 != 1) {
                throw CPython.runtimeError("internal error");
            }
            iLast = argCount;
        }
        int p = 0;
        int i;
        for (i = i0; i < iLast; i++) {
            jargs[p] = Convert.toJavaArg(CPython.tupleGet(args, i), m.paramTypes[p]);
            p++;
        }
        if (m.isVarArgs) {
            if (isVarArgsArray) {
                jargs[p] = Convert.toJavaArg(CPython.tupleGet(args, i), m.paramTypes[p]);
            } else {
                jargs[p] = varArgsArray(m.paramTypes[p].getComponentType(), args, i, argCount);
            }
        }
        return jargs;
    }

    /**
     * JType_ConvertVarArgPyArgToJObjectArg converts args[from:] as a sequence
     * into the var-args array; this builds the same array item by item.
     */
    private static Object varArgsArray(Class<?> component, MemorySegment args, int from, int argCount) {
        Object array = Array.newInstance(component, argCount - from);
        for (int k = from; k < argCount; k++) {
            MemorySegment item = CPython.tupleGet(args, k);
            Object value = component.isPrimitive()
                    ? Convert.primitiveArrayItem(item, component)
                    : Convert.toJavaObject(item, component, false);
            Array.set(array, k - from, value);
        }
        return array;
    }

    /** JOverloadedMethod_call: find the overload, convert, call Java without the GIL, convert back. */
    MemorySegment call(MemorySegment args) {
        Found f = find(args, true);
        return invoke(f.method, args, f.isVarArgsArray);
    }

    static MemorySegment invoke(JMethod m, MemorySegment args, boolean isVarArgsArray) {
        Object[] jargs = javaArgs(m, args, isVarArgsArray);
        Object target = null;
        if (!m.isStatic) {
            target = JObjects.get(CPython.tupleGet(args, 0));
        }
        Object result;
        try (Gil.Released r = Gil.release()) {
            result = ((Method) m.executable).invoke(target, jargs);
        } catch (InvocationTargetException e) {
            throw JavaErrors.toPython(e.getCause());
        } catch (IllegalAccessException | IllegalArgumentException e) {
            throw JavaErrors.toPython(e);
        }
        return Convert.toPython(result, m.returnType);
    }

    /** Runs a constructor; the caller stores the new object. */
    static Object construct(JMethod m, MemorySegment args, boolean isVarArgsArray) {
        Object[] jargs = javaArgs(m, args, isVarArgsArray);
        try (Gil.Released r = Gil.release()) {
            return ((Constructor<?>) m.executable).newInstance(jargs);
        } catch (InvocationTargetException e) {
            throw JavaErrors.toPython(e.getCause());
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            throw JavaErrors.toPython(e);
        }
    }

    // ------------------------------------------------------------------
    // Python-facing slots of jpy.JOverloadedMethod
    // ------------------------------------------------------------------

    /** JOverloadedMethod_repr. */
    String repr() {
        return "jpy.JOverloadedMethod(class='" + declaringType.name + "', name='" + name
                + "', methodCount=" + methods.size() + ")";
    }
}
