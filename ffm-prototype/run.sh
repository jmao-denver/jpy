#!/bin/zsh
# Usage: ./run.sh M1 [extra java args...]
set -e
cd "$(dirname "$0")"
JAVA_HOME="$HOME/.sdkman/candidates/java/25.0.3-tem"
# The uv/python-build-standalone dylib has a baked-in prefix of /install;
# without PYTHONHOME, Py_InitializeEx dies with "No module named 'encodings'".
export PYTHONHOME="${PYTHONHOME:-$HOME/.local/share/uv/python/cpython-3.12.12-macos-aarch64-none}"
CLASS="$1"; shift 2>/dev/null || true
mkdir -p out
"$JAVA_HOME/bin/javac" -d out $(find src -name '*.java')
# JAVA_OPTS: extra JVM flags, e.g. -Dlibpython=/path/to/libpython3.13t.dylib
exec "$JAVA_HOME/bin/java" --enable-native-access=ALL-UNNAMED ${=JAVA_OPTS:-} -cp out "ffm.$CLASS" "$@"
