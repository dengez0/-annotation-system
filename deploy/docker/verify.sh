#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/.env"
COMPOSE_FILE="${SCRIPT_DIR}/compose.yml"

docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" ps
docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" logs --tail=100
curl --fail --show-error --silent http://127.0.0.1:18083/internal/health
echo
curl --fail --show-error --silent --output /dev/null http://127.0.0.1:18083/
curl --fail --show-error --silent --output /dev/null http://127.0.0.1:18083/admin/activate
echo "SimpleLabel local verification passed."
