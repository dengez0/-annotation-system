# SimpleLabel Docker deployment

This deployment targets the inspected Ubuntu host without changing its system
Java or Python. It reuses the cached Ubuntu CUDA image and runs inference on CPU
until GPU use is separately approved.

## Upload, bootstrap, and start

The release archive contains code and runtime definitions only. It contains no
annotation data, models, logs, plaintext administrator tokens, local toolchains,
or Git metadata.

After extracting the archive on the server:

```bash
cd ~/simplelabel
sha256sum --check --strict SHA256SUMS
bash deploy/docker/deploy.sh
python3 deploy/docker/configure_admin_token.py runtime/admin/admin_tokens.json admin-pc-1
python3 deploy/docker/configure_admin_token.py runtime/admin/admin_tokens.json admin-pc-2
docker compose --env-file deploy/docker/.env -f deploy/docker/compose.yml up -d
bash deploy/docker/verify.sh
```

The two offline commands prompt without echoing. Enter the separately issued
token for each named computer. Only SHA-256 hashes are stored in
`runtime/admin/admin_tokens.json`; plaintext tokens must never be written to the
server, command line, shell history, repository, or release archive.

The deployment fixes application and worker time to `Asia/Shanghai`. After
restart, `/internal/health` reports `time_zone` and an offset-bearing
`current_time`; verify `Asia/Shanghai` and `+08:00`.

Users on the same LAN open `http://192.168.1.226:18083`. If the connection is
refused while local verification passes, an administrator must review the
host/network firewall. Do not change the firewall without authorization.

## Isolated remote test environment

The test stack does not reuse production ports or runtime data: its application
is available at `http://SERVER_IP:18084`, its original PT_ONNX page uses port
`8001`, and all files are stored under `runtime-test/`. Production remains on
`18083`, `8000`, and `runtime/`.

```bash
# First test deployment: creates deploy/docker/.env.test and runtime-test/
bash deploy/docker/deploy.sh test
python3 deploy/docker/configure_admin_token.py runtime-test/admin/admin_tokens.json test-admin-pc
docker compose --env-file deploy/docker/.env.test -f deploy/docker/compose.test.yml up -d

# Inspect or stop only the test stack
docker compose --env-file deploy/docker/.env.test -f deploy/docker/compose.test.yml ps
docker compose --env-file deploy/docker/.env.test -f deploy/docker/compose.test.yml logs --tail=200
docker compose --env-file deploy/docker/.env.test -f deploy/docker/compose.test.yml down
```

Do not copy production `runtime/` into `runtime-test/`; upload only the data
needed for testing. The test compose file uses a separate container named
`simplelabel-test`, so its lifecycle does not affect the production container.

## Token-only administrator access

Client IP addresses, loopback, and server-local addresses never grant
administrator access. Work logs, project and YOLO exports, source dataset
download/deletion, processed-result download/deletion, and token management
require an activated administrator device.

On each administrator computer, open `/admin/activate` and enter its own token.
The browser stores it in an HttpOnly, SameSite=Strict cookie. An active
administrator manages devices at `/admin/tokens`:

- The server generates each new token and displays the plaintext exactly once.
- Revocation takes effect on the target device's next request.
- The current device and the final remaining device cannot be deleted.
- Registry changes are persisted in `runtime/admin/admin_tokens.json`.

For a new first-time deployment, tokens can instead be generated with:

```bash
python3 deploy/docker/generate_admin_device_tokens.py admin-pc-1 admin-pc-2
```

Copy only its `SIMPLELABEL_ADMIN_TOKEN_HASHES=...` output into the server-only
`deploy/docker/.env`. The application imports this variable only when the
persistent registry does not exist. After successful import and activation,
clear the variable so a revoked bootstrap token cannot reappear if the registry
is accidentally replaced.

For offline recovery, stop the application container and run
`configure_admin_token.py` against `runtime/admin/admin_tokens.json`. Never pass
a plaintext administrator token as a command-line argument.

The current direct HTTP deployment must use
`SIMPLELABEL_ADMIN_COOKIE_SECURE=false`, but HTTP cannot prevent LAN packet
capture from stealing a bearer token. Put the application behind HTTPS and set
`SIMPLELABEL_ADMIN_COOKIE_SECURE=true` before treating tokens as a strong network
security boundary.

Place model files in `runtime/models/`. Annotation data is stored in
`runtime/data/`; logs, administrator hashes, processed output, and optional
pre-mask backups under `runtime/backups/` also remain under `runtime/`.

## Operations

```bash
# Status and recent logs
docker compose --env-file deploy/docker/.env -f deploy/docker/compose.yml ps
docker compose --env-file deploy/docker/.env -f deploy/docker/compose.yml logs --tail=200

# Restart
docker compose --env-file deploy/docker/.env -f deploy/docker/compose.yml restart

# Stop without deleting data
docker compose --env-file deploy/docker/.env -f deploy/docker/compose.yml down
```

Never add `-v` to `docker compose down`; runtime data uses host bind mounts, but
unrelated Docker volumes on this shared host must remain untouched.
