import sys, time, timeit, threading
sys.path.insert(0, '/Users/jianfengmao/git/jpy')
import jpyutil
jpyutil.init_jvm(jvm_maxmem='512M', jvm_classpath=[
    '/Users/jianfengmao/git/jpy/target/jpy-2.1.0-SNAPSHOT.jar',
    '/Users/jianfengmao/git/jpy/.claude/worktrees/ffm-prototype/ffm-prototype/bench'])
import jpy
print("python:", sys.version.split('(')[0], "GIL enabled:", getattr(sys, '_is_gil_enabled', lambda: True)())
Integer = jpy.get_type('java.lang.Integer')

def hammer(n):
    s = Integer.sum
    for i in range(n):
        s(1, 2)

hammer(200_000)  # warmup
t0 = time.perf_counter(); hammer(1_000_000); single_s = time.perf_counter() - t0
print(f"P->J single thread:  {single_s * 1e9 / 1e6:8.1f} ns/op")

ts = [threading.Thread(target=hammer, args=(250_000,)) for _ in range(4)]
t0 = time.perf_counter()
[t.start() for t in ts]; [t.join() for t in ts]
multi_s = time.perf_counter() - t0
print(f"P->J 4 threads:      {1.0/multi_s:8.2f} Mcalls/s (single was {1.0/single_s:.2f}) -> scaling x{single_s/multi_s:.2f}")

Bench = jpy.get_type('JpyCallbackBench')
def py_add(a): return a + 1
ns = Bench.bench(py_add, 500_000)
print(f"J->P single thread:  {ns:8.1f} ns/op")
