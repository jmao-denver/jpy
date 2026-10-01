#!/bin/zsh
# Runs a Java main class or single-file source (Java-first: Java starts Python) against the FFM jpy.
# Usage: ffm/jrun.sh Main.java [args...]   (PYTHON=... selects the interpreter)
set -e
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-$HOME/.sdkman/candidates/java/25.0.3-tem}"
PYTHON="${PYTHON:-$HOME/.local/bin/python3.12}"
PYTHON_LIB="$(PYTHONPATH="$PWD/ffm/python" "$PYTHON" -c 'import jpy; print(jpy._libpython_path())')"
# An embedded interpreter finds its standard library from PYTHONHOME, not from a python executable.
export PYTHONHOME="${PYTHONHOME:-$("$PYTHON" -c 'import sys; print(sys.base_prefix)')}"
# LAUNCHER wraps the JVM, e.g. LAUNCHER="lldb -b -s cmds --" for a native backtrace.
exec $=LAUNCHER "$JAVA_HOME/bin/java" --enable-native-access=ALL-UNNAMED -Xmx512m $=JAVA_OPTS \
    -Djpy.pythonLib="$PYTHON_LIB" -Djpy.jpyLib="$PWD/ffm/python/jpy.py" \
    -cp "ffm/build/classes:target/test-classes:$HOME/.m2/repository/junit/junit/4.13.2/junit-4.13.2.jar:$HOME/.m2/repository/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar" "$@"
