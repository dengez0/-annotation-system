#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SOURCE_IMAGE="${SIMPLELABEL_TEST_SOURCE_IMAGE:-simplelabel:local}"
TARGET_IMAGE="${SIMPLELABEL_TEST_IMAGE:-simplelabel:test-29090}"
PRODUCTION_CONTAINER=simplelabel
TEMP_CONTAINER=simplelabel-test-image-prep
VALIDATOR_SOURCE="${PROJECT_ROOT}/deploy/ubuntu/validate_runtime_env.sh"
VALIDATOR_TARGET=/opt/simplelabel/current/deploy/ubuntu/validate_runtime_env.sh

command -v docker >/dev/null 2>&1 || { echo "[ERROR] docker is unavailable." >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo "[ERROR] curl is unavailable." >&2; exit 1; }
[[ -f "${VALIDATOR_SOURCE}" ]] || { echo "[ERROR] Missing ${VALIDATOR_SOURCE}." >&2; exit 1; }

production_id="$(docker inspect --format '{{.Id}}' "${PRODUCTION_CONTAINER}" 2>/dev/null)"
[[ -n "${production_id}" ]] || { echo "[ERROR] Production container is missing." >&2; exit 1; }
curl --fail --show-error --silent --max-time 5 http://127.0.0.1:18083/internal/health >/dev/null || {
    echo "[ERROR] Production is not healthy; refusing to prepare the test image." >&2
    exit 1
}
docker image inspect "${SOURCE_IMAGE}" >/dev/null 2>&1 || {
    echo "[ERROR] Source image ${SOURCE_IMAGE} is missing." >&2
    exit 1
}
if docker image inspect "${TARGET_IMAGE}" >/dev/null 2>&1; then
    echo "Test image ${TARGET_IMAGE} already exists; refusing to overwrite it."
    exit 0
fi
if docker inspect "${TEMP_CONTAINER}" >/dev/null 2>&1; then
    echo "[ERROR] Temporary container ${TEMP_CONTAINER} already exists." >&2
    exit 1
fi

cleanup() {
    docker rm -f "${TEMP_CONTAINER}" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

docker create --name "${TEMP_CONTAINER}" "${SOURCE_IMAGE}" >/dev/null
docker cp "${VALIDATOR_SOURCE}" "${TEMP_CONTAINER}:${VALIDATOR_TARGET}"
validator_sha="$(sha256sum "${VALIDATOR_SOURCE}" | awk '{print $1}')"
docker commit \
    --change "LABEL com.simplelabel.test-only=true" \
    --change "LABEL com.simplelabel.test-validator-sha256=${validator_sha}" \
    "${TEMP_CONTAINER}" "${TARGET_IMAGE}" >/dev/null

[[ "$(docker inspect --format '{{.Id}}' "${PRODUCTION_CONTAINER}")" == "${production_id}" ]] || {
    echo "[ERROR] Production container identity changed unexpectedly." >&2
    exit 1
}
curl --fail --show-error --silent --max-time 5 http://127.0.0.1:18083/internal/health >/dev/null || {
    echo "[ERROR] Production became unhealthy after test image preparation." >&2
    exit 1
}

echo "Prepared ${TARGET_IMAGE} from ${SOURCE_IMAGE} without starting or rebuilding production."
