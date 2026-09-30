package org.jpy.ffm;

import java.lang.foreign.MemorySegment;

/**
 * Java-side state for one bridged Java type. The matching Python type object
 * is {@link #pyType}; this class holds what the C jpy stored in JPy_JType.
 */
public final class JavaType {
    public final Class<?> clazz;
    /** Class.getName(), e.g. "[I" or "java.awt.geom.Point2D$Double"; also the Python tp_name. */
    public final String name;
    /** The Java superclass, or java.lang.Object for interfaces; null for Object, primitives and void. */
    public final JavaType superType;
    /** Non-null only for array types. */
    public final JavaType componentType;
    public final boolean isPrimitive;
    public final boolean isInterface;

    MemorySegment pyType;

    volatile boolean resolved;
    boolean resolving;

    /** The "__jinit__" overloads; null if the type has no public constructor or is unresolved. */
    OverloadSet constructors;

    JavaType(Class<?> clazz, JavaType superType, JavaType componentType) {
        this.clazz = clazz;
        this.name = clazz.getName();
        this.superType = superType;
        this.componentType = componentType;
        this.isPrimitive = clazz.isPrimitive();
        this.isInterface = clazz.isInterface();
    }

    public MemorySegment pyType() {
        return pyType;
    }

    public boolean isResolved() {
        return resolved;
    }

    @Override
    public String toString() {
        return "JavaType(" + name + ")";
    }
}
