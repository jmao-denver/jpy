"""
Which org.jpy classes the JVM gets when Python creates it. jpy.py adds the jar bundled in the wheel
first on the class path, unless the caller's class path already starts its jpy entries with an FFM
jpy jar (org.jpyconsortium:jpy 3.x from Maven, as an application such as Deephaven ships it). Then
the caller's jar is used as is, so the JVM never sees two copies. No JVM is created here.
"""
import os
import shutil
import tempfile
import unittest
import zipfile

import jpy

SEP = os.pathsep


def make_jar(path, *classes):
    with zipfile.ZipFile(path, 'w') as z:
        for c in classes:
            z.writestr(c, b'')
    return path


class ClasspathTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.saved_override = os.environ.pop('JPY_FFM_CLASSPATH', None)
        self.ffm = make_jar(os.path.join(self.dir, 'jpy-3.0.0.jar'),
                            'org/jpy/PyLib.class', 'org/jpy/ffm/Bootstrap.class')
        self.c = make_jar(os.path.join(self.dir, 'jpy-2.1.0.jar'), 'org/jpy/PyLib.class')
        self.ext = make_jar(os.path.join(self.dir, 'deephaven-jpy-ext-0.40.0.jar'), 'io/deephaven/jpy/X.class')
        self.other = make_jar(os.path.join(self.dir, 'guava.jar'), 'com/google/X.class')
        self.bundled = jpy._ffm_classpath()

    def tearDown(self):
        shutil.rmtree(self.dir)
        if self.saved_override is not None:
            os.environ['JPY_FFM_CLASSPATH'] = self.saved_override
        else:
            os.environ.pop('JPY_FFM_CLASSPATH', None)

    def classpath(self, *entries):
        options = jpy._with_ffm_options(['-Xmx1g', '-Djava.class.path=' + SEP.join(entries)])
        [cp] = [o for o in options if o.startswith('-Djava.class.path=')]
        return cp[len('-Djava.class.path='):]

    def test_caller_ffm_jar_is_used_as_is(self):
        self.assertEqual(self.classpath(self.other, self.ffm), SEP.join([self.other, self.ffm]))

    def test_non_jpy_jar_with_jpy_in_its_name_is_skipped(self):
        self.assertEqual(self.classpath(self.ext, self.ffm), SEP.join([self.ext, self.ffm]))

    def test_caller_c_jar_gets_bundled_jar_first(self):
        self.assertEqual(self.classpath(self.c), SEP.join([self.bundled, self.c]))

    def test_c_jar_before_ffm_jar_gets_bundled_jar_first(self):
        # the JVM would load the C jpy's org.jpy.PyLib from the first jar
        self.assertEqual(self.classpath(self.c, self.ffm), SEP.join([self.bundled, self.c, self.ffm]))

    def test_no_jpy_jar_gets_bundled_jar_first(self):
        self.assertEqual(self.classpath(self.other), SEP.join([self.bundled, self.other]))

    def test_class_directory(self):
        classes = os.path.join(self.dir, 'jpy-classes')
        os.makedirs(os.path.join(classes, 'org', 'jpy', 'ffm'))
        for name in ('org/jpy/PyLib.class', 'org/jpy/ffm/Bootstrap.class'):
            open(os.path.join(classes, *name.split('/')), 'wb').close()
        self.assertEqual(self.classpath(classes), classes)

    def test_override_always_wins(self):
        os.environ['JPY_FFM_CLASSPATH'] = '/somewhere/jpy-ffm.jar'
        self.assertEqual(self.classpath(self.ffm), SEP.join(['/somewhere/jpy-ffm.jar', self.ffm]))

    def test_no_classpath_option(self):
        options = jpy._with_ffm_options(['-Xmx1g'])
        self.assertIn('-Djava.class.path=' + self.bundled, options)


if __name__ == '__main__':
    unittest.main()
