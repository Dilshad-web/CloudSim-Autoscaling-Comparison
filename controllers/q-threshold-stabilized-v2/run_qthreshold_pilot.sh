#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
command -v mvn >/dev/null || { echo "Maven is not installed or not on PATH."; exit 1; }
java -version 2>&1 | grep -Eq '21|22|23|24|25' || echo "WARNING: this project requires Java 21 or newer; verify JAVA_HOME and PATH."
mvn -q -DskipTests package
mvn -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
DEPS="$(cat target/classpath.txt)"
java -Dbitbrains.days=1 -Dbitbrains.fresh=true -cp "target/classes:${DEPS}" org.example.QThresholdTraceBatchRunner
