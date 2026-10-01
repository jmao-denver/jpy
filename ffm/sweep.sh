#!/bin/zsh
# Full sweep: jpy's Python suite plus the FFM extras (ffm/suite.py) on every uv-managed CPython 3.12+
# here, each in its own venv with numpy (made under ffm/build/venvs on first use), then jpy's JUnit
# tests on the same interpreters. Run ffm/build.sh and ffm/build-fixtures.sh first.
# Usage: ffm/sweep.sh
cd "$(dirname "$0")/.."
UV=$HOME/.local/share/uv/python
VENVS=ffm/build/venvs
rc=0
for tag py in \
    3.12 cpython-3.12.12-macos-aarch64-none/bin/python3.12 \
    3.13 cpython-3.13.11-macos-aarch64-none/bin/python3.13 \
    3.14 cpython-3.14.2-macos-aarch64-none/bin/python3.14 \
    3.13t cpython-3.13.9+freethreaded-macos-aarch64-none/bin/python3.13t \
    3.14t cpython-3.14.0+freethreaded-macos-aarch64-none/bin/python3.14t
do
    [[ -x $UV/$py ]] || { echo "$tag: not installed"; continue; }
    v=$VENVS/$tag
    if [[ ! -x $v/bin/python ]]; then
        uv venv -q -p $UV/$py $v && VIRTUAL_ENV=$PWD/$v uv pip install -q numpy
    fi
    log=ffm/build/sweep-$tag.log
    PYTHON=$PWD/$v/bin/python ./ffm/py.sh ffm/suite.py > $log 2>&1 || rc=1
    echo "$tag python: $(grep TOTAL $log | tr -s ' ' | tr '\n' '|')"
done
./ffm/matrix.sh junit || rc=1
exit $rc
