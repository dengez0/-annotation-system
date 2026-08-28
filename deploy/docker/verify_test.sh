#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/.env.test"
COMPOSE_FILE="${SCRIPT_DIR}/compose.test.yml"
PROJECT_NAME=simplelabel-test

[[ -f "${ENV_FILE}" ]] || { echo "[ERROR] Missing ${ENV_FILE}." >&2; exit 1; }

env_value() {
    sed -n "s/^$1=//p" "${ENV_FILE}" | tail -n 1
}

bind_address="$(env_value SIMPLELABEL_TEST_BIND_ADDRESS)"
web_port="$(env_value SIMPLELABEL_TEST_WEB_PORT)"
model_port="$(env_value SIMPLELABEL_TEST_MODEL_PORT)"
compose=(docker compose -p "${PROJECT_NAME}" --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}")

[[ "${bind_address}" == "192.168.1.226" && "${web_port}" == "29090" && "${model_port}" == "29091" ]] || {
    echo "[ERROR] Unsafe test endpoint configuration." >&2
    exit 1
}

"${compose[@]}" ps
curl --fail --show-error --silent --max-time 5 http://127.0.0.1:18083/internal/health >/dev/null
curl --fail --show-error --silent --max-time 5 "http://${bind_address}:${web_port}/internal/health"
echo
curl --fail --show-error --silent --max-time 5 --output /dev/null "http://${bind_address}:${web_port}/"
curl --fail --show-error --silent --max-time 5 --output /dev/null "http://${bind_address}:${web_port}/admin/activate"
curl --fail --show-error --silent --max-time 10 --output /dev/null "http://${bind_address}:${model_port}/"

docker exec simplelabel-test sh -lc '
set -eu
test -w /srv/simplelabel/data
test -w /srv/simplelabel/data/.uploads
test -w /srv/simplelabel/data/未标注
mkdir /srv/simplelabel/data/.uploads/.permission-test
rmdir /srv/simplelabel/data/.uploads/.permission-test
mkdir /srv/simplelabel/data/未标注/.permission-test
rmdir /srv/simplelabel/data/未标注/.permission-test
'

curl --fail --show-error --silent --max-time 5 http://127.0.0.1:18083/internal/health >/dev/null
echo "SimpleLabel isolated test verification passed; production remained healthy."
