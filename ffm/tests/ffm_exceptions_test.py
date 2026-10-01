"""
Exact formats for jpy.VerboseExceptions and the checks of jpy.diag and jpy.VerboseExceptions.
jpy's own jpy_exception_test.py writes out the C jpy's exact verbose messages but only asserts
that they contain "java.lang.NullPointerException"; this file asserts them in full. The FFM jpy
calls Java through reflection, so it must cut the bridge's frames to match a JNI call's stack.
"""
import unittest

import jpyutil

jpyutil.init_jvm(jvm_maxmem='512M', jvm_classpath=['target/test-classes'])
import jpy


class TestVerboseExceptions(unittest.TestCase):
    def setUp(self):
        self.fixture = jpy.get_type('org.jpy.fixtures.ExceptionTestFixture')()
        jpy.VerboseExceptions.enabled = True

    def tearDown(self):
        jpy.VerboseExceptions.enabled = False

    def test_nested(self):
        with self.assertRaises(RuntimeError) as e:
            self.fixture.throwNpeIfArgIsNullNested(None)
        lines = str(e.exception).split('\n')
        self.assertEqual(lines[0], 'java.lang.RuntimeException: Nested exception')
        self.assertEqual(lines[1], '\tat org.jpy.fixtures.ExceptionTestFixture.throwNpeIfArgIsNullNested(ExceptionTestFixture.java:43)')
        self.assertTrue(lines[2].startswith('caused by java.lang.NullPointerException'), lines[2])
        self.assertEqual(lines[3:], [
            '\tat org.jpy.fixtures.ExceptionTestFixture.throwNpeIfArgIsNull(ExceptionTestFixture.java:32)',
            '\tat org.jpy.fixtures.ExceptionTestFixture.throwNpeIfArgIsNullNested(ExceptionTestFixture.java:41)',
            ''])

    def test_nested3_elides_common_frames(self):
        with self.assertRaises(RuntimeError) as e:
            self.fixture.throwNpeIfArgIsNullNested3(None)
        lines = str(e.exception).split('\n')
        self.assertTrue(lines[6].startswith('caused by java.lang.NullPointerException'), lines[6])
        del lines[6]  # the NPE's helpful message differs between JDKs
        self.assertEqual(lines, [
            'java.lang.RuntimeException: Nested exception 3',
            '\tat org.jpy.fixtures.ExceptionTestFixture.throwNpeIfArgIsNullNested3(ExceptionTestFixture.java:55)',
            'caused by java.lang.RuntimeException: Nested exception',
            '\tat org.jpy.fixtures.ExceptionTestFixture.throwNpeIfArgIsNullNested(ExceptionTestFixture.java:43)',
            '\tat org.jpy.fixtures.ExceptionTestFixture.throwNpeIfArgIsNullNested2(ExceptionTestFixture.java:48)',
            '\tat org.jpy.fixtures.ExceptionTestFixture.throwNpeIfArgIsNullNested3(ExceptionTestFixture.java:53)',
            '\tat org.jpy.fixtures.ExceptionTestFixture.throwNpeIfArgIsNull(ExceptionTestFixture.java:32)',
            '\tat org.jpy.fixtures.ExceptionTestFixture.throwNpeIfArgIsNullNested(ExceptionTestFixture.java:41)',
            '\t... 2 more',
            ''])

    def test_off_is_tostring(self):
        jpy.VerboseExceptions.enabled = False
        with self.assertRaises(RuntimeError) as e:
            self.fixture.throwRteIfMessageIsNotNull("Evil!")
        self.assertEqual(str(e.exception), 'java.lang.RuntimeException: Evil!')


class TestSwitches(unittest.TestCase):
    def test_verbose_exceptions_needs_a_bool(self):
        with self.assertRaises(ValueError):
            jpy.VerboseExceptions.enabled = 1
        self.assertFalse(jpy.VerboseExceptions.enabled)

    def test_diag_flags_need_an_int(self):
        with self.assertRaises(ValueError):
            jpy.diag.flags = 'x'
        self.assertEqual(jpy.diag.flags, 0)

    def test_diag_flags_are_shared_with_java(self):
        PyLib = jpy.get_type('org.jpy.PyLib$Diag')
        jpy.diag.flags = jpy.diag.F_ERR
        try:
            self.assertEqual(PyLib.getFlags(), 0x20)
        finally:
            jpy.diag.flags = 0

    def test_constants_are_read_only(self):
        with self.assertRaises(AttributeError):
            jpy.diag.F_OFF = 1


if __name__ == '__main__':
    print('\nRunning ' + __file__)
    unittest.main()
