#!/bin/sh
# Runs ffm/ci.py in Linux containers, one per Python version.
# Usage: ffm/docker/run.sh [3.12 3.13 3.14]
cd "$(dirname "$0")/../.." || exit 1
status=0
for v in "${@:-3.12 3.13 3.14}"; do
    for version in $v; do
        tag="jpy-ffm-test:py$version"
        docker build -q --build-arg PYTHON_VERSION="$version" -t "$tag" ffm/docker >/dev/null || exit 1
        echo "######## Linux $(uname -m), Python $version"
        docker run --rm -v "$PWD":/src "$tag" python ffm/ci.py || status=1
    done
done
exit $status
