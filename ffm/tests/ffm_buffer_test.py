"""
Buffer protocol parity with the C jpy, as measured on the C jpy (2026-10-01): a view of a Java
primitive array is a snapshot copied on first export, and the copy is written back to the Java
array only when the Python wrapper is deallocated, and only if some export was writable. Also
jpy.byte_buffer, which jpy's own suite does not cover but Deephaven uses.
"""
import gc
import struct
import unittest

import jpyutil

jpyutil.init_jvm(jvm_maxmem='512M', jvm_classpath=['target/test-classes'])
import jpy

Arrays = jpy.get_type('java.util.Arrays')
Array = jpy.get_type('java.lang.reflect.Array')
ArrayList = jpy.get_type('java.util.ArrayList')


class TestArrayBufferSemantics(unittest.TestCase):
    def test_snapshot_and_deferred_write_back(self):
        # Same steps and same expected results as the probe run against the C jpy.
        holder = ArrayList()
        arr = jpy.array('int', [1, 2, 3])
        holder.add(arr)
        struct.pack_into('i', arr, 0, 100)  # writable export
        view = memoryview(arr)
        self.assertEqual(Arrays.toString(holder.get(0)), '[1, 2, 3]')
        self.assertEqual(view.tolist(), [100, 2, 3])

        Array.setInt(holder.get(0), 1, 200)
        self.assertEqual(Arrays.toString(holder.get(0)), '[1, 200, 3]')
        self.assertEqual(view.tolist(), [100, 2, 3])

        view.release()
        del view
        gc.collect()
        self.assertEqual(Arrays.toString(holder.get(0)), '[1, 200, 3]')

        del arr
        gc.collect()
        # The snapshot is written back on dealloc and overwrites Java's later write (C jpy behavior).
        self.assertEqual(Arrays.toString(holder.get(0)), '[100, 2, 3]')

    def test_read_only_exports_never_write_back(self):
        holder = ArrayList()
        arr = jpy.array('int', [1, 2, 3])
        holder.add(arr)
        view = memoryview(arr)
        self.assertTrue(view.readonly)
        Array.setInt(holder.get(0), 0, 7)
        view.release()
        del view, arr
        gc.collect()
        self.assertEqual(Arrays.toString(holder.get(0)), '[7, 2, 3]')

    def test_formats_and_shape(self):
        expected = {'boolean': ('B', 1), 'char': ('H', 2), 'byte': ('b', 1), 'short': ('h', 2),
                    'int': ('i', 4), 'long': ('q', 8), 'float': ('f', 4), 'double': ('d', 8)}
        for name, (fmt, size) in expected.items():
            m = memoryview(jpy.array(name, 3))
            self.assertEqual((m.format, m.itemsize, m.shape, m.strides, m.ndim), (fmt, size, (3,), (size,), 1), name)
            m.release()

    def test_empty_array(self):
        m = memoryview(jpy.array('double', 0))
        self.assertEqual((m.nbytes, m.shape), (0, (0,)))
        m.release()

    def test_object_arrays_have_no_buffer(self):
        with self.assertRaises(TypeError):
            memoryview(jpy.array('java.lang.String', 2))


class TestByteBuffer(unittest.TestCase):
    def test_read_only_direct_buffer_over_python_memory(self):
        data = b'abc'
        bb = jpy.byte_buffer(data)
        self.assertTrue(bb.isDirect())
        self.assertTrue(bb.isReadOnly())
        self.assertEqual(bb.capacity(), 3)
        self.assertEqual(bb.get(0), 97)
        utf8 = jpy.get_type('java.nio.charset.StandardCharsets').UTF_8
        self.assertEqual(utf8.decode(bb).toString(), 'abc')

    def test_shares_memory(self):
        data = bytearray(b'xyz')
        bb = jpy.byte_buffer(data)
        data[0] = ord('Q')
        self.assertEqual(bb.get(0), ord('Q'))

    def test_rejects_non_buffers(self):
        with self.assertRaises(ValueError):
            jpy.byte_buffer('not a buffer')


if __name__ == '__main__':
    print('\nRunning ' + __file__)
    unittest.main()
