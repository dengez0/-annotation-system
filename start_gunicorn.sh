#!/bin/bash
# SimpleLabel - Production Server (Gunicorn)
# Usage: bash start_gunicorn.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "============================================"
echo "  SimpleLabel - Production Server (Gunicorn)"
echo "============================================"
echo ""

# Check gunicorn
if ! python -c "import gunicorn" 2>/dev/null; then
    echo "[ERROR] Gunicorn is not installed."
    echo "Run: pip install gunicorn"
    exit 1
fi

# Create logs directory
mkdir -p logs

# Workers: auto-detect CPU count, cap at 8
CPU_COUNT=$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)
WORKERS=$(( CPU_COUNT < 8 ? CPU_COUNT : 8 ))

echo "Starting Gunicorn with ${WORKERS} workers on port 18083..."
echo "Access:  http://localhost:18083"
echo "Logs:    logs/access.log  /  logs/error.log"
echo ""
echo "Press Ctrl+C to stop the server."
echo "============================================"
echo ""

gunicorn -c gunicorn.conf.py --workers "$WORKERS" app:app
