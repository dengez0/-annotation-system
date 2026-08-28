#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
ENV_FILE="${SCRIPT_DIR}/.env.test"
COMPOSE_FILE="${SCRIPT_DIR}/compose.test.yml"
PROJECT_NAME=simplelabel-test
PRODUCTION_CONTAINER=simplelabel
TEST_CONTAINER=simplelabel-test
MIN_MEMORY_KB=$((8 * 1024 * 1024))
# Keep a small safety reserve while allowing the current 8.7 GiB-free host to
# start the isolated test stack.  This matches the documented 5 GiB stop line.
MIN_DISK_KB=$((5 * 1024 * 1024))

[[ -f "${ENV_FILE}" ]] || {
    echo "[ERROR] Missing ${ENV_FILE}. Run: bash deploy/docker/deploy.sh test" >&2
    exit 1
}

command -v docker >/dev/null 2>&1 || { echo "[ERROR] docker is unavailable." >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo "[ERROR] curl is unavailable." >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "[ERROR] python3 is unavailable." >&2; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "[ERROR] Docker Compose v2 is unavailable." >&2; exit 1; }

compose=(docker compose -p "${PROJECT_NAME}" --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}")

env_value() {
    sed -n "s/^$1=//p" "${ENV_FILE}" | tail -n 1
}

TEST_BIND_ADDRESS="$(env_value SIMPLELABEL_TEST_BIND_ADDRESS)"
TEST_WEB_PORT="$(env_value SIMPLELABEL_TEST_WEB_PORT)"
TEST_MODEL_PORT="$(env_value SIMPLELABEL_TEST_MODEL_PORT)"
TEST_IMAGE="$(env_value SIMPLELABEL_TEST_IMAGE)"

[[ "${TEST_BIND_ADDRESS}" == "192.168.1.226" ]] || {
    echo "[ERROR] Test bind address must be 192.168.1.226." >&2
    exit 1
}
[[ "${TEST_WEB_PORT}" == "29090" && "${TEST_MODEL_PORT}" == "29091" ]] || {
    echo "[ERROR] Test host ports must be 29090 and 29091." >&2
    exit 1
}
[[ "${TEST_IMAGE}" == "simplelabel:test-29090" ]] || {
    echo "[ERROR] SIMPLELABEL_TEST_IMAGE must be simplelabel:test-29090 for this isolated test stack." >&2
    exit 1
}

assert_rendered_config() {
    local config_file production_runtime test_runtime
    config_file="$(mktemp)"
    production_runtime="$(realpath -m "${PROJECT_ROOT}/runtime")"
    test_runtime="$(realpath -m "${PROJECT_ROOT}/runtime-test")"
    trap 'rm -f "${config_file}"' RETURN
    "${compose[@]}" config --format json > "${config_file}"
    python3 - "${config_file}" "${production_runtime}" "${test_runtime}" <<'PY'
import json
import os
import sys

config_path, production_runtime, test_runtime = sys.argv[1:]
with open(config_path, encoding="utf-8") as stream:
    config = json.load(stream)

services = config.get("services", {})
if set(services) != {"simplelabel-test"}:
    raise SystemExit("[ERROR] Test Compose must contain only simplelabel-test.")
service = services["simplelabel-test"]
if service.get("container_name") != "simplelabel-test":
    raise SystemExit("[ERROR] Test container name is unsafe.")
if service.get("restart") not in (None, "no"):
    raise SystemExit("[ERROR] Test container restart policy must be disabled.")

ports = {(str(item.get("host_ip")), int(item.get("published")), int(item.get("target")))
         for item in service.get("ports", [])}
expected_ports = {("192.168.1.226", 29090, 18083), ("192.168.1.226", 29091, 29091)}
if ports != expected_ports:
    raise SystemExit(f"[ERROR] Unsafe test port mapping: {sorted(ports)}")

production_runtime = os.path.realpath(production_runtime)
test_runtime = os.path.realpath(test_runtime)
expected_targets = {
    "/srv/simplelabel/data", "/srv/simplelabel/models", "/srv/simplelabel/logs",
    "/srv/simplelabel/admin", "/srv/simplelabel/processed", "/srv/simplelabel/backups",
}
actual_targets = set()
for mount in service.get("volumes", []):
    source = os.path.realpath(mount.get("source", ""))
    target = mount.get("target")
    actual_targets.add(target)
    if source == production_runtime or source.startswith(production_runtime + os.sep):
        raise SystemExit(f"[ERROR] Test stack references production runtime: {source}")
    if not (source == test_runtime or source.startswith(test_runtime + os.sep)):
        raise SystemExit(f"[ERROR] Test mount is outside runtime-test: {source}")
if actual_targets != expected_targets:
    raise SystemExit("[ERROR] Test runtime mounts are incomplete or unexpected.")
PY
    rm -f "${config_file}"
    trap - RETURN
}

production_id() {
    docker inspect --format '{{.Id}}' "${PRODUCTION_CONTAINER}" 2>/dev/null
}

assert_production_healthy() {
    [[ -n "$(production_id)" ]] || { echo "[ERROR] Production container is not running." >&2; return 1; }
    curl --fail --show-error --silent --max-time 5 \
        http://127.0.0.1:18083/internal/health >/dev/null
}

assert_resources() {
    local memory_kb disk_kb
    memory_kb="$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo)"
    disk_kb="$(df -Pk "${PROJECT_ROOT}" | awk 'NR == 2 {print $4}')"
    (( memory_kb >= MIN_MEMORY_KB )) || {
        echo "[ERROR] At least 8 GiB of available memory is required to start the test stack." >&2
        return 1
    }
    (( disk_kb >= MIN_DISK_KB )) || {
        echo "[ERROR] At least 5 GiB of free disk space is required to start the test stack." >&2
        return 1
    }
}

assert_ports_free() {
    if docker inspect "${TEST_CONTAINER}" >/dev/null 2>&1; then
        return 0
    fi
    command -v ss >/dev/null 2>&1 || { echo "[ERROR] ss is required for the test port preflight." >&2; return 1; }
    local port
    for port in "${TEST_WEB_PORT}" "${TEST_MODEL_PORT}"; do
        if ss -ltn "( sport = :${port} )" | tail -n +2 | grep -q .; then
            echo "[ERROR] Test port ${port} is already in use." >&2
            return 1
        fi
    done
}

wait_for_test_health() {
    local attempt
    for attempt in $(seq 1 45); do
        if curl --fail --show-error --silent --max-time 5 \
                "http://${TEST_BIND_ADDRESS}:${TEST_WEB_PORT}/internal/health" >/dev/null 2>&1; then
            return 0
        fi
        sleep 4
    done
    echo "[ERROR] Test health check did not pass within 180 seconds." >&2
    return 1
}

safe_test_down() {
    "${compose[@]}" down
}

start_test() {
    local before_id after_id
    assert_rendered_config
    assert_production_healthy
    assert_resources
    assert_ports_free
    docker image inspect "${TEST_IMAGE}" >/dev/null 2>&1 || {
        echo "[ERROR] Test image ${TEST_IMAGE} is missing; refusing to build or pull while production is active." >&2
        return 1
    }
    before_id="$(production_id)"
    if ! "${compose[@]}" up -d --no-build --no-deps; then
        safe_test_down || true
        return 1
    fi
    after_id="$(production_id)"
    if [[ "${before_id}" != "${after_id}" ]] || ! assert_production_healthy; then
        echo "[ERROR] Production changed or became unhealthy; stopping only the test stack." >&2
        safe_test_down || true
        return 1
    fi
    if ! wait_for_test_health; then
        "${compose[@]}" logs --tail=200 || true
        safe_test_down || true
        assert_production_healthy || true
        return 1
    fi
    bash "${SCRIPT_DIR}/verify_test.sh"
}

stop_test() {
    local before_id after_id
    before_id="$(production_id)"
    safe_test_down
    after_id="$(production_id)"
    [[ -n "${before_id}" && "${before_id}" == "${after_id}" ]] || {
        echo "[ERROR] Production container identity changed unexpectedly." >&2
        return 1
    }
    assert_production_healthy
    echo "Test stack stopped; production remained healthy and unchanged."
}

case "${1:-}" in
    up) start_test ;;
    down) stop_test ;;
    ps) assert_rendered_config; "${compose[@]}" ps ;;
    logs) assert_rendered_config; "${compose[@]}" logs --tail="${2:-200}" ;;
    verify) assert_rendered_config; bash "${SCRIPT_DIR}/verify_test.sh" ;;
    *)
        echo "Usage: $0 {up|down|ps|logs [lines]|verify}" >&2
        exit 2
        ;;
esac
