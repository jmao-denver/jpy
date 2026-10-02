"""
Builds the FFM jpy and runs every test, on macOS, Linux and Windows, with the Python running this
script. The portable replacement for ffm/build.sh, build-fixtures.sh and junit.sh.

Usage: python ffm/ci.py [build] [python] [junit]     (no argument: all three, in that order)
Needs: JAVA_HOME pointing at JDK 25+, numpy installed in this Python (two of jpy's test files use it).
JUnit and Hamcrest come from ~/.m2 if present, else they are downloaded from Maven Central.
Exit code 0 only if every step passed.
"""
import glob
import os
import re
import shutil
import subprocess
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FFM = os.path.join(ROOT, 'ffm')
BUILD = os.path.join(FFM, 'build')
CLASSES = os.path.join(BUILD, 'classes')
TEST_CLASSES = os.path.join(ROOT, 'target', 'test-classes')
JARS = {
    'junit-4.13.2.jar': 'junit/junit/4.13.2/junit-4.13.2.jar',
    'hamcrest-core-1.3.jar': 'org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar',
}
# What Maven runs: surefire's default includes (**/*Test.java), so not EmbeddableTestJunit or UseCases.
JUNIT_CLASSES = ['org.jpy.JavaReflectionTest', 'org.jpy.LifeCycleTest', 'org.jpy.PyLibTest',
                 'org.jpy.PyLibWithSysPathTest', 'org.jpy.PyModuleTest', 'org.jpy.PyObjectTest',
                 'org.jpy.PyProxyTest', 'org.jpy.jsr223.Jsr223Test']
# Bugs in jpy's own tests, not in the bridge: they match Python's default repr against
# "at 0x[0-9a-f]+", but on Windows CPython prints the address in upper case (printf "%p").
# jpy's CI never runs the JUnit tests on Windows. Any other failure still fails the run.
KNOWN_JUNIT_FAILURES = {
    'win32': {'strNotDefined(org.jpy.PyObjectTest)', 'doesNotHaveStrToString(org.jpy.PyProxyTest)'},
}


def java_home():
    home = os.environ.get('JAVA_HOME')
    if not home:
        sys.exit('ffm/ci.py: set JAVA_HOME to a JDK 25+')
    return home


def tool(name):
    return os.path.join(java_home(), 'bin', name + ('.exe' if os.name == 'nt' else ''))


def sources(directory, relative_to=None):
    found = sorted(glob.glob(os.path.join(directory, '**', '*.java'), recursive=True))
    return [os.path.relpath(p, relative_to) if relative_to else p for p in found]


def javac(release, out, classpath, files):
    if os.path.isdir(out):
        shutil.rmtree(out)
    os.makedirs(out)
    # an argument file keeps the command short enough for Windows
    argfile = out + '.args'
    with open(argfile, 'w') as f:
        f.write('\n'.join('"%s"' % p.replace('\\', '/') for p in files))
    cmd = [tool('javac'), '--release', str(release), '-Xlint:-options', '-nowarn', '-d', out]
    if classpath:
        cmd += ['-cp', os.pathsep.join(classpath)]
    subprocess.run(cmd + ['@' + argfile], check=True)


def test_jars():
    lib = os.path.join(BUILD, 'lib')
    os.makedirs(lib, exist_ok=True)
    paths = []
    for name, rel in JARS.items():
        m2 = os.path.join(os.path.expanduser('~'), '.m2', 'repository', *rel.split('/'))
        local = os.path.join(lib, name)
        if os.path.exists(m2):
            paths.append(m2)
            continue
        if not os.path.exists(local):
            urllib.request.urlretrieve('https://repo1.maven.org/maven2/' + rel, local)
        paths.append(local)
    return paths


def build():
    """ffm/build.sh + ffm/build-fixtures.sh."""
    replaced = {os.path.relpath(p, os.path.join(FFM, 'java')) for p in sources(os.path.join(FFM, 'java', 'org', 'jpy'))
                if os.path.dirname(p) == os.path.join(FFM, 'java', 'org', 'jpy')}
    jpy_main = [p for p in sources(os.path.join(ROOT, 'src', 'main', 'java'))
                if os.path.relpath(p, os.path.join(ROOT, 'src', 'main', 'java')) not in replaced]
    javac(25, CLASSES, None, sources(os.path.join(FFM, 'java')) + jpy_main)
    javac(11, os.path.join(BUILD, 'fixtures'), None, sources(os.path.join(FFM, 'fixtures')))
    javac(11, TEST_CLASSES, [CLASSES] + test_jars(), sources(os.path.join(ROOT, 'src', 'test', 'java')))
    shutil.copytree(os.path.join(ROOT, 'src', 'test', 'resources'), TEST_CLASSES, dirs_exist_ok=True)
    print('built ffm/build/classes, ffm/build/fixtures, target/test-classes')
    return True


def python_suite():
    """jpy's Python test files, unmodified, then the FFM extras."""
    env = dict(os.environ, PYTHON=sys.executable)
    return subprocess.run([sys.executable, os.path.join(FFM, 'suite.py')], cwd=ROOT, env=env).returncode == 0


def junit():
    """jpy's JUnit tests, Java-first, as jpy's CI runs them (setup.py test -> mvn test)."""
    env = dict(os.environ, PYTHONPATH=os.path.join(FFM, 'python'))
    python_lib = subprocess.run([sys.executable, '-c', 'import jpy; print(jpy._libpython_path())'],
                                env=env, capture_output=True, text=True, check=True).stdout.strip()
    # An embedded interpreter finds its standard library from PYTHONHOME, not from a python executable.
    env['PYTHONHOME'] = sys.base_prefix
    cmd = [tool('java'), '--enable-native-access=ALL-UNNAMED', '-Xmx512m',
           # as setup.py's test_maven: never stop and restart CPython in one process (bcdev/jpy#70)
           '-Djpy.stopIsNoOp=true',
           '-Djpy.pythonLib=' + python_lib,
           '-Djpy.jpyLib=' + os.path.join(FFM, 'python', 'jpy.py'),
           '-cp', os.pathsep.join([CLASSES, TEST_CLASSES] + test_jars()),
           'org.junit.runner.JUnitCore'] + JUNIT_CLASSES
    print('\njpy JUnit tests (Java-first), pythonLib=' + python_lib, flush=True)
    proc = subprocess.Popen(cmd, cwd=ROOT, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, errors='replace')
    failed = []
    for line in proc.stdout:
        print(line, end='', flush=True)
        m = re.match(r'^\d+\) (\S+\(\S+\))$', line.strip())
        if m:
            failed.append(m.group(1))
    if proc.wait() == 0:
        return True
    known = KNOWN_JUNIT_FAILURES.get(sys.platform, set())
    if failed and set(failed) <= known:
        print('only known failures of jpy\'s own tests on %s: %s' % (sys.platform, ', '.join(sorted(failed))))
        return True
    return False


def main():
    steps = {'build': build, 'python': python_suite, 'junit': junit}
    chosen = sys.argv[1:] or list(steps)
    print('python %s on %s, JAVA_HOME=%s' % (sys.version.split()[0], sys.platform, java_home()), flush=True)
    ok = True
    for name in chosen:
        passed = steps[name]()
        print('ffm/ci.py %s: %s' % (name, 'OK' if passed else 'FAILED'), flush=True)
        ok &= passed
        if name == 'build' and not passed:
            break
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
