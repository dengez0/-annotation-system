#!/usr/bin/env python3
"""Add an administrator token to the persistent registry without echoing it."""

import argparse
import getpass
import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path
import re
import tempfile


DEVICE_NAME = re.compile(r"[A-Za-z0-9._-]{1,64}\Z")


def main() -> int:
    parser = argparse.ArgumentParser(description="Offline SimpleLabel administrator recovery tool")
    parser.add_argument("registry", type=Path, help="Path to admin_tokens.json")
    parser.add_argument("device_name")
    args = parser.parse_args()
    if not DEVICE_NAME.fullmatch(args.device_name):
        parser.error("device name must use 1-64 letters, digits, dots, underscores, or hyphens")

    token = getpass.getpass("Administrator token: ").strip()
    confirmation = getpass.getpass("Confirm token: ").strip()
    if token != confirmation:
        raise SystemExit("Tokens do not match.")
    if not token.startswith("slt_") or len(token) < 32:
        raise SystemExit("Token must be a strong SimpleLabel token beginning with slt_.")

    path = args.registry.resolve()
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        os.chmod(path.parent, 0o700)
    except OSError:
        pass
    if path.exists():
        registry = json.loads(path.read_text(encoding="utf-8"))
    else:
        registry = {"version": 1, "devices": []}
    if registry.get("version") != 1 or not isinstance(registry.get("devices"), list):
        raise SystemExit("Unsupported or invalid administrator registry.")
    if any(item.get("name") == args.device_name for item in registry["devices"]):
        raise SystemExit(f"Device already exists: {args.device_name}")

    registry["devices"].append({
        "name": args.device_name,
        "sha256": hashlib.sha256(token.encode("utf-8")).hexdigest(),
        "created_at": datetime.now(timezone.utc).astimezone().isoformat(),
    })
    descriptor, temporary_name = tempfile.mkstemp(prefix=".admin_tokens-", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            json.dump(registry, output, ensure_ascii=False, indent=2)
            output.write("\n")
        os.chmod(temporary_name, 0o600)
        os.replace(temporary_name, path)
        print(f"Added administrator device {args.device_name}; no plaintext token was stored.")
    finally:
        if os.path.exists(temporary_name):
            os.unlink(temporary_name)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
