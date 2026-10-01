"""
Runs jpy's Python test files, unmodified, against the FFM jpy and prints a scoreboard.

Each file runs in its own process (a JVM can be created once per process), with a
timeout, from the repository root. Logs go to ffm/build/suite/<file>.log.

Usage: <python-with-numpy> ffm/suite.py [test files...]   (default: setup.py's list)
Env:   PYTHON  interpreter for the tests (default: the one running this script)
       JAVA_HOME  JDK 22+ (default: sdkman Temurin 25)
"""
import os
import re
import signal
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOGS = os.path.join(ROOT, 'ffm', 'build', 'suite')
TIMEOUT = int(os.environ.get('SUITE_TIMEOUT', '180'))

# setup.py: python_java_rt_tests + python_java_jpy_tests (jpy_perf_test.py is excluded there too)
DEFAULT = [
    'jpy_rt_test.py', 'jpy_mt_test.py', 'jpy_diag_test.py',
    'jpy_array_test.py', 'jpy_field_test.py', 'jpy_retval_test.py', 'jpy_exception_test.py',
    'jpy_py_exception_chain_test.py', 'jpy_overload_test.py', 'jpy_typeconv_test.py',
    'jpy_typeconv_test_pyobj.py', 'jpy_typeres_test.py', 'jpy_modretparam_test.py',
    'jpy_translation_test.py', 'jpy_gettype_test.py', 'jpy_reentrant_test.py',
    'jpy_java_embeddable_test.py', 'jpy_obj_test.py', 'jpy_eval_exec_test.py',
    'jpy_mt_eval_exec_test.py', 'jpy_reachability_fence_test.py', 'jpy_cleanup_thread_test.py',
]


def run(test, python, env):
    log_path = os.path.join(LOGS, test + '.log')
    start = time.time()
    try:
        proc = subprocess.run([python, os.path.join('src', 'test', 'python', test), '-v'],
                              cwd=ROOT, env=env, capture_output=True, text=True, timeout=TIMEOUT)
        out = proc.stdout + proc.stderr
        code = proc.returncode
    except subprocess.TimeoutExpired as e:
        out = (e.stdout or b'').decode(errors='replace') if isinstance(e.stdout, bytes) else (e.stdout or '')
        out += (e.stderr or b'').decode(errors='replace') if isinstance(e.stderr, bytes) else (e.stderr or '')
        code = None
    elapsed = time.time() - start
    with open(log_path, 'w') as f:
        f.write(out)

    ran = re.search(r'^Ran (\d+) tests?', out, re.M)
    ran = int(ran.group(1)) if ran else 0
    fail = re.search(r'failures=(\d+)', out)
    err = re.search(r'errors=(\d+)', out)
    skip = re.search(r'skipped=(\d+)', out)
    fail = int(fail.group(1)) if fail else 0
    err = int(err.group(1)) if err else 0
    skip = int(skip.group(1)) if skip else 0

    if code is None:
        status = 'TIMEOUT'
    elif code < 0:
        status = 'CRASH (' + signal.Signals(-code).name + ')'
    elif 'Fatal Python error' in out or 'A fatal error has been detected by the Java Runtime' in out:
        status = 'CRASH'
    elif ran == 0:
        status = 'NO TESTS RAN'
    elif fail == 0 and err == 0:
        status = 'OK'
    else:
        status = 'FAILED'
    passed = ran - fail - err - skip
    return test, status, ran, passed, fail, err, skip, elapsed


def main():
    tests = sys.argv[1:] or DEFAULT
    python = os.environ.get('PYTHON', sys.executable)
    env = dict(os.environ)
    env.setdefault('JAVA_HOME', os.path.expanduser('~/.sdkman/candidates/java/25.0.3-tem'))
    env['PYTHONPATH'] = os.path.join(ROOT, 'ffm', 'python')
    os.makedirs(LOGS, exist_ok=True)

    print(f'python: {python}')
    print(f'{"test file":34s} {"status":16s} {"ran":>4s} {"pass":>5s} {"fail":>5s} {"err":>4s} {"skip":>5s} {"secs":>6s}')
    totals = [0, 0, 0, 0, 0]
    for test in tests:
        t, status, ran, passed, fail, err, skip, secs = run(test, python, env)
        totals = [a + b for a, b in zip(totals, [ran, passed, fail, err, skip])]
        print(f'{t:34s} {status:16s} {ran:4d} {passed:5d} {fail:5d} {err:4d} {skip:5d} {secs:6.1f}', flush=True)
    print(f'{"TOTAL":34s} {"":16s} {totals[0]:4d} {totals[1]:5d} {totals[2]:5d} {totals[3]:4d} {totals[4]:5d}')


if __name__ == '__main__':
    main()
