#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
WHEELHOUSE="${PROJECT_ROOT}/wheelhouse"

command -v python3 >/dev/null 2>&1 || { echo "[ERROR] Python 3 is required." >&2; exit 1; }
[[ "$(python3 -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")')" == "3.12" ]] || {
    echo "[ERROR] Build the Ubuntu wheelhouse with Python 3.12." >&2
    exit 1
}

[[ "${WHEELHOUSE}" == "${PROJECT_ROOT}/wheelhouse" && "${WHEELHOUSE}" != "/" ]] || {
    echo "[ERROR] Refusing unsafe wheelhouse path: ${WHEELHOUSE}" >&2
    exit 1
}
rm -rf "${WHEELHOUSE}"
mkdir -p "${WHEELHOUSE}"
python3 -m pip download --only-binary=:all: --dest "${WHEELHOUSE}" \
    --extra-index-url https://download.pytorch.org/whl/cpu \
    -r "${SCRIPT_DIR}/requirements-yolo-worker.txt"
(cd "${WHEELHOUSE}" && find . -maxdepth 1 -type f ! -name SHA256SUMS -printf '%P\0' | sort -z | xargs -0 sha256sum > SHA256SUMS)
echo "Built verified CPU wheelhouse at ${WHEELHOUSE}"
