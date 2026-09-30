#!/bin/zsh
# Compiles jpy's Java test fixtures into target/test-classes, where jpy's Python
# tests expect them (they pass jvm_classpath=['target/test-classes']).
# The fixtures only need org.jpy.* at compile time; the C jpy's jar provides it.
set -e
cd "$(dirname "$0")/.."
JAVA_HOME="${JAVA_HOME:-$HOME/.sdkman/candidates/java/25.0.3-tem}"
JPY_JAR="${JPY_JAR:-$HOME/git/jpy/target/jpy-2.1.0-SNAPSHOT.jar}"
JUNIT_JAR="${JUNIT_JAR:-$HOME/.m2/repository/junit/junit/4.13.2/junit-4.13.2.jar}"
mkdir -p target/test-classes
"$JAVA_HOME/bin/javac" --release 11 -Xlint:-options -cp "$JPY_JAR:$JUNIT_JAR" -d target/test-classes \
    $(find src/test/java/org/jpy/fixtures -name '*.java')
echo "built target/test-classes"
