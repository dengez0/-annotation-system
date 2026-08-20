#!/usr/bin/env python3
"""Generate independent administrator device tokens and their server-side hashes."""

import hashlib
import re
import secrets
import sys


LABEL = re.compile(r"[A-Za-z0-9._-]{1,64}\Z")


def main() -> int:
    labels = sys.argv[1:]
    if not labels:
        print(f"Usage: {sys.argv[0]} DEVICE_NAME [DEVICE_NAME ...]", file=sys.stderr)
        return 2
    if len(set(labels)) != len(labels) or any(not LABEL.fullmatch(label) for label in labels):
        print("Device names must be unique and contain only letters, digits, dot, underscore, or hyphen.",
              file=sys.stderr)
        return 2

    records = []
    for label in labels:
        token = "slt_" + secrets.token_urlsafe(32)
        digest = hashlib.sha256(token.encode("utf-8")).hexdigest()
        records.append((label, token, digest))

    print("Add this line to deploy/docker/.env:")
    print("SIMPLELABEL_ADMIN_TOKEN_HASHES=" + ",".join(
        f"{label}={digest}" for label, _, digest in records))
    print("\nGive each token only to its named administrator device (shown once):")
    for label, token, _ in records:
        print(f"{label}: {token}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
