#!/usr/bin/env bash
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
VENV_PATH="${SIMPLELABEL_RUNTIME_DIR:-/opt/simplelabel/runtime}/.venv"
FAILED=0

check_command() {
    local name="$1"
    if command -v "${name}" >/dev/null 2>&1; then
        echo "[OK] ${name}: $(command -v "${name}")"
    else
        echo "[MISSING] ${name}"
        FAILED=1
    fi
}

echo "SimpleLabel Ubuntu environment check (read-only)"
check_command java
check_command python3
check_command curl
check_command sha256sum
check_command ss

for path in \
    backend-java/target/simplelabel-java-1.0.0-SNAPSHOT.jar \
    yolo-worker/worker.py \
    services/yolo_backend.py \
    services/yolo_legacy_loader.py \
    services/yolo_result_parser.py \
    static; do
    if [[ -e "${PROJECT_ROOT}/${path}" ]]; then
        echo "[OK] project item: ${path}"
    else
        echo "[MISSING] project item: ${path}"
        FAILED=1
    fi
done

if [[ -x "${VENV_PATH}/bin/python" ]]; then
    echo "[OK] Ubuntu virtual environment exists."
    "${VENV_PATH}/bin/python" -c 'import flask, PIL, cv2, ultralytics; print("[OK] Worker Python imports")' || FAILED=1
    "${VENV_PATH}/bin/python" -c 'import torch; print("PyTorch:", torch.__version__, "CUDA available:", torch.cuda.is_available())' || FAILED=1
else
    echo "[MISSING] ${VENV_PATH}/bin/python"
    FAILED=1
fi

if command -v ss >/dev/null 2>&1; then
    echo "Listening state for application ports:"
    ss -ltn '( sport = :18083 or sport = :18085 or sport = :28083 or sport = :28085 )' 2>/dev/null || true
fi

exit "${FAILED}"
