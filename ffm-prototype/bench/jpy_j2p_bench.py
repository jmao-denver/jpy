import sys
sys.path.insert(0, '/Users/jianfengmao/git/jpy')
import jpyutil
jpyutil.init_jvm(jvm_maxmem='512M', jvm_classpath=[
    '/Users/jianfengmao/git/jpy/target/jpy-2.1.0-SNAPSHOT.jar',
    '/Users/jianfengmao/git/jpy/.claude/worktrees/ffm-prototype/ffm-prototype/bench'])
import jpy
Bench = jpy.get_type('JpyCallbackBench')
def py_add(a): return a + 1
ns = Bench.bench(py_add, 1_000_000)
print(f"jpy(JNI) Java->Python call py_add(1)        {ns:8.1f} ns/op")
