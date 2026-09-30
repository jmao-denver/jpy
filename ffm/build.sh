#!/bin/zsh
# Compiles the FFM jpy Java sources into ffm/build/classes.
set -e
cd "$(dirname "$0")"
JAVA_HOME="${JAVA_HOME:-$HOME/.sdkman/candidates/java/25.0.3-tem}"
rm -rf build/classes
mkdir -p build/classes
"$JAVA_HOME/bin/javac" --release 22 -Xlint:-options -d build/classes $(find java -name '*.java')
echo "built ffm/build/classes"
