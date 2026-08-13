#!/usr/bin/env bash
set -euo pipefail

WORKER_URL="${SIMPLELABEL_YOLO_WORKER_URL:-http://127.0.0.1:18085}"
for _ in $(seq 1 60); do
    if command -v curl >/dev/null 2>&1 && curl --silent --fail --max-time 2 "${WORKER_URL}/internal/health" >/dev/null; then
        exit 0
    fi
    sleep 1
done

echo "YOLO Worker health check timed out: ${WORKER_URL}" >&2
exit 1
