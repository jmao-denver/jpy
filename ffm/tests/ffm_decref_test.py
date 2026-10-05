"""
PyLib.decRef and decRefs, the calls behind PyObject.close() and the cleanup thread. Like the C jpy,
they read the object's reference count first and skip an object whose count is already 0 or less.
Reading the count needs the object header layout, which differs in free-threaded builds, so a wrong
read would show up here as a decRef that was skipped.
"""
import sys
import unittest

import jpyutil

jpyutil.init_jvm(jvm_maxmem='512M', jvm_classpath=['target/test-classes'])
import jpy

PyLibImpl = jpy.get_type('org.jpy.ffm.PyLibImpl')


class DecRefTest(unittest.TestCase):

    def test_decref_balances_incref(self):
        o = object()
        before = sys.getrefcount(o)
        PyLibImpl.incRef(id(o))
        self.assertEqual(sys.getrefcount(o), before + 1)
        PyLibImpl.decRef(id(o))
        self.assertEqual(sys.getrefcount(o), before)

    def test_decrefs_balances_increfs(self):
        objects = [object() for _ in range(5)]
        before = [sys.getrefcount(o) for o in objects]
        pointers = []
        for o in objects:
            for _ in range(3):
                PyLibImpl.incRef(id(o))
                pointers.append(id(o))
        del o  # the loop variable holds a reference too
        self.assertEqual([sys.getrefcount(o) for o in objects], [b + 3 for b in before])
        PyLibImpl.decRefs(jpy.array('long', pointers), len(pointers))
        self.assertEqual([sys.getrefcount(o) for o in objects], before)

    def test_immortal_object_is_left_alone(self):
        PyLibImpl.decRef(id(None))
        PyLibImpl.decRefs(jpy.array('long', [id(True)] * 3), 3)
        self.assertIsNone(None)


if __name__ == '__main__':
    unittest.main()
