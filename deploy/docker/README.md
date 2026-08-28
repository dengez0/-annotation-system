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

The same-host test environment is isolated from production by host ports,
administrator registry, and runtime data. Its application is available at
`http://192.168.1.226:29090`, its model-detection page uses `29091`, and all
test data is stored under `runtime-test/`. The actual production endpoints are
`18083` (web) and `18086` (model detection); production data remains under
`runtime/`.

On the current host, keep the test checkout at
`/home/kinth/simplelabel-test`, alongside but never inside
`/home/kinth/simplelabel`. Its persistent test data is therefore stored at
`/home/kinth/simplelabel-test/runtime-test`; do not place the checkout or
runtime under `/tmp`, because system cleanup may remove them.

The repository scripts define a single-container reference stack named
`simplelabel-test`, using `simplelabel:test-29090`. The currently running test
environment is different: `simplelabel-web-test` serves 29090 and
`simplelabel-model-test` serves 29091. Before running any command below, inspect
the live containers. Do not use these single-container scripts against the live
two-container test environment unless a planned migration explicitly replaces
both containers; in particular, do not interrupt `simplelabel-model-test` while
model detection is in progress.

The reference test container is limited to one CPU, 6 GB of memory, CPU-only
inference, and no automatic restart. Its launcher refuses to start unless
production is healthy, sufficient memory and disk are available, both test
ports are free, and the rendered Compose configuration contains only
`runtime-test/` mounts.

```bash
# Prepare only: creates deploy/docker/.env.test and runtime-test/, then validates
# permissions and configuration. It does not start, build, or pull anything.
bash deploy/docker/prepare_test_image.sh
bash deploy/docker/deploy.sh test
python3 deploy/docker/configure_admin_token.py runtime-test/admin/admin_tokens.json test-admin-pc

# The commands below always use the isolated simplelabel-test project.
bash deploy/docker/test_stack.sh up
bash deploy/docker/test_stack.sh ps
bash deploy/docker/test_stack.sh logs 200
bash deploy/docker/test_stack.sh verify
bash deploy/docker/test_stack.sh down
```

Do not copy production `runtime/` into `runtime-test/`; upload only the data
needed for testing. Do not use `--remove-orphans`, `-v`, or global Docker prune
commands for test operations. While users are annotating, limit verification to
health, pages, permissions, and small uploads. Schedule large uploads, model
inference, and load tests for a low-traffic window.

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
