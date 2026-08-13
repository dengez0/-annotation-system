#!/usr/bin/env bash
set -euo pipefail

required=(
    SIMPLELABEL_DEPLOYMENT_MODE SIMPLELABEL_ROOT SIMPLELABEL_BIND_ADDRESS SIMPLELABEL_PORT
    SIMPLELABEL_DATA_DIR SIMPLELABEL_MODELS_DIR SIMPLELABEL_LOGS_DIR
    SIMPLELABEL_PROCESSED_DIR SIMPLELABEL_STATIC_DIR SIMPLELABEL_CACHE_DIR
    SIMPLELABEL_YOLO_WORKER_PORT SIMPLELABEL_YOLO_WORKER_URL
    SIMPLELABEL_YOLO_WORKER_TOKEN SIMPLELABEL_YOLO_DEVICE SIMPLELABEL_ADMIN_IP
)

for name in "${required[@]}"; do
    [[ -n "${!name:-}" ]] || { echo "[ERROR] Missing ${name}." >&2; exit 1; }
done

[[ "${SIMPLELABEL_ROOT}" == /opt/simplelabel/* ]] || {
    echo "[ERROR] SIMPLELABEL_ROOT must stay inside /opt/simplelabel." >&2
    exit 1
}
[[ "${SIMPLELABEL_STATIC_DIR}" == /opt/simplelabel/* ]] || {
    echo "[ERROR] SIMPLELABEL_STATIC_DIR must stay inside /opt/simplelabel." >&2
    exit 1
}
for name in SIMPLELABEL_DATA_DIR SIMPLELABEL_MODELS_DIR SIMPLELABEL_LOGS_DIR SIMPLELABEL_PROCESSED_DIR; do
    [[ "${!name}" == /srv/simplelabel/* || "${!name}" == /srv/simplelabel-preview/* ]] || {
        echo "[ERROR] ${name} must stay inside a SimpleLabel directory under /srv." >&2
        exit 1
    }
done
[[ "${SIMPLELABEL_CACHE_DIR}" == /tmp/simplelabel* ]] || {
    echo "[ERROR] SIMPLELABEL_CACHE_DIR must stay inside a SimpleLabel path under /tmp." >&2
    exit 1
}

for name in SIMPLELABEL_ROOT SIMPLELABEL_DATA_DIR SIMPLELABEL_MODELS_DIR \
            SIMPLELABEL_LOGS_DIR SIMPLELABEL_PROCESSED_DIR SIMPLELABEL_STATIC_DIR \
            SIMPLELABEL_CACHE_DIR; do
    [[ "${!name}" == /* ]] || { echo "[ERROR] ${name} must be an absolute path." >&2; exit 1; }
done

[[ "${SIMPLELABEL_PORT}" =~ ^[0-9]+$ ]] || { echo "[ERROR] Invalid web port." >&2; exit 1; }
[[ "${SIMPLELABEL_YOLO_WORKER_PORT}" =~ ^[0-9]+$ ]] || { echo "[ERROR] Invalid Worker port." >&2; exit 1; }
(( SIMPLELABEL_PORT >= 1 && SIMPLELABEL_PORT <= 65535 )) || { echo "[ERROR] Web port is out of range." >&2; exit 1; }
(( SIMPLELABEL_YOLO_WORKER_PORT >= 1 && SIMPLELABEL_YOLO_WORKER_PORT <= 65535 )) || { echo "[ERROR] Worker port is out of range." >&2; exit 1; }
case "${SIMPLELABEL_DEPLOYMENT_MODE}" in
    preview)
        [[ "${SIMPLELABEL_BIND_ADDRESS}" == "127.0.0.1" ]] || { echo "[ERROR] Preview Web must bind to 127.0.0.1." >&2; exit 1; }
        [[ "${SIMPLELABEL_PORT}" == "28083" && "${SIMPLELABEL_YOLO_WORKER_PORT}" == "28085" ]] || {
            echo "[ERROR] Preview ports must be 28083 and 28085." >&2
            exit 1
        }
        [[ "${SIMPLELABEL_DATA_DIR}" == /srv/simplelabel-preview/* ]] || { echo "[ERROR] Preview must use isolated preview data." >&2; exit 1; }
        ;;
    production)
        [[ "${SIMPLELABEL_BIND_ADDRESS}" == "0.0.0.0" ]] || { echo "[ERROR] Production Web bind address must be 0.0.0.0." >&2; exit 1; }
        [[ "${SIMPLELABEL_PORT}" == "18083" && "${SIMPLELABEL_YOLO_WORKER_PORT}" == "18085" ]] || {
            echo "[ERROR] Production ports must be 18083 and 18085." >&2
            exit 1
        }
        [[ "${SIMPLELABEL_DATA_DIR}" == /srv/simplelabel/* ]] || { echo "[ERROR] Production must use the production data directory." >&2; exit 1; }
        ;;
    *)
        echo "[ERROR] Deployment mode must be preview or production." >&2
        exit 1
        ;;
esac
[[ "${SIMPLELABEL_YOLO_WORKER_URL}" =~ ^http://(127\.0\.0\.1|localhost):[0-9]+$ ]] || {
    echo "[ERROR] Worker URL must remain on the remote host loopback interface." >&2
    exit 1
}
[[ "${SIMPLELABEL_YOLO_WORKER_URL##*:}" == "${SIMPLELABEL_YOLO_WORKER_PORT}" ]] || {
    echo "[ERROR] Worker URL and Worker port do not match." >&2
    exit 1
}
[[ "${#SIMPLELABEL_YOLO_WORKER_TOKEN}" -ge 32 ]] || { echo "[ERROR] Worker token is too short." >&2; exit 1; }
[[ "${SIMPLELABEL_YOLO_WORKER_TOKEN}" != REPLACE_WITH_* ]] || { echo "[ERROR] Replace the example Worker token." >&2; exit 1; }
[[ "${SIMPLELABEL_YOLO_DEVICE}" == "cpu" || "${SIMPLELABEL_YOLO_DEVICE}" =~ ^[0-9]+$ ]] || {
    echo "[ERROR] YOLO device must be cpu or an approved numeric device index." >&2
    exit 1
}
if [[ "${SIMPLELABEL_YOLO_DEVICE}" =~ ^[0-9]+$ && -z "${CUDA_VISIBLE_DEVICES:-}" ]]; then
    echo "[ERROR] Numeric GPU use requires an explicitly approved CUDA_VISIBLE_DEVICES value." >&2
    exit 1
fi

if [[ "${SIMPLELABEL_ADMIN_IP}" == REPLACE_WITH_* ]]; then
    echo "[ERROR] Replace the example administrator IP." >&2
    exit 1
fi

echo "SimpleLabel runtime environment is valid."
