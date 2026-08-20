#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
ENV_FILE="${SCRIPT_DIR}/.env"
COMPOSE_FILE="${SCRIPT_DIR}/compose.yml"

command -v docker >/dev/null 2>&1 || { echo "[ERROR] docker is unavailable." >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "[ERROR] host python3 is unavailable." >&2; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "[ERROR] Docker Compose v2 is unavailable." >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "[ERROR] The current user cannot access Docker." >&2; exit 1; }
docker image inspect nvidia/cuda:11.8.0-cudnn8-devel-ubuntu22.04 >/dev/null 2>&1 || {
    echo "[ERROR] Required cached base image is missing." >&2
    exit 1
}

if [[ -f "${PROJECT_ROOT}/SHA256SUMS" ]]; then
    (cd "${PROJECT_ROOT}" && sha256sum --check --strict SHA256SUMS)
fi

for port in 18083; do
    if command -v ss >/dev/null 2>&1 && ss -ltn "( sport = :${port} )" | tail -n +2 | grep -q .; then
        echo "[ERROR] Port ${port} is already in use." >&2
        exit 1
    fi
done

mkdir -p \
    "${PROJECT_ROOT}/runtime/data" \
    "${PROJECT_ROOT}/runtime/models" \
    "${PROJECT_ROOT}/runtime/logs" \
    "${PROJECT_ROOT}/runtime/admin" \
    "${PROJECT_ROOT}/runtime/processed"
chmod 700 "${PROJECT_ROOT}/runtime/admin"

if [[ ! -f "${ENV_FILE}" ]]; then
    token="$(python3 -c 'import secrets; print(secrets.token_hex(32))')"
    sed \
        -e "s/^SIMPLELABEL_UID=.*/SIMPLELABEL_UID=$(id -u)/" \
        -e "s/^SIMPLELABEL_GID=.*/SIMPLELABEL_GID=$(id -g)/" \
        -e "s/^SIMPLELABEL_YOLO_WORKER_TOKEN=.*/SIMPLELABEL_YOLO_WORKER_TOKEN=${token}/" \
        "${SCRIPT_DIR}/.env.example" > "${ENV_FILE}"
    chmod 600 "${ENV_FILE}"
    echo "Created ${ENV_FILE}. Configure administrator token hashes before starting the service."
else
    echo "Keeping existing ${ENV_FILE}."
    if ! grep -q '^SIMPLELABEL_TIME_ZONE=' "${ENV_FILE}"; then
        printf '\nSIMPLELABEL_TIME_ZONE=Asia/Shanghai\n' >> "${ENV_FILE}"
        echo "Added SIMPLELABEL_TIME_ZONE=Asia/Shanghai to the existing environment file."
    fi
    if ! grep -q '^SIMPLELABEL_ADMIN_DIR=' "${ENV_FILE}"; then
        printf '\nSIMPLELABEL_ADMIN_DIR=/srv/simplelabel/admin\n' >> "${ENV_FILE}"
        echo "Added SIMPLELABEL_ADMIN_DIR=/srv/simplelabel/admin to the existing environment file."
    fi
fi

docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" config --quiet
echo "Building SimpleLabel from the cached Ubuntu 22.04 CUDA base image..."
docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" build --pull=false
echo "Build completed. Start with:"
echo "docker compose --env-file ${ENV_FILE} -f ${COMPOSE_FILE} up -d"
