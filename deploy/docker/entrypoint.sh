#!/usr/bin/env bash
set -euo pipefail

APP_ROOT=/opt/simplelabel/current
WORKER_PID=
WEB_PID=

stop_children() {
    [[ -z "${WEB_PID}" ]] || kill "${WEB_PID}" 2>/dev/null || true
    [[ -z "${WORKER_PID}" ]] || kill "${WORKER_PID}" 2>/dev/null || true
    [[ -z "${WEB_PID}" ]] || wait "${WEB_PID}" 2>/dev/null || true
    [[ -z "${WORKER_PID}" ]] || wait "${WORKER_PID}" 2>/dev/null || true
}
trap stop_children EXIT INT TERM

bash "${APP_ROOT}/deploy/ubuntu/validate_runtime_env.sh"
mkdir -p "${SIMPLELABEL_CACHE_DIR}"

python3 "${APP_ROOT}/yolo-worker/worker.py" &
WORKER_PID=$!
bash "${APP_ROOT}/deploy/ubuntu/wait_for_worker.sh"

# SIMPLELABEL_JAVA_OPTS is controlled by the local deployment environment file.
# Intentional word splitting permits multiple JVM options such as -Xms and -Xmx.
# shellcheck disable=SC2086
java "-Duser.timezone=${SIMPLELABEL_TIME_ZONE:-Asia/Shanghai}" \
    ${SIMPLELABEL_JAVA_OPTS:--Xms256m -Xmx2g} \
    -jar "${APP_ROOT}/backend-java/target/simplelabel-java-1.0.0-SNAPSHOT.jar" &
WEB_PID=$!

set +e
wait -n "${WORKER_PID}" "${WEB_PID}"
status=$?
set -e
echo "A SimpleLabel child process exited with status ${status}; stopping the container." >&2
exit "${status}"
