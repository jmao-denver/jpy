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

    /**
     * Public static non-final fields declared by this type, served live by
     * jpy.JTypeMeta (class access) and the instance slots. The C jpy skips
     * these fields entirely. Filled during resolution.
     */
    final java.util.Map<String, java.lang.reflect.Field> staticFields = new java.util.HashMap<>();

    /** Names resolution put into this type's dict (methods, constants, instance fields). */
    final java.util.Set<String> dictNames = new java.util.HashSet<>();

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
