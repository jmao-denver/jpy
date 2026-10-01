#!/bin/zsh
# Runs jpy's Java JUnit tests (Java-first: Java starts Python) against the FFM jpy, in one JVM,
# the way Maven's surefire does by default, then the FFM-only JUnit tests in ffm/tests/java.
# Run ffm/build.sh and ffm/build-fixtures.sh first.
# Usage: ffm/junit.sh [test class ...]   (PYTHON=... selects the interpreter)
set -e
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-$HOME/.sdkman/candidates/java/25.0.3-tem}"
PYTHON="${PYTHON:-$HOME/.local/bin/python3.12}"
JUNIT_JAR="${JUNIT_JAR:-$HOME/.m2/repository/junit/junit/4.13.2/junit-4.13.2.jar}"
HAMCREST_JAR="${HAMCREST_JAR:-$HOME/.m2/repository/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar}"

PYTHON_LIB="$(PYTHONPATH="$PWD/ffm/python" "$PYTHON" -c 'import jpy; print(jpy._libpython_path())')"
# An embedded interpreter finds its standard library from PYTHONHOME, not from a python executable.
export PYTHONHOME="${PYTHONHOME:-$("$PYTHON" -c 'import sys; print(sys.base_prefix)')}"

junit() {
    "$JAVA_HOME/bin/java" --enable-native-access=ALL-UNNAMED -Xmx512m \
        -Djpy.pythonLib="$PYTHON_LIB" -Djpy.jpyLib="$PWD/ffm/python/jpy.py" \
        -cp "ffm/build/classes:target/test-classes:ffm/build/tests:$JUNIT_JAR:$HAMCREST_JAR" \
        org.junit.runner.JUnitCore "$@"
}

if (( $# > 0 )); then
    junit "$@"
    exit
fi

# What Maven runs: surefire's default includes (**/*Test.java), so not EmbeddableTestJunit or
# UseCases. EmbeddableTestJunit fails on the C jpy too: EmbeddableTest.assertFalse throws when its
# argument is false.
rc=0
echo "== jpy's JUnit tests (src/test/java, unmodified)"
junit org.jpy.JavaReflectionTest org.jpy.LifeCycleTest org.jpy.PyLibTest org.jpy.PyLibWithSysPathTest \
    org.jpy.PyModuleTest org.jpy.PyObjectTest org.jpy.PyProxyTest org.jpy.jsr223.Jsr223Test || rc=1
echo "== FFM extras (ffm/tests/java, FFM-only behavior)"
junit org.jpy.FfmRestartSafetyTest || rc=1
exit $rc
