#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
command -v mvn >/dev/null || { echo "Maven 3.8+ is required and must be on PATH."; exit 1; }
java -version
mvn -q -DskipTests package
mvn -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
DEPS="$(cat target/classpath.txt)"
java -Dbitbrains.dir=data/bitbrains/fastStorage/2013-8 -Dbitbrains.vms=0 -Dbitbrains.stepsPerDay=288 ${EXTRA:-} -cp "target/classes:${DEPS}" org.example.TraceBatchRunner
