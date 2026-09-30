package org.jpy.ffm;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * JNI calls ignore Java access control; reflection does not. A public
 * method declared in a non-public class (e.g. an iterator implementation)
 * fails with IllegalAccessException when invoked reflectively. This maps
 * such members to an equivalent one on an accessible supertype, and falls
 * back to setAccessible where the module system allows it.
 */
final class Access {

    static boolean accessible(Member m) {
        Class<?> c = m.getDeclaringClass();
        return Modifier.isPublic(m.getModifiers())
                && Modifier.isPublic(c.getModifiers())
                && c.getModule().isExported(c.getPackageName());
    }

    static Method usable(Method m) {
        if (accessible(m)) {
            return m;
        }
        Method alt = findOnAccessibleSupertype(m);
        if (alt != null) {
            return alt;
        }
        m.trySetAccessible();
        return m;
    }

    static Constructor<?> usable(Constructor<?> c) {
        if (!accessible(c)) {
            c.trySetAccessible();
        }
        return c;
    }

    static Field usable(Field f) {
        if (!accessible(f)) {
            f.trySetAccessible();
        }
        return f;
    }

    private static Method findOnAccessibleSupertype(Method m) {
        Deque<Class<?>> todo = new ArrayDeque<>();
        Set<Class<?>> seen = new HashSet<>();
        todo.add(m.getDeclaringClass());
        while (!todo.isEmpty()) {
            Class<?> t = todo.poll();
            if (!seen.add(t)) {
                continue;
            }
            if (t != m.getDeclaringClass() && Modifier.isPublic(t.getModifiers())
                    && t.getModule().isExported(t.getPackageName())) {
                try {
                    Method candidate = t.getMethod(m.getName(), m.getParameterTypes());
                    if (accessible(candidate)) {
                        return candidate;
                    }
                } catch (NoSuchMethodException ignored) {
                    // keep looking
                }
            }
            if (t.getSuperclass() != null) {
                todo.add(t.getSuperclass());
            }
            for (Class<?> i : t.getInterfaces()) {
                todo.add(i);
            }
        }
        return null;
    }

    private Access() {
    }
}
