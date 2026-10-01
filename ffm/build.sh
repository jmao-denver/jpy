#!/bin/zsh
# Compiles the FFM jpy into ffm/build/classes: the FFM bridge (ffm/java) plus jpy's own Java
# sources (src/main/java), unchanged except for the classes ffm/java replaces (PyLib, DL).
set -e
cd "$(dirname "$0")"
JAVA_HOME="${JAVA_HOME:-$HOME/.sdkman/candidates/java/25.0.3-tem}"
rm -rf build/classes
mkdir -p build/classes
replaced=(${(f)"$(cd java && find org/jpy -maxdepth 1 -name '*.java')"})
jpy_sources=()
for f in $(cd ../src/main/java && find org/jpy -name '*.java'); do
    if (( ! ${replaced[(Ie)$f]} )); then
        jpy_sources+=("../src/main/java/$f")
    fi
done
"$JAVA_HOME/bin/javac" --release 25 -Xlint:-options -d build/classes $(find java -name '*.java') $jpy_sources
echo "built ffm/build/classes"
rm -rf build/fixtures
mkdir -p build/fixtures
"$JAVA_HOME/bin/javac" --release 11 -Xlint:-options -d build/fixtures $(find fixtures -name '*.java')
echo "built ffm/build/fixtures"
