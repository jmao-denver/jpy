#!/bin/sh
# Runs ffm/ci.py in Linux containers, one per Python version.
# Usage: ffm/docker/run.sh [3.12 3.13 3.14]   (no arguments: also Ubuntu's static python3)
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
# Ubuntu's own python3: libpython built into the executable. Python-first only, see Dockerfile.ubuntu.
if [ $# -eq 0 ]; then
    docker build -q -f ffm/docker/Dockerfile.ubuntu -t jpy-ffm-test:ubuntu ffm/docker >/dev/null || exit 1
    echo "######## Linux $(uname -m), Ubuntu python3 (static libpython)"
    docker run --rm -v "$PWD":/src jpy-ffm-test:ubuntu python3 ffm/ci.py build python || status=1
fi
exit $status
