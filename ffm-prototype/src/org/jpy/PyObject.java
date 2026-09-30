package org.jpy;

import java.lang.foreign.MemorySegment;

import ffm.Py;

/**
 * Wrapper around a CPython PyObject* — same role and name as jpy's
 * org.jpy.PyObject. Owns one reference; close() releases it (jpy uses a
 * cleanup thread / PyObjectReferences; the prototype keeps it explicit).
 */
public class PyObject implements AutoCloseable {
    private final long pointer;

    PyObject(MemorySegment ptr, boolean takeRef) {
        if (takeRef) {
            Py.incRef(ptr);
        }
        this.pointer = ptr.address();
    }

    public long getPointer() {
        return pointer;
    }

    MemorySegment segment() {
        return MemorySegment.ofAddress(pointer);
    }

    // ---- value accessors, named as in jpy's PyObject ----

    public int getIntValue() { return PyLib.getIntValue(pointer); }

    public long getLongValue() { return PyLib.getLongValue(pointer); }

    public double getDoubleValue() { return PyLib.getDoubleValue(pointer); }

    public boolean getBooleanValue() { return PyLib.getBooleanValue(pointer); }

    public String getStringValue() { return PyLib.getStringValue(pointer); }

    public Object getObjectValue() { return PyLib.getObjectValue(pointer); }

    public String str() {
        try (Py.Gil gil = Py.Gil.lock()) {
            return Py.str(segment());
        }
    }

    @Override
    public String toString() {
        return "PyObject(pointer=0x" + Long.toHexString(pointer) + ")";
    }

    @Override
    public void close() {
        try (Py.Gil gil = Py.Gil.lock()) {
            Py.decRef(segment());
        }
    }
}
