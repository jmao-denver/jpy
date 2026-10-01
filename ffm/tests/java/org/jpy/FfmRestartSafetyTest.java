package org.jpy;

import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

/**
 * FFM-only: a PyObject that is still alive when Java stops Python is never decRef'd into a restarted
 * interpreter. On the C jpy this is undefined behavior: the wrapper's later decRef hits an address
 * from the old interpreter, which aborted the process in the FFM port before the fix.
 */
public class FfmRestartSafetyTest {

    @Test
    public void liveWrappersAreForgottenAcrossRestart() {
        Assume.assumeFalse(Boolean.getBoolean("jpy.stopIsNoOp"));
        List<PyObject> held = new ArrayList<>();
        PyLib.startPython();
        for (int i = 0; i < 1000; i++) {
            held.add(PyObject.executeCode("object()", PyInputMode.EXPRESSION));
        }
        PyObject kept = held.get(0);
        PyLib.stopPython();

        PyLib.startPython();
        try {
            // the old wrappers die in the new interpreter
            held.clear();
            System.gc();
            PyObject.cleanup();
            for (int i = 0; i < 100; i++) {
                PyObject.executeCode("[object() for _ in range(1000)]", PyInputMode.EXPRESSION).close();
            }
            // a wrapper from the old interpreter is closed for good
            Assert.assertThrows(IllegalStateException.class, kept::getPointer);
            kept.close();
            Assert.assertEquals(42, PyObject.executeCode("6 * 7", PyInputMode.EXPRESSION).getIntValue());
        } finally {
            PyLib.stopPython();
        }
    }
}
