#!/usr/bin/env bash
set -u

FAILED=0
MIN_MEMORY_KIB=$((12 * 1024 * 1024))
MIN_DISK_KIB=$((15 * 1024 * 1024))

echo "SimpleLabel read-only preflight. No services or settings are changed."

if ! command -v ss >/dev/null 2>&1; then
    echo "[BLOCKED] the read-only port inspection command 'ss' is unavailable"
    exit 1
fi

for port in 28083 28085; do
    if ss -ltn "( sport = :${port} )" 2>/dev/null | tail -n +2 | grep -q .; then
        echo "[BLOCKED] preview port ${port} is already in use"
        FAILED=1
    else
        echo "[OK] preview port ${port} is free"
    fi
done

available_memory="$(awk '/MemAvailable:/ {print $2}' /proc/meminfo)"
available_disk="$(df -Pk /opt 2>/dev/null | awk 'NR==2 {print $4}')"
if [[ -z "${available_disk}" ]]; then
    available_disk="$(df -Pk / | awk 'NR==2 {print $4}')"
fi

if (( available_memory < MIN_MEMORY_KIB )); then
    echo "[BLOCKED] less than 12 GiB memory is currently available"
    FAILED=1
else
    echo "[OK] at least 12 GiB memory is available"
fi
if (( available_disk < MIN_DISK_KIB )); then
    echo "[BLOCKED] less than 15 GiB disk space is available"
    FAILED=1
else
    echo "[OK] at least 15 GiB disk space is available"
fi

echo "Existing failed systemd units (informational; do not modify them):"
systemctl --failed --no-pager || true
exit "${FAILED}"
