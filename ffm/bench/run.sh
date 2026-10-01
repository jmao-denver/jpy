#!/bin/zsh
# Bridge micro-benchmark: runs ffm/bench/bench.py on the C jpy and on the FFM jpy, with the same
# Python and JDK, and prints ns per call for each case.
# Usage: ffm/bench/run.sh   (C_JPY=<dir with the C jpy's jpy*.so, jdl*.so, jpyutil.py>, C_JPY_JAR=<its jar>)
here=${0:A:h}
wt=${here:h:h}
export JAVA_HOME="${JAVA_HOME:-$HOME/.sdkman/candidates/java/25.0.3-tem}"
PYTHON="${PYTHON:-$HOME/.local/bin/python3.12}"
C_JPY="${C_JPY:-$HOME/git/jpy/build/lib.macosx-11.0-arm64-cpython-312}"
C_JPY_JAR="${C_JPY_JAR:-$HOME/git/jpy/target/jpy-2.1.0-SNAPSHOT.jar}"
classes=$wt/ffm/build/bench
rm -rf $classes && mkdir -p $classes
"$JAVA_HOME/bin/javac" --release 11 -Xlint:-options -cp $wt/ffm/build/classes -d $classes $here/bench/Bench.java || exit 1
cd $wt
echo "== C jpy ($C_JPY)"
PYTHONPATH=$C_JPY "$PYTHON" $here/bench.py $classes $C_JPY_JAR 2>&1 | grep -v WARNING
echo "== FFM jpy"
PYTHONPATH=$wt/ffm/python "$PYTHON" $here/bench.py $classes 2>&1 | grep -v WARNING
