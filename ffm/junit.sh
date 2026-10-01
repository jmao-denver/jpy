#!/bin/zsh
# Runs jpy's Java JUnit tests (Java-first: Java starts Python) against the FFM jpy, in one JVM,
# the way jpy's CI does (setup.py test -> mvn test). Run ffm/build.sh and ffm/build-fixtures.sh first.
# Usage: ffm/junit.sh [test class ...]   (PYTHON=... selects the interpreter, JAVA_OPTS adds JVM options)
set -e
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-$HOME/.sdkman/candidates/java/25.0.3-tem}"
PYTHON="${PYTHON:-$HOME/.local/bin/python3.12}"
JUNIT_JAR="${JUNIT_JAR:-$HOME/.m2/repository/junit/junit/4.13.2/junit-4.13.2.jar}"
HAMCREST_JAR="${HAMCREST_JAR:-$HOME/.m2/repository/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar}"

PYTHON_LIB="$(PYTHONPATH="$PWD/ffm/python" "$PYTHON" -c 'import jpy; print(jpy._libpython_path())')"
# An embedded interpreter finds its standard library from PYTHONHOME, not from a python executable.
export PYTHONHOME="${PYTHONHOME:-$("$PYTHON" -c 'import sys; print(sys.base_prefix)')}"

# What Maven runs: surefire's default includes (**/*Test.java), so not EmbeddableTestJunit or
# UseCases. EmbeddableTestJunit fails on the C jpy too: EmbeddableTest.assertFalse throws when its
# argument is false.
if (( $# == 0 )); then
    set -- org.jpy.JavaReflectionTest org.jpy.LifeCycleTest org.jpy.PyLibTest org.jpy.PyLibWithSysPathTest \
        org.jpy.PyModuleTest org.jpy.PyObjectTest org.jpy.PyProxyTest org.jpy.jsr223.Jsr223Test
fi

# -Djpy.stopIsNoOp=true as in setup.py's test_maven: stopPython() keeps Python running, because
# stopping and restarting CPython in one process is not safe (bcdev/jpy#70). LifeCycleTest skips.
exec "$JAVA_HOME/bin/java" --enable-native-access=ALL-UNNAMED -Xmx512m -Djpy.stopIsNoOp=true $=JAVA_OPTS \
    -Djpy.pythonLib="$PYTHON_LIB" -Djpy.jpyLib="$PWD/ffm/python/jpy.py" \
    -cp "ffm/build/classes:target/test-classes:$JUNIT_JAR:$HAMCREST_JAR" \
    org.junit.runner.JUnitCore "$@"
