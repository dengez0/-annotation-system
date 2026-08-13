#!/usr/bin/env bash
set -euo pipefail

RELEASE_DIR="${1:-.}"
[[ -f "${RELEASE_DIR}/SHA256SUMS" ]] || { echo "[ERROR] Missing release SHA256SUMS." >&2; exit 1; }
(cd "${RELEASE_DIR}" && sha256sum --check --strict SHA256SUMS)
[[ -f "${RELEASE_DIR}/GIT_COMMIT" ]] || { echo "[ERROR] Missing GIT_COMMIT." >&2; exit 1; }
[[ -f "${RELEASE_DIR}/backend-java/target/simplelabel-java-1.0.0-SNAPSHOT.jar" ]] || {
    echo "[ERROR] Missing executable JAR." >&2
    exit 1
}
echo "Release verified: $(cat "${RELEASE_DIR}/GIT_COMMIT")"
