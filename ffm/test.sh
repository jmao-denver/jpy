#!/bin/zsh
# Runs one of jpy's own Python test files, unmodified, against the FFM jpy.
# Usage: ffm/test.sh jpy_gettype_test.py [unittest args...]
set -e
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-$HOME/.sdkman/candidates/java/25.0.3-tem}"
PYTHON="${PYTHON:-$HOME/.local/bin/python3.12}"
TEST="$1"; shift
PYTHONPATH="$PWD/ffm/python" exec "$PYTHON" "src/test/python/$TEST" "$@"
