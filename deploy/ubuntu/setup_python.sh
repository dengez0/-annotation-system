#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
RUNTIME_DIR="${SIMPLELABEL_RUNTIME_DIR:-/opt/simplelabel/runtime}"
VENV_PATH="${RUNTIME_DIR}/.venv"
REQUIREMENTS="${SCRIPT_DIR}/requirements-yolo-worker.txt"
WHEELHOUSE="${PROJECT_ROOT}/wheelhouse"
MANIFEST="${WHEELHOUSE}/SHA256SUMS"

if [[ "${1:-}" != "--apply" ]]; then
    echo "Dry run only. No files or packages were changed."
    echo "Would create the project-private environment: ${VENV_PATH}"
    echo "Would verify the offline wheelhouse: ${WHEELHOUSE}"
    echo "Would install only with --no-index from the verified wheelhouse."
    echo "No global package, driver, CUDA, firewall, or service changes are performed."
    exit 0
fi

command -v python3 >/dev/null 2>&1 || { echo "[ERROR] python3 is not installed." >&2; exit 1; }
[[ -f "${REQUIREMENTS}" ]] || { echo "[ERROR] Missing requirements file." >&2; exit 1; }
[[ -d "${WHEELHOUSE}" ]] || { echo "[ERROR] Missing offline wheelhouse." >&2; exit 1; }
[[ -f "${MANIFEST}" ]] || { echo "[ERROR] Missing wheelhouse SHA256SUMS." >&2; exit 1; }
(cd "${WHEELHOUSE}" && sha256sum --check --strict SHA256SUMS)

mkdir -p "${RUNTIME_DIR}"
[[ -e "${VENV_PATH}" ]] || python3 -m venv "${VENV_PATH}"

[[ -x "${VENV_PATH}/bin/python" ]] || { echo "[ERROR] Existing .venv is not an Ubuntu Python environment." >&2; exit 1; }
"${VENV_PATH}/bin/python" -m pip install --no-index --find-links "${WHEELHOUSE}" -r "${REQUIREMENTS}"
"${VENV_PATH}/bin/python" -c 'import torch; print("PyTorch:", torch.__version__, "CUDA available:", torch.cuda.is_available())'

echo "Worker environment created at ${VENV_PATH}."
echo "Run check_environment.sh to verify the completed Worker environment."
