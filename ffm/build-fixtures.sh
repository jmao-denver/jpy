#!/bin/zsh
# Compiles jpy's Java tests and fixtures (all of src/test/java) into target/test-classes, where
# jpy's Python tests expect them (they pass jvm_classpath=['target/test-classes']), and copies
# src/test/resources there, as Maven would. They compile against the FFM jpy (ffm/build.sh first).
set -e
cd "$(dirname "$0")/.."
JAVA_HOME="${JAVA_HOME:-$HOME/.sdkman/candidates/java/25.0.3-tem}"
JUNIT_JAR="${JUNIT_JAR:-$HOME/.m2/repository/junit/junit/4.13.2/junit-4.13.2.jar}"
HAMCREST_JAR="${HAMCREST_JAR:-$HOME/.m2/repository/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar}"
rm -rf target/test-classes
mkdir -p target/test-classes
"$JAVA_HOME/bin/javac" --release 11 -Xlint:-options -cp "ffm/build/classes:$JUNIT_JAR:$HAMCREST_JAR" \
    -d target/test-classes $(find src/test/java -name '*.java')
cp -R src/test/resources/. target/test-classes/
echo "built target/test-classes"
