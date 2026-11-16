#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
command -v mvn >/dev/null || { echo "Maven is not installed or not on PATH."; exit 1; }
java -version 2>&1 | grep -Eq '21|22|23|24|25' || echo "WARNING: this project requires Java 21 or newer; verify JAVA_HOME and PATH."
echo "This will archive results_qthreshold and start a new 30-day experiment from Day 1."
mvn -q -DskipTests package
mvn -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
DEPS="$(cat target/classpath.txt)"
java -Dbitbrains.days=30 -Dbitbrains.fresh=true -cp "target/classes:${DEPS}" org.example.QThresholdTraceBatchRunner
