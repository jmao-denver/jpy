"""
Concurrency stress for the bridge, aimed at free-threaded CPython (3.13t, 3.14t), where Python
threads really run in parallel. jpy's own suite has little multi-threaded load. Many threads at once:
create Java types for the first time, resolve them, call overloaded methods, wrap and unwrap objects,
and call from Java back into Python. Every result is checked; any exception fails the test.
"""
import sys
import threading
import unittest

import jpyutil

jpyutil.init_jvm(jvm_maxmem='512M', jvm_classpath=['target/test-classes'])
import jpy

THREADS = 16
ROUNDS = 300

# Classes no other test has touched yet, so their types are created and resolved concurrently.
FRESH = ['java.util.concurrent.ConcurrentSkipListMap', 'java.util.TreeSet', 'java.util.ArrayDeque',
         'java.util.LinkedHashMap', 'java.util.BitSet', 'java.util.StringJoiner',
         'java.util.concurrent.atomic.AtomicLong', 'java.time.Duration', 'java.math.BigInteger',
         'java.util.zip.CRC32', 'java.util.Base64', 'java.text.DecimalFormat']


def run_threads(work):
    errors = []
    start = threading.Barrier(THREADS)

    def body(i):
        try:
            start.wait()
            work(i)
        except BaseException as e:  # noqa: B902 - any failure in a worker fails the test
            errors.append('%s: %r' % (threading.current_thread().name, e))

    threads = [threading.Thread(target=body, args=(i,), name='w%d' % i) for i in range(THREADS)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    return errors


class TestThreads(unittest.TestCase):
    def test_concurrent_type_creation_and_resolution(self):
        seen = [None] * THREADS

        def work(i):
            types = [jpy.get_type(name) for name in (FRESH[i % len(FRESH):] + FRESH[:i % len(FRESH)])]
            seen[i] = [id(t) for t in sorted(types, key=lambda t: t.jclassname)]
            sb = jpy.get_type('java.lang.StringBuilder')()
            for k in range(50):
                # a str argument: jpy, like the C jpy, maps a Python int to append(char)
                sb.append(str(k)).append('-')
            self.assertEqual(sb.length(), len(''.join('%d-' % k for k in range(50))))

        self.assertEqual(run_threads(work), [])
        # every thread got the very same Python type object for each Java class
        self.assertEqual(len({tuple(s) for s in seen}), 1)

    def test_concurrent_calls_and_conversions(self):
        Math = jpy.get_type('java.lang.Math')
        ArrayList = jpy.get_type('java.util.ArrayList')
        Integer = jpy.get_type('java.lang.Integer')

        def work(i):
            lst = ArrayList()
            for k in range(ROUNDS):
                self.assertEqual(Math.max(k, i), max(k, i))
                self.assertEqual(Math.max(float(k), 0.5), max(float(k), 0.5))
                lst.add('s%d' % k)
                lst.add(Integer.valueOf(k))
            self.assertEqual(lst.size(), 2 * ROUNDS)
            self.assertEqual(lst.get(2 * ROUNDS - 2), 's%d' % (ROUNDS - 1))
            arr = jpy.array('int', range(100))
            self.assertEqual(sum(arr), sum(range(100)))
            self.assertEqual(memoryview(arr).tolist(), list(range(100)))

        self.assertEqual(run_threads(work), [])

    def test_concurrent_java_to_python_callbacks(self):
        # Python -> Java -> Python on every thread: Java evaluates an expression in the caller's frame
        Fixture = jpy.get_type('org.jpy.fixtures.EvalTestFixture')
        lock = threading.Lock()
        counter = [0]

        def work(i):
            for k in range(ROUNDS // 3):
                x = i * 1000 + k
                self.assertEqual(Fixture.expression('x + 1'), x + 1)
                with lock:
                    counter[0] += 1

        self.assertEqual(run_threads(work), [])
        self.assertEqual(counter[0], THREADS * (ROUNDS // 3))

    def test_reports_gil_state(self):
        if hasattr(sys, '_is_gil_enabled'):
            print('\nGIL enabled:', sys._is_gil_enabled())


if __name__ == '__main__':
    print('\nRunning ' + __file__)
    unittest.main()
