"""
FFM jpy bridge checks beyond jpy's own suite: that the pure-Python FFM jpy is the one
running, plus calls, overloads, fields, arrays and error translation through it.
"""
import sys
import unittest

import jpyutil

jpyutil.init_jvm(jvm_maxmem='512M', jvm_classpath=['target/test-classes'])
import jpy


class TestFfmIdentity(unittest.TestCase):
    def test_pure_python_jpy_module(self):
        self.assertTrue(jpy.__file__.replace('\\', '/').endswith('ffm/python/jpy.py'))

    def test_no_compiled_jpy_extension(self):
        compiled = [n for n, m in list(sys.modules.items())
                    if n.startswith(('jpy', 'jdl')) and str(getattr(m, '__file__', '')).endswith(('.so', '.pyd'))]
        self.assertEqual(compiled, [])

    def test_c_jpy_jar_not_on_class_path(self):
        cp = jpy.get_type('java.lang.System').getProperty('java.class.path')
        self.assertNotIn('jpy-2.', cp)


class TestFfmCalls(unittest.TestCase):
    def setUp(self):
        self.String = jpy.get_type('java.lang.String')

    def test_constructor_and_instance_method(self):
        s = self.String('hello')
        self.assertEqual(str(s), 'hello')
        self.assertEqual(s.toUpperCase(), 'HELLO')

    def test_overload_by_argument_type(self):
        s = self.String('hello')
        self.assertEqual(s.indexOf('l'), 2)
        self.assertEqual(s.indexOf(ord('l')), 2)

    def test_static_method(self):
        self.assertEqual(self.String.valueOf(42), '42')

    def test_equality_and_hash_are_java(self):
        self.assertTrue(self.String('a') == self.String('a'))
        self.assertEqual(hash(self.String('a')), 97)

    def test_jclass_and_registry(self):
        self.assertEqual(self.String.jclassname, 'java.lang.String')
        self.assertEqual(str(self.String.jclass), 'class java.lang.String')
        self.assertIs(jpy.types['java.lang.String'], self.String)

    def test_collections(self):
        lst = jpy.get_type('java.util.ArrayList')()
        lst.add('x')
        lst.add(7)
        self.assertEqual(lst.size(), 2)
        self.assertEqual(lst.get(1), 7)

    def test_instance_fields(self):
        p = jpy.get_type('java.awt.Point')(3, 4)
        self.assertEqual((p.x, p.y), (3, 4))
        p.x = 10
        self.assertEqual(p.getX(), 10.0)

    def test_object_array(self):
        Arrays = jpy.get_type('java.util.Arrays')
        arr = Arrays.asList('a', 'b').toArray()
        self.assertEqual(len(arr), 2)
        self.assertEqual(arr[0], 'a')

    def test_static_final_constant(self):
        self.assertEqual(jpy.get_type('java.lang.Integer').MAX_VALUE, 2147483647)


class TestFfmErrors(unittest.TestCase):
    def test_java_exception_becomes_runtime_error(self):
        with self.assertRaises(RuntimeError) as e:
            jpy.get_type('java.lang.Integer').parseInt('nope')
        self.assertIn('NumberFormatException', str(e.exception))

    def test_no_matching_overload(self):
        with self.assertRaises(RuntimeError) as e:
            jpy.get_type('java.lang.String').valueOf()
        self.assertEqual(str(e.exception), 'no matching Java method overloads found')


if __name__ == '__main__':
    print('\nRunning ' + __file__)
    unittest.main()
