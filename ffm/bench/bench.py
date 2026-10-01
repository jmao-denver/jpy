"""jpy bridge micro-benchmark. Run with the C jpy or the FFM jpy on PYTHONPATH; same script for both.
Prints ns per call (median of 5 runs) for each case."""
import statistics
import sys
import time

import jpyutil
jpyutil.init_jvm(jvm_maxmem='512M', jvm_classpath=sys.argv[1:])
import jpy

N = 200_000
Bench = jpy.get_type('bench.Bench')
b = Bench()
String = jpy.get_type('java.lang.String')
s = String('hello')
Math = jpy.get_type('java.lang.Math')
ArrayList = jpy.get_type('java.util.ArrayList')
lst = ArrayList()
lst.add('x')
Integer = jpy.get_type('java.lang.Integer')


def py_nop():
    return 0


def time_loop(fn):
    runs = []
    for _ in range(6):
        t0 = time.perf_counter_ns()
        fn()
        runs.append((time.perf_counter_ns() - t0) / N)
    return statistics.median(runs[1:])  # first run is warm-up


cases = {
    'python baseline: call a Python function': lambda: [py_nop() for _ in range(N)],
    'static, no args: Bench.nop()': lambda: [Bench.nop() for _ in range(N)],
    'instance, 1 int: b.inst(7)': lambda: [b.inst(7) for _ in range(N)],
    'String.length() on a Java String': lambda: [s.length() for _ in range(N)],
    'overloads, int arg: Bench.overloaded(7)': lambda: [Bench.overloaded(7) for _ in range(N)],
    'overloads, str arg: Bench.overloaded("a")': lambda: [Bench.overloaded('a') for _ in range(N)],
    'overloads, float arg: Bench.overloaded(1.5)': lambda: [Bench.overloaded(1.5) for _ in range(N)],
    'Math.max(3, 4) (4 overloads)': lambda: [Math.max(3, 4) for _ in range(N)],
    '3 Object args (boxing): Bench.objects(1, "a", 2.5)': lambda: [Bench.objects(1, 'a', 2.5) for _ in range(N)],
    'returns a Java object: lst.get(0)': lambda: [lst.get(0) for _ in range(N)],
    'construct: Integer(5)': lambda: [Integer(5) for _ in range(N)],
    'attribute only: s.length (bind, no call)': lambda: [s.length for _ in range(N)],
}

for name, fn in cases.items():
    print('%-55s %8.0f ns' % (name, time_loop(fn)))


def java_side(method, callable_):
    getattr(Bench, method)(callable_, N)  # warm-up
    return statistics.median(getattr(Bench, method)(callable_, N) for _ in range(5))


print('%-55s %8.0f ns' % ('Java -> Python, PyObject result: fn(i)', java_side('callPython', lambda i: i)))
print('%-55s %8.0f ns' % ('Java -> Python, Integer result: fn(i)', java_side('callPythonValue', lambda i: i)))
