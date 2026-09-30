#!/bin/zsh
# Runs a Python script against the FFM jpy, from the repository root.
# Usage: ffm/py.sh path/to/script.py [args...]   (PYTHON=... selects the interpreter)
set -e
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-$HOME/.sdkman/candidates/java/25.0.3-tem}"
PYTHON="${PYTHON:-$HOME/.local/bin/python3.12}"
PYTHONPATH="$PWD/ffm/python" exec "$PYTHON" "$@"
