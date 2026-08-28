#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
PROFILE="${1:-production}"

case "${PROFILE}" in
    production)
        ENV_FILE="${SCRIPT_DIR}/.env"
        ENV_TEMPLATE="${SCRIPT_DIR}/.env.example"
        COMPOSE_FILE="${SCRIPT_DIR}/compose.yml"
        RUNTIME_DIR="${PROJECT_ROOT}/runtime"
        HOST_PORTS=(18083)
        ;;
    test)
        ENV_FILE="${SCRIPT_DIR}/.env.test"
        ENV_TEMPLATE="${SCRIPT_DIR}/.env.test.example"
        COMPOSE_FILE="${SCRIPT_DIR}/compose.test.yml"
        RUNTIME_DIR="${PROJECT_ROOT}/runtime-test"
        HOST_PORTS=(29090 29091)
        ;;
    *)
        echo "Usage: $0 [production|test]" >&2
        exit 2
        ;;
esac

command -v docker >/dev/null 2>&1 || { echo "[ERROR] docker is unavailable." >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "[ERROR] host python3 is unavailable." >&2; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "[ERROR] Docker Compose v2 is unavailable." >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "[ERROR] The current user cannot access Docker." >&2; exit 1; }
if [[ "${PROFILE}" == "production" ]]; then
    docker image inspect nvidia/cuda:11.8.0-cudnn8-devel-ubuntu22.04 >/dev/null 2>&1 || {
        echo "[ERROR] Required cached base image is missing." >&2
        exit 1
    }
fi

if [[ -f "${PROJECT_ROOT}/SHA256SUMS" ]]; then
    (cd "${PROJECT_ROOT}" && sha256sum --check --strict SHA256SUMS)
fi

for port in "${HOST_PORTS[@]}"; do
    if command -v ss >/dev/null 2>&1 && ss -ltn "( sport = :${port} )" | tail -n +2 | grep -q .; then
        echo "[ERROR] Port ${port} is already in use." >&2
        exit 1
    fi
done

mkdir -p \
    "${RUNTIME_DIR}/data" \
    "${RUNTIME_DIR}/models" \
    "${RUNTIME_DIR}/logs" \
    "${RUNTIME_DIR}/admin" \
    "${RUNTIME_DIR}/processed" \
    "${RUNTIME_DIR}/backups"
chmod 700 "${RUNTIME_DIR}/admin"

if [[ ! -f "${ENV_FILE}" ]]; then
    token="$(python3 -c 'import secrets; print(secrets.token_hex(32))')"
    sed \
        -e "s/^SIMPLELABEL_UID=.*/SIMPLELABEL_UID=$(id -u)/" \
        -e "s/^SIMPLELABEL_GID=.*/SIMPLELABEL_GID=$(id -g)/" \
        -e "s/^SIMPLELABEL_YOLO_WORKER_TOKEN=.*/SIMPLELABEL_YOLO_WORKER_TOKEN=${token}/" \
        "${ENV_TEMPLATE}" > "${ENV_FILE}"
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
    if ! grep -q '^SIMPLELABEL_BACKUPS_DIR=' "${ENV_FILE}"; then
        printf '\nSIMPLELABEL_BACKUPS_DIR=/srv/simplelabel/backups\n' >> "${ENV_FILE}"
        echo "Added SIMPLELABEL_BACKUPS_DIR=/srv/simplelabel/backups to the existing environment file."
    fi
    if ! grep -q '^SIMPLELABEL_MODEL_DETECTION_PORT=' "${ENV_FILE}"; then
        if [[ "${PROFILE}" == "test" ]]; then
            printf '\nSIMPLELABEL_MODEL_DETECTION_PORT=29091\n' >> "${ENV_FILE}"
        else
            printf '\nSIMPLELABEL_MODEL_DETECTION_PORT=8000\n' >> "${ENV_FILE}"
        fi
        echo "Added SIMPLELABEL_MODEL_DETECTION_PORT to the existing environment file."
    fi
fi

if [[ "${PROFILE}" == "test" ]]; then
    mkdir -p "${RUNTIME_DIR}/data/.uploads" "${RUNTIME_DIR}/data/未标注"
    set_env_value() {
        local name="$1" value="$2"
        if grep -q "^${name}=" "${ENV_FILE}"; then
            sed -i "s|^${name}=.*|${name}=${value}|" "${ENV_FILE}"
        else
            printf '\n%s=%s\n' "${name}" "${value}" >> "${ENV_FILE}"
        fi
    }
    set_env_value SIMPLELABEL_TEST_IMAGE simplelabel:test-29090
    set_env_value SIMPLELABEL_TEST_BIND_ADDRESS 192.168.1.226
    set_env_value SIMPLELABEL_TEST_WEB_PORT 29090
    set_env_value SIMPLELABEL_TEST_MODEL_PORT 29091
    set_env_value SIMPLELABEL_MODEL_DETECTION_PORT 29091
    set_env_value SIMPLELABEL_YOLO_DEVICE cpu
    set_env_value SIMPLELABEL_JAVA_OPTS '-Xms128m -Xmx1g'

    target_uid="$(sed -n 's/^SIMPLELABEL_UID=//p' "${ENV_FILE}" | tail -n 1)"
    target_gid="$(sed -n 's/^SIMPLELABEL_GID=//p' "${ENV_FILE}" | tail -n 1)"
    [[ "${target_uid}" =~ ^[0-9]+$ && "${target_gid}" =~ ^[0-9]+$ ]] || {
        echo "[ERROR] Test UID and GID must be numeric." >&2
        exit 1
    }
    ownership_mismatch="$(find "${RUNTIME_DIR}" -xdev \( ! -uid "${target_uid}" -o ! -gid "${target_gid}" \) -print -quit)"
    if [[ -n "${ownership_mismatch}" ]]; then
        if (( EUID == 0 )); then
            chown -R "${target_uid}:${target_gid}" "${RUNTIME_DIR}"
        else
            echo "[ERROR] ${ownership_mismatch} does not belong to ${target_uid}:${target_gid}." >&2
            echo "Run: sudo chown -R ${target_uid}:${target_gid} ${RUNTIME_DIR}" >&2
            exit 1
        fi
    fi
    chmod u+rwx "${RUNTIME_DIR}/data" "${RUNTIME_DIR}/data/.uploads" "${RUNTIME_DIR}/data/未标注"

    test_image="$(sed -n 's/^SIMPLELABEL_TEST_IMAGE=//p' "${ENV_FILE}" | tail -n 1)"
    docker image inspect "${test_image}" >/dev/null 2>&1 || {
        echo "[ERROR] Test image ${test_image} is missing. Test deployment will not build or pull images on this host." >&2
        exit 1
    }

    docker compose -p simplelabel-test --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" config --quiet
    echo "Test configuration is ready. No image was built or pulled."
    echo "Start safely with:"
    echo "bash deploy/docker/test_stack.sh up"
else
    docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" config --quiet
    echo "Building SimpleLabel from the cached Ubuntu 22.04 CUDA base image..."
    docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" build --pull=false
    echo "Build completed. Start with:"
    echo "docker compose --env-file ${ENV_FILE} -f ${COMPOSE_FILE} up -d"
fi
