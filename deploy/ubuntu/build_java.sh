#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

command -v java >/dev/null 2>&1 || { echo "[ERROR] Java is not installed." >&2; exit 1; }
command -v mvn >/dev/null 2>&1 || { echo "[ERROR] Maven is not installed." >&2; exit 1; }

JAVA_VERSION="$(java -version 2>&1 | head -n 1)"
echo "Using ${JAVA_VERSION}"
echo "Running a clean Java verification and building the executable JAR..."
mvn -f "${PROJECT_ROOT}/backend-java/pom.xml" clean verify
echo "Built: ${PROJECT_ROOT}/backend-java/target/simplelabel-java-1.0.0-SNAPSHOT.jar"
