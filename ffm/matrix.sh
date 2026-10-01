#!/bin/zsh
# Runs one jpy test file against the FFM jpy on every uv-managed CPython 3.12+ here.
# Usage: ffm/matrix.sh jpy_gettype_test.py     (or: ffm/matrix.sh junit, for jpy's Java tests)
cd "$(dirname "$0")/.."
TEST="$1"
for py in \
    cpython-3.12.12-macos-aarch64-none/bin/python3.12 \
    cpython-3.13.11-macos-aarch64-none/bin/python3.13 \
    cpython-3.14.2-macos-aarch64-none/bin/python3.14 \
    cpython-3.13.9+freethreaded-macos-aarch64-none/bin/python3.13t \
    cpython-3.14.0+freethreaded-macos-aarch64-none/bin/python3.14t
do
    exe="$HOME/.local/share/uv/python/$py"
    [[ -x "$exe" ]] || { echo "$py: not installed"; continue; }
    if [[ "$TEST" == junit ]]; then
        # one result per section: jpy's JUnit tests, then the FFM extras
        result=$(PYTHON="$exe" ./ffm/junit.sh 2>&1 | grep -E '^(OK|Tests run)' | tr '\n' ' ')
    else
        result=$(PYTHON="$exe" ./ffm/test.sh "$TEST" 2>&1 | grep -E '^(OK|FAILED)' | tail -1)
    fi
    echo "${py:t}: ${result:-CRASHED}"
done
