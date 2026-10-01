"""
FFM jpy improvements over the C jpy (ffm/DESIGN.md section 11): the jpy.JTypeMeta metaclass,
live public static non-final fields, and class-level resolution of unresolved types.
The C jpy fails these tests by design; they live outside src/test/python for that reason.
"""
import unittest

import jpyutil

jpyutil.init_jvm(jvm_maxmem='512M', jvm_classpath=['target/test-classes', 'ffm/build/fixtures'])
import jpy


class TestJTypeMeta(unittest.TestCase):
    def setUp(self):
        self.F = jpy.get_type('org.jpy.ffm.fixtures.StaticFieldsFixture')

    def test_metaclass_shape(self):
        self.assertIs(type(self.F), jpy.JTypeMeta)
        self.assertIs(type(jpy.JTypeMeta), type)
        self.assertTrue(issubclass(jpy.JTypeMeta, type))

    def test_jtype_is_still_the_base_of_java_classes(self):
        self.assertTrue(issubclass(self.F, jpy.JType))
        self.assertIsInstance(self.F(), jpy.JType)

    def test_type_str_unchanged(self):
        self.assertEqual(str(self.F), "<class 'org.jpy.ffm.fixtures.StaticFieldsFixture'>")
        self.assertEqual(str(jpy.get_type('[I')), "<class '[I'>")

    def test_class_level_access_resolves_unresolved_type(self):
        lazy = jpy.get_type('java.util.zip.CRC32', resolve=False)
        self.assertTrue(hasattr(lazy, 'getValue'))

    def test_dunder_access_does_not_resolve(self):
        lazy = jpy.get_type('java.util.zip.Adler32', resolve=False)
        self.assertNotIn('getValue', lazy.__dict__)
        self.assertEqual(lazy.__name__, 'Adler32')
        self.assertNotIn('getValue', lazy.__dict__)


class TestStaticFields(unittest.TestCase):
    def setUp(self):
        self.F = jpy.get_type('org.jpy.ffm.fixtures.StaticFieldsFixture')
        self.Child = jpy.get_type('org.jpy.ffm.fixtures.StaticFieldsChild')
        self.F.counter = 7
        self.F.label = 'start'
        self.F.anything = None

    def test_class_read_and_write_are_live(self):
        self.assertEqual(self.F.counter, 7)
        self.F.counter = 41
        self.assertEqual(self.F.bump(), 42)
        self.assertEqual(self.F.counter, 42)

    def test_string_and_object_fields(self):
        self.F.label = 'changed'
        self.assertEqual(self.F.getLabel(), 'changed')
        self.F.anything = 'a str'
        self.assertEqual(self.F.anything, 'a str')
        self.F.anything = None
        self.assertIsNone(self.F.anything)

    def test_access_through_instances(self):
        obj = self.F()
        self.assertEqual(obj.counter, 7)
        obj.counter = 100
        self.assertEqual(self.F.counter, 100)
        self.assertEqual(obj.instanceField, 3)

    def test_access_through_subclass(self):
        self.assertEqual(self.Child.counter, 7)
        self.Child.counter = 5
        self.assertEqual(self.F.counter, 5)
        self.assertEqual(self.Child.childCounter, 1)

    def test_static_final_stays_a_plain_value(self):
        self.assertEqual(self.F.CONSTANT, 99)

    def test_method_keeps_its_name_over_a_static_field(self):
        self.assertEqual(self.F.shared(), 2)

    def test_private_static_fields_stay_invisible(self):
        self.assertFalse(hasattr(self.F, 'hidden'))
        self.assertEqual(self.F.getHidden(), 5)

    def test_wrong_type_raises_and_leaves_value(self):
        self.F.counter = 5
        with self.assertRaises(ValueError):
            self.F.counter = 'not an int'
        self.assertEqual(self.F.counter, 5)


if __name__ == '__main__':
    print('\nRunning ' + __file__)
    unittest.main()
