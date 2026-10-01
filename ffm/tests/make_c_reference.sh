#!/bin/zsh
# Records what introspection shows on the C jpy, for ffm_introspection_test.py to compare against:
# one file per interpreter in ffm/tests/data. Needs C jpy builds (jpy*.so, jdl*.so, jpyutil.py) and
# their jar; the defaults are a jpy checkout at ~/git/jpy built with `python setup.py build`.
# Usage: ffm/tests/make_c_reference.sh
here=${0:A:h}
wt=${here:h:h}
export JAVA_HOME="${JAVA_HOME:-$HOME/.sdkman/candidates/java/25.0.3-tem}"
C_JPY_BUILD="${C_JPY_BUILD:-$HOME/git/jpy/build}"
C_JPY_JAR="${C_JPY_JAR:-$HOME/git/jpy/target/jpy-2.1.0-SNAPSHOT.jar}"
UV="$HOME/.local/share/uv/python"
mkdir -p $here/data
cd $wt
for tag py in \
    3.12 cpython-3.12.12-macos-aarch64-none/bin/python3.12 \
    3.13 cpython-3.13.11-macos-aarch64-none/bin/python3.13 \
    3.14 cpython-3.14.2-macos-aarch64-none/bin/python3.14 \
    3.13t cpython-3.13.9+freethreaded-macos-aarch64-none/bin/python3.13t \
    3.14t cpython-3.14.0+freethreaded-macos-aarch64-none/bin/python3.14t
do
    build=$C_JPY_BUILD/lib.macosx-11.0-arm64-cpython-${tag//./}
    out=$here/data/c_jpy_introspection_$tag.txt
    PYTHONPATH=$build $UV/$py $here/introspection_probe.py $C_JPY_JAR 2>/dev/null | grep ' = ' > $out
    echo "$tag: $(wc -l < $out) lines from $build"
done
