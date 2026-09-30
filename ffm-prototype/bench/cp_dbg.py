import sys
sys.path.insert(0, '/Users/jianfengmao/git/jpy')
import jpyutil
jpyutil.init_jvm(jvm_maxmem='512M', jvm_classpath=[
    '/Users/jianfengmao/git/jpy/target/jpy-2.1.0-SNAPSHOT.jar',
    '/Users/jianfengmao/git/jpy/.claude/worktrees/ffm-prototype/ffm-prototype/bench'])
import jpy
Sys = jpy.get_type('java.lang.System')
print("java.class.path =", Sys.getProperty('java.class.path'))
