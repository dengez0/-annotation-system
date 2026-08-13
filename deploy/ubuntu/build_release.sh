#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
OUTPUT_DIR="${1:-${PROJECT_ROOT}/dist}"

git -C "${PROJECT_ROOT}" diff --quiet
git -C "${PROJECT_ROOT}" diff --cached --quiet
[[ -z "$(git -C "${PROJECT_ROOT}" status --porcelain)" ]] || {
    echo "[ERROR] Refusing to package a dirty or untracked working tree." >&2
    exit 1
}

COMMIT="$(git -C "${PROJECT_ROOT}" rev-parse HEAD)"
RELEASE_ID="simplelabel-${COMMIT:0:12}"
STAGING="$(mktemp -d)"
trap 'rm -rf "${STAGING}"' EXIT

bash "${SCRIPT_DIR}/build_java.sh"
mkdir -p "${OUTPUT_DIR}" "${STAGING}/${RELEASE_ID}/backend-java/target"
git -C "${PROJECT_ROOT}" archive HEAD \
    deploy/ubuntu \
    static \
    services/__init__.py \
    services/yolo_backend.py \
    services/yolo_legacy_loader.py \
    services/yolo_result_parser.py \
    yolo-worker \
    yolov5 | tar -x -C "${STAGING}/${RELEASE_ID}"
cp "${PROJECT_ROOT}/backend-java/target/simplelabel-java-1.0.0-SNAPSHOT.jar" \
   "${STAGING}/${RELEASE_ID}/backend-java/target/"
if [[ -d "${PROJECT_ROOT}/wheelhouse" ]]; then
    (cd "${PROJECT_ROOT}/wheelhouse" && sha256sum --check --strict SHA256SUMS)
    cp -a "${PROJECT_ROOT}/wheelhouse" "${STAGING}/${RELEASE_ID}/"
else
    echo "[ERROR] Missing verified wheelhouse. Run build_wheelhouse.sh first." >&2
    exit 1
fi
printf '%s\n' "${COMMIT}" > "${STAGING}/${RELEASE_ID}/GIT_COMMIT"
(cd "${STAGING}/${RELEASE_ID}" && find . -type f ! -path './SHA256SUMS' -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS)
tar -C "${STAGING}" -czf "${OUTPUT_DIR}/${RELEASE_ID}.tar.gz" "${RELEASE_ID}"
sha256sum "${OUTPUT_DIR}/${RELEASE_ID}.tar.gz" > "${OUTPUT_DIR}/${RELEASE_ID}.tar.gz.sha256"
echo "Built ${OUTPUT_DIR}/${RELEASE_ID}.tar.gz"
