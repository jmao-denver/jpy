"""
The FFM jpy can find the Python API in the running process instead of in a libpython file.
jpy.py does this by itself when libpython is built into the Python executable (Ubuntu's python3,
uv's Linux builds), where there is no libpython file to open. JPY_PYTHON_LIB=process forces the
same path on any Python, so this test runs everywhere except Windows.
"""
import os
import sys
import unittest

if sys.platform != 'win32':
    os.environ['JPY_PYTHON_LIB'] = 'process'

import jpyutil

jpyutil.init_jvm(jvm_maxmem='512M', jvm_classpath=['target/test-classes'])
import jpy


@unittest.skipIf(sys.platform == 'win32', 'Windows Python always runs from a python3XX.dll')
class ProcessLookupTest(unittest.TestCase):

    def test_property(self):
        System = jpy.get_type('java.lang.System')
        self.assertEqual(System.getProperty('jpy.pythonLib'), 'process')

    def test_python_to_java(self):
        ArrayList = jpy.get_type('java.util.ArrayList')
        a = ArrayList()
        a.add('x')
        a.add('y')
        self.assertEqual(a.size(), 2)
        self.assertEqual(a.get(1), 'y')

    def test_java_to_python(self):
        # Java evaluates an expression in this frame
        Fixture = jpy.get_type('org.jpy.fixtures.EvalTestFixture')
        x = 41
        self.assertEqual(Fixture.expression('x + 1'), 42)

    def test_python_errors(self):
        PyModule = jpy.get_type('org.jpy.PyModule')
        with self.assertRaises(RuntimeError) as cm:
            PyModule.importModule('no_such_module_anywhere')
        self.assertIn('ModuleNotFoundError', str(cm.exception))


if __name__ == '__main__':
    unittest.main()
