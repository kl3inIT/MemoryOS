# CI and staging delivery

> Delivery policy — 2026-09-12: the user owns business acceptance. CD verifies release provenance, backup/migration, deployed image identity and health/readiness. Login, upload, indexing, Search and reader are not deployment gates. Recovery is manual. A green deployment does not claim business acceptance or a successful restore rehearsal.

The repository ships one GitHub Actions path: [CI](../../.github/workflows/ci.yml) verifies changes and publishes main releases; [Deploy](../../.github/workflows/deploy.yml) promotes a verified release and is called by [Deploy staging](../../.github/workflows/deploy-staging.yml) and [Deploy production](../../.github/workflows/deploy-production.yml), which only decide when their environment runs and where it points. Actions owns orchestration. The server runs [one Compose script](../../infrastructure/deployment/deploy.sh). Existing [Playwright acceptance tooling](../../web/tests/staging/search.spec.ts) remains optional for an operator; CD does not install or execute it.

## Required verification and release identity

`CI Gate` requires successful backend/infrastructure checks, frontend checks/browser fixtures, all three production image builds, the landing page checks and image smoke, and a redacted Gitleaks history scan. Main pushes run every job. On a pull request the `changes` job maps the merge commit's changed paths to areas: backend (`check`, `backend-images`: `core`, `connector`, `api`, `worker`, Gradle files, `Dockerfile`, `infrastructure`), web (`frontend-check`, `frontend`, `frontend-image`: `web`), landing (`landing`); `openapi.yml` selects backend and web, docs and Markdown select nothing, and any other path or an empty diff selects every area. The gate requires `changes` and `secrets` to succeed, every job of a selected area to succeed and every job of an unselected area to be skipped; any other failed, canceled or skipped job fails it. Obsolete PR runs are canceled; main runs are not. PR runs have no package-write or staging authority and do not retain image archives.

`frontend-check` runs the frontend static/unit/build gate on a plain runner. Browser tests run as four Playwright shards (following Playwright's CI guidance: one worker per runner, scale with shards; `fullyParallel` splits by test) with matrix fail-fast disabled; all shards must succeed for `frontend` to pass. A failing shard annotates the run and prints its own assertion; no browser report is kept, because merging blob reports into an HTML nobody opened was most of what filled the artifact store. Reports that are kept — backend test XML, the frontend unit report, landing — live three days and never fail the job that produced them, and a verified image waits two days for the deploy that collects it. API and worker images stay on one runner to reuse their shared build layers. Image jobs build with BuildKit and `type=gha` layer caches (`backend-api`, `backend-worker`, `web`); the backend Dockerfile resolves Gradle dependencies in their own layer before copying sources. Core tests run in two JVMs, each cloning PostgreSQL fixtures from a template migrated once per JVM. MinIO fixtures and the deployment default use the official Quay mirror with the existing immutable digest, avoiding the unavailable Docker Hub repository without upgrading the service.

After successful main gates, publication loads the preserved API, worker, web, interpreter and interpreter executor images, checks their revision/source labels, and pushes those bytes to GHCR. It does not rebuild them. The release artifact is named `release-<source SHA>-<CI attempt>` and contains:

- `images.env`: five digest references (API, worker, web, interpreter, interpreter executor) and the full source SHA;
- `configuration.tar`: tracked infrastructure and Flyway migrations from that revision;
- `SHA256SUMS`: configuration and image-reference checksums;
- `manifest.json`: repository, source SHA, CI run ID and attempt.

Test reports and main candidate image archives are retained seven days; release bundles are retained 90 days. There are no mutable deployment tags. A partially published image set without a successful publication job and complete artifact is not deployable. A manual rerun must produce a complete successful CI attempt containing both `CI Gate` and `Publish verified release`.

The public landing page is released separately: `Publish landing` pushes its preserved image after landing verification and secret scanning, and records the digest in its own `landing-release-<sha>-<attempt>` artifact. It is never part of `images.env`; operators deploy it with the [landing runbook](landing.md).

## First-use configuration

These are external prerequisites, not evidence that the workflow has already deployed staging. Provision a GitHub `staging` environment restricted to `main` and a dedicated SSH identity for this server. The identity can execute reviewed deployment scripts with root-equivalent deployment authority; protect its private key accordingly. Do not copy a general operator's SSH key into Actions.

| Location | Required value |
| --- | --- |
| Environment variables | `STAGING_HOST`, `STAGING_USER`, `STAGING_KNOWN_HOSTS` (host key verified through the existing trusted SSH connection) |
| Environment secrets | `STAGING_SSH_KEY` |
| Repository variable | `STAGING_AUTO_DEPLOY=true` enables promotion after successful main CI; leave unset for manual deployment only |
| Server | Docker/Compose with `--wait`, Bash, jq, flock, coreutils, tar; the existing `/apps/memoryos/.env.staging` must be a root-only regular file with mode `0600` |
| Server identity | SSH/SFTP access to its private `/apps/memoryos/incoming` directory and noninteractive sudo to run the reviewed deployment script; disable SSH forwarding and interactive terminals for this dedicated key |

Production uses the same delivery workflow through [Deploy production](../../.github/workflows/deploy-production.yml), with `PRODUCTION_HOST`, `PRODUCTION_USER`, `PRODUCTION_KNOWN_HOSTS` and `PRODUCTION_SSH_KEY` on a GitHub `production` environment restricted to `main`, and `/apps/memoryos/.env.production` on its server. There is deliberately no `PRODUCTION_AUTO_DEPLOY`: an operator selects a CI run whose release is already accepted on staging. Each environment needs its own SSH identity; never reuse the staging key. The deployment script takes the environment as an argument and derives `.env.<environment>` and the `compose.base` / `compose.<environment>` / `compose.search.<environment>` overlays from it.

No application login, smoke user, Actor variable or business-test credential is required by CD. The user tests the deployed application through normal identity and authorization paths. An optional operator-run acceptance script requires its own valid account and configuration; its result is separate from deployment status.

Application secrets are files the server itself holds under `/apps/memoryos/secrets`; no vault is reached at container start. The workflow forwards its short-lived package-read token over SSH stdin for pulling private GHCR images; the server removes the temporary Docker credential file when that operation exits. An interrupted process can require operator cleanup of its private transaction directory after the job token expires. No application credentials are read or rotated by CD.

Branch-protection changes are outside MEM-70. An owner can separately select the stable `CI Gate` check as a required merge check.

## Provisioning a server

A deployment assumes a host that already looks like this. Nothing here is created by CD, and a missing piece fails the deployment rather than repairing itself. The steps below were carried out on the production application node (Ubuntu 24.04, 12 vCPU, 31 GiB); staging predates this section and differs where noted.

**Container runtime.** Docker Engine and the Compose plugin from Docker's own repository, not the distribution's `docker.io`, which ships no Compose plugin. Also `jq`, `flock`, `tar` and `coreutils`: the deployment script calls the first three directly. The production node runs Docker 29.8.1 and Compose v5.5.1; record the version when it changes, because CI validates Compose files with the version on the GitHub runner and nothing ties the two together. They have already disagreed once, over nested variable defaults.

Do not add the deployment user to the `docker` group. Membership is root without a password; the user reaches Docker through the one `sudo` rule below.

**Directory tree.** All owned by root:

| Path | Mode | Holds |
| --- | --- | --- |
| `/apps/memoryos` | `0755` | the root the script resolves everything against |
| `/apps/memoryos/incoming` | `0755` | release bundles uploaded by CD, one directory per release |
| `/apps/memoryos/deployments` | `0700` | `pending`, `current.env`, `current.compose`, one directory per transaction |
| `/apps/memoryos/secrets` | `0700` | secret files mounted into containers |
| `/apps/memoryos/.env.<environment>` | `0600` | values Compose cannot default; the script refuses a symlink or any other mode |

Secrets live in files rather than environment variables because an environment variable is visible in `docker inspect`, in a crash log and in `/proc/<pid>/environ`. Their subdirectories follow the environment file: `minio/`, `redis/`, `opensearch/`, `interpreter/`.

The database bootstrap runs once, at the first start of an empty data directory. Changing the
connection limit or the memory settings in the repository therefore reaches a new host only.
On a host that already holds data, recreate the `postgres` container to pick up the memory
settings, and apply the limits by hand as the platform role:

```sql
ALTER ROLE memoryos_app CONNECTION LIMIT 40;
ALTER ROLE memoryos_app SET idle_in_transaction_session_timeout = '60s';
ALTER ROLE keycloak CONNECTION LIMIT 20;
ALTER ROLE keycloak SET idle_in_transaction_session_timeout = '60s';
```

A role already at its limit answers `too many connections for role`, which reads as load rather
than as a ceiling somebody chose; the pools hold their connections idle, so nothing appears to be
running at the moment it refuses.

A host reaches its first deployment with more than directories. Three things the script does not
do for itself, each of which stopped a first promotion before it was written down:

* **`vm.max_map_count` at least 262144**, in `/etc/sysctl.d/`, not only `sysctl -w`. OpenSearch
  memory-maps its Lucene segments and refuses to start below that, as a bootstrap check rather
  than a warning. Setting it without a file leaves a host that works until it reboots.
* **The supporting services started once**, from the release's own Compose files:
  `up -d --wait minio minio-bootstrap redis opensearch docling`. The rollout uses `--no-deps`,
  because those services belong to the operator rather than to a release, so a deployment onto a
  host where they were never started brings up an api that cannot reach Redis and waits four
  minutes for a health check that will not go green — after the reservation is taken.

  Those services mount their scripts and configuration from the release directory that started
  them. `deploy.sh` therefore leaves the release's `source` tree readable, and everything else
  in the transaction readable by root alone. **Removing a release directory** that a running
  container still mounts leaves that container unable to start again. Before removing one, check
  that no container's mounts name it:
  `docker inspect --format '{{.Name}} {{range .Mounts}}{{.Source}} {{end}}' $(docker ps -aq) | grep <release>`.
  When a later release changes one of their files, recreate that service from the current release.
* **The Search security configuration loaded once**, with
  `--profile ops run --rm search-security-bootstrap`. With
  `plugins.security.allow_default_init_securityindex: false` the node answers 503 until
  `securityadmin.sh` has run, so its health check stays red and nothing that depends on it starts.

The first deployment on a host is recognised by the absence of a running `memoryos-api`, not by a
missing `current.env`: a runtime built over SSH before this script existed also has no
`current.env`, and it does have something to roll back to. On a first deployment there is nothing
to capture, so `rollback` refuses rather than restoring nothing, and the reservation stays until
an operator has looked. Recovery is to stop the candidate api, worker, web and interpreter and
remove `deployments/pending`. Do **not** take the stack down with its volumes: PostgreSQL holds the
Keycloak realm as well, and the migrations the failed attempt applied are carried by the next
release too, so the next deployment's migration check passes against them.

**Off-host backup.** Every night at 02:10 (UTC+7) `memoryos-backup@<environment>.timer` runs
`infrastructure/backup/backup.sh`. One archive holds what the host could not rebuild on its own:
both databases, dumped by `infrastructure/postgres/backup-databases.sh`, which proves each dump
with `pg_restore --list`; the object store; the secrets directory and environment files; and the
reverse proxy's state. The credential encryption keys travel with the database on purpose: rows
restored without them are credentials nobody can decrypt. OpenSearch and Redis are left out — the
index is rebuilt from PostgreSQL, and Redis holds only queues.

The archive is compressed and encrypted with `age` to a public key before it leaves the host. The
**private key is not on any server**: it is kept in the team's password manager, because a key
that lived on the host would be lost with it. The archive is sent by rsync to
`memoryos-backup@<target>:`, an account whose `authorized_keys` entry runs `rrsync -wo`, so the
sending host can add a backup but cannot read, list or delete one. Retention runs on the target
(`memoryos-backup-prune.timer`, 05:00): the newest 14 archives, plus the oldest of each of the
last six months. The sending host keeps its newest three for a fast restore and writes
`backups/last-success` only after every target has the archive.

Prove a backup rather than assume one:

```sh
sudo /usr/local/lib/memoryos-backup/restore-drill.sh <archive.tar.zst.age> <age-identity-file>
```

It decrypts the archive, restores both databases into a throwaway container on no network,
counts what came back, and checks that the encryption keys are present. Put the identity in
`/dev/shm` for the drill and shred it afterwards. Configuration lives in
`/etc/memoryos-backup.conf`; see `infrastructure/backup/backup.conf.example`.

**Never run `docker image prune -a` on a deployment host.** The interpreter executor image is
started only for a Python execution, so no container holds it between runs and prune deletes it;
the interpreter then answers 503 and the next deployment refuses to replace a runtime it cannot
call healthy. That happened on staging. Superseded release images are removed instead by
`infrastructure/deployment/prune-release-images.sh`, which keeps every image named by the running
release and by the one accepted before it — the rollback target — including the executor, and
never considers an image outside `ghcr.io/kl3init/memoryos-*`. It does nothing while a deployment
holds the lock or a reservation is pending. Install it once per host:

```sh
sudo install -m 0755 infrastructure/deployment/prune-release-images.sh /usr/local/sbin/memoryos-prune-release-images
sudo install -m 0644 infrastructure/deployment/systemd/memoryos-prune-release-images.{service,timer} /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now memoryos-prune-release-images.timer
sudo /usr/local/sbin/memoryos-prune-release-images --dry-run
```

**The reverse proxy is not part of a release.** It terminates TLS for every host on the node and
must survive a deployment, a rollback and a teardown, so it lives in its own composition at
`/apps/proxy/compose.yaml` and joins `proxy-network`. It publishes 80 and 443; its administration
interface stays on `127.0.0.1:81` and is reached through a host it serves itself, because `ufw`
cannot filter a port Docker publishes — Docker writes its own iptables rules and the bind address
is the whole protection.

Nginx Proxy Manager already sets the forwarding headers, `Host`, TLS 1.2/1.3, gzip,
`server_tokens off`, 90-second proxy timeouts and a 2000 MiB body limit. Only what those do not
cover is configured, and only on the host that needs it:

| Host | Upstream | Configured beyond the defaults |
| --- | --- | --- |
| `app.vadan.app` | `memoryos-web:8080` | `proxy_read_timeout 300s`, matching `web/nginx.conf` for `/api`; `= /api/meeting-stream` at 5400s because a quiet room sends nothing for minutes; the voice stream at 660s. WebSocket on. |
| `auth.vadan.app` | `memoryos-keycloak:8080` | nothing |
| `objects.vadan.app` | `memoryos-minio:9000` | `proxy_request_buffering off`, so a 250 MiB attachment streams to the store instead of spooling to the proxy's disk |
| `observability.vadan.app` | `memoryos-grafana:3000` | WebSocket on, for Grafana Live |
| `proxy.vadan.app` | `127.0.0.1:81` | nothing |

Response buffering is **not** disabled anywhere: the API sends `X-Accel-Buffering: no` on the
responses it streams, which nginx honours per response, so disabling it per host would also
disable it for static assets.

Write those per-path timeouts in the host's own advanced configuration, never as a Proxy Manager
custom location. A custom location names its upstream literally, so nginx resolves it while
loading: with `memoryos-web` absent, during a deployment or before the first one, the
configuration test fails and the whole host is disabled until somebody saves it again. The
advanced configuration includes `conf.d/include/proxy.conf`, which passes through the proxy's own
`$server` and `$port` and is resolved per request.

The observability stack is a prerequisite, not a companion. The api and worker join
`memoryos-telemetry`, which is declared external and owned by that stack, and they read
`MEMORYOS_OTLP_BASE_URL` with no application default. A deployment onto a host where the stack has
never been started fails at `compose up` with a missing network; one where the address is absent
fails later, while the application defines beans. Start
`infrastructure/observability/compose.observability.yaml` before the first rollout on a new host.

The environment file feeds Compose interpolation and nothing else. A value reaches a container only when the service block in `compose.base.yaml` names it, so adding a key here does not by itself make the application see it — that was how the first deployment without the vault failed. Addresses inside the composition (the database, Keycloak's admin API, OpenSearch) are written in Compose and must not be repeated here; a stale copy silently wins over the composition. `infrastructure/deployment/test_configuration_reaches_the_container.py` holds both rules.

**Networks.** `docker network create proxy-network`. Compose declares it `external`, so it is not created on demand and the whole stack refuses to start without it. Production declares no other external network; `shared-infra` exists only on the host MemoryOS shares with OrgMemory.

**Reverse proxy.** Nginx Proxy Manager on `proxy-network`, forwarding to `memoryos-web:8080` by container name. No application service publishes a host port, so only the proxy is reachable from outside. Raise `client_max_body_size` on the object-storage host: the browser uploads directly to MinIO through it, and the default rejects large files at the proxy before MinIO ever sees them.

**Access.** Open 22, 80 and 443 only. Port 81 is the proxy's own administration interface and belongs behind an SSH tunnel, never on the public interface. Disable `PasswordAuthentication`. Create a deployment user for CD whose sudo rule names the script exactly:

```
memoryos-ci ALL=(root) NOPASSWD: /usr/bin/bash /apps/memoryos/incoming/*/deploy.sh *
```

**That rule pins the file name.** Renaming the script in the repository without updating this line makes every deployment stop at `sudo: a password is required`, after the bundle has been uploaded and before anything is reserved. It happened once on staging, where the rule still named `deploy-staging.sh`. Validate any edit with `visudo -c` before installing it, and keep both names while releases published under the old one are still deployable.

**TLS.** Certificates for the application, identity and object-storage hosts, issued through the proxy once DNS resolves to this machine. Ask for them before DNS propagates and Let's Encrypt counts the failures against an hourly limit.

**Values that only fail on the server.** `MEMORYOS_KEYCLOAK_HOSTNAME` is required precisely because a default would silently authenticate one environment against another's realm. `MEMORYOS_SEARCH_REPLICAS` must be `0` on a single data node, or every replica shard stays unassigned and the OpenSearch health check, which waits for a green cluster, never passes.

### Serving node

`hn-fci-k8s-aioffice-serving` (172.24.244.79, 8 vCPU, 15 GiB, RTX 4090 24 GB) has no public address; reach it with `ProxyJump` through the application node. It holds the off-host backups and is where GPU services run ([MEM-192](../increments/active/mem-192-ocr-gpu/design.md), [MEM-135](../increments/active/mem-135-embedding-settings/design.md)). Prepared on 2026-09-23:

* **Firmware.** Legacy BIOS boot, so there is no Secure Boot and no module signing step.
* **Driver.** `ubuntu-drivers install --gpgpu nvidia:580-server-open` plus `nvidia-utils-580-server`: driver 580.178.04, CUDA 13.0, prebuilt modules for kernel `6.8.0-142-generic`, no DKMS. The driver packages **and** `linux-generic`, `linux-image-generic` and `linux-headers-generic` are held. Holding only the driver lets unattended-upgrades install a kernel with no matching module, and the GPU disappears at the next reboot. Upgrade both together by hand: release the holds, upgrade, reboot, confirm `nvidia-smi`, hold again.
* **Containers.** Docker 29.8.1 and Compose v5.5.1 from Docker's repository, and NVIDIA Container Toolkit 1.20.1 configured with `nvidia-ctk runtime configure --runtime=docker`. `/etc/docker/daemon.json` also sets `"ip": "127.0.0.1"`, so a port published without an address binds to loopback, and caps `json-file` logs at 50 MB × 5. A service meant for the application node must publish on `172.24.244.79:<port>` explicitly.
* **Firewall.** `ufw` denies incoming by default and allows only 22/tcp from `172.24.244.120`, which carries both the jump host and the backup account. Open a service port for that address only; `ufw` cannot filter a port Docker publishes, so the bind address remains the real boundary.
* **Backups.** `memoryos-backup` accepts writes through `rrsync -wo /srv/memoryos-backups`; the application node sends at 02:10 and `memoryos-backup-prune.timer` runs here at 05:00. Reboot outside that window, and after any SSH or firewall change confirm that the backup key still authenticates.
* **Open.** `PasswordAuthentication` and `PermitRootLogin` are still `yes`; disabling them was deferred on 2026-09-23.

**Rollout.** The production deployment rolls this node out before the application node, because the worker it is about to start reads scanned documents through it. The step connects with `ProxyJump` through the application node, copies the release bundle and `deploy-serving.sh` to `/apps/memoryos-serving/incoming/<release>/`, and runs that script under one sudo rule. The script checks the bundle, installs and restarts `memoryos-serving-firewall.service` **before** any port is published, then pulls the digest-pinned images and runs `docker compose up --wait` on `compose.serving.yaml`. A release without `deploy-serving.sh` skips the step. Nothing on this node is built by the release; the release only decides which configuration runs.

What the node must already have, beyond the list above:

| Item | Value |
| --- | --- |
| Tree | `/apps/memoryos-serving` root-owned `0755`; `/apps/memoryos-serving/incoming` owned by `memoryos-ci`, `0770`, because the workflow creates each release directory as that user, as on the application node |
| Environment | `/apps/memoryos-serving/.env.serving`, root, `0600`, from [`serving.env.example`](../../infrastructure/deployment/serving.env.example); it holds no secret |
| Embedding key | `/apps/memoryos-serving/secrets/tei/api-key.txt`, root, `0600`, generated on the node: `install -d -m 0700 /apps/memoryos-serving/secrets/tei && (umask 077; openssl rand -hex 32 > /apps/memoryos-serving/secrets/tei/api-key.txt)`. `deploy-serving.sh` refuses the rollout while it is missing or empty and prints only its path. The same value is entered once as the key of the serving embedding provider on the Search settings page; it never leaves the two nodes |
| Deployment user | `memoryos-ci`, password locked, not in `docker`, key only, with `memoryos-ci ALL=(root) NOPASSWD: /usr/bin/bash /apps/memoryos-serving/incoming/*/deploy-serving.sh *` checked by `visudo -c` |
| GitHub `production` environment | variables `PRODUCTION_SERVING_HOST` (`172.24.244.79`) and `PRODUCTION_SERVING_USER`, secret `PRODUCTION_SERVING_SSH_KEY` (its own key, not the application node's), and the node's host key appended to `PRODUCTION_KNOWN_HOSTS` under `172.24.244.79` |

**Embedding service ([MEM-135](../increments/active/mem-135-embedding-settings/design.md)).** `tei` runs Text Embeddings Inference 1.9.4 (Ada Lovelace image, pinned by digest) serving `Qwen/Qwen3-Embedding-4B` at revision `5cf2132abc99cad020ac570b19d031efec650f2b`, published on `172.24.244.79:18090` (`MEMORYOS_TEI_PORT`), which must be in `MEMORYOS_SERVING_PORTS`. The one-shot `tei-model-download` puts that revision in the `tei-models` volume (one `model.safetensors`, or every shard its `model.safetensors.index.json` names) before every start and exits at once when it is already there, so only the first rollout needs to reach `huggingface.co`; `tei` itself runs with `HF_HUB_OFFLINE=1` and mounts the volume read-only. The key reaches TEI as its `API_KEY` variable, read from the secret file at start, never as a command-line argument. The health check passes only while `/health` answers with the key **and** `/v1/embeddings` refuses a request without it (401), so a server that stopped requiring the key fails the rollout. Memory is capped at 6 GiB (`MEMORYOS_TEI_MEMORY_LIMIT`); measured use is 4.6 GiB RAM after loading (5.9 GiB peak while loading) and about 8.4 GB VRAM, 18.1 GB for the whole GPU beside PaddleOCR-VL. The api and worker call `http://172.24.244.79:18090/v1` through the embedding provider configured on the Search settings page, not through deployment variables. To rotate the key, replace the file, run `docker compose ... up -d --force-recreate tei` with the accepted configuration, then update the provider's key on the Search settings page; searches fail in between.

The jump is an SSH forward through the application node's deployment user. That user's `memoryos-ci` authorized key on the application node carries `restrict,port-forwarding,permitopen="172.24.244.79:22"`: it may forward to the serving node's SSH port and nowhere else, which is all `ProxyJump` needs, and it gets no other rights there. The firewall reads `MEMORYOS_SERVING_ALLOWED_SOURCE` and `MEMORYOS_SERVING_PORTS` from the same environment file; after changing them, restart the unit and confirm that the application node still reaches the port and another address does not. On 2026-09-23 the rule admitted the application node (HTTP 200), refused it once another source was configured, and left one rule after two runs.

**Staging path.** Staging reads scans through the same layout API at `MEMORYOS_EXTRACTION_PADDLEOCR_VL_ENDPOINT=https://ocr.vadan.app`, a deliberate exception to the [MEM-171](../increments/active/mem-171-production-deployment/design.md) environment separation, accepted by the product owner on 2026-09-23. `ocr.vadan.app` is a Nginx Proxy Manager host on the application node whose access list admits only the staging server's address `72.62.193.33` (403 from anywhere else) and forwards to `172.24.244.79:18080`. The serving node therefore still sees every request come from `172.24.244.120`, and its `DOCKER-USER` rule is unchanged. Each worker sends at most `MEMORYOS_EXTRACTION_PADDLEOCR_VL_MAX_CONCURRENT_REQUESTS` documents at once, so staging load stays bounded on the production GPU.

### MinIO images

MinIO withdrew its public images on 2026-09-24: `quay.io/minio/minio` and `quay.io/minio/mc` answer 401 or "no such manifest", and `minio/minio` no longer exists on Docker Hub. CI and any host without a cached copy could no longer pull them. The copies cached on the production application node were mirrored, unchanged, to public GHCR packages of this repository:

| Image | Mirror | Upstream digest it came from |
| --- | --- | --- |
| MinIO server `RELEASE.2025-04-22T22-12-26Z` | `ghcr.io/kl3init/memoryos-minio@sha256:159a90402c72e031227cdf0f3fb0ba82c54e517a110b2d0b462817dc849b0ac2` | `quay.io/minio/minio@sha256:a1ea29fa28355559ef137d71fc570e508a214ec84ff8083e39bc5428980b015e` |
| MinIO client `RELEASE.2025-04-16T18-13-26Z` | `ghcr.io/kl3init/memoryos-minio-mc@sha256:bddaf9ead3bf24765ffe4c63f7baf4791d3bbd594f1fb8b7cbaff17b433ef2dd` | `quay.io/minio/mc@sha256:aead63c77f9db9107f1696fb08ecb0faeda23729cde94b0f663edf4fe09728e3` |

Each mirror is the upstream image plus labels naming this repository, the upstream digest, the AGPL-3.0 licence and the upstream source tag; the layers are unchanged, so the digest differs only because the configuration gained labels. `compose.base.yaml` and the Testcontainers tests pin the mirrors. A host that already runs the upstream digest keeps it until MinIO is next recreated. Building MinIO from source, or replacing it, is a separate decision.

## Deploy and accept

Start the first deployment after the implementation is merged and its main CI has published a release:

```sh
gh workflow run deploy-staging.yml --ref main -f ci_run_id=<successful-main-CI-run-id>
gh run list --workflow deploy-staging.yml --limit 5
gh run watch <deployment-run-id> --exit-status
```

The workflow verifies same-repository main-push provenance, successful CI and publication jobs, ancestry, checksums and the selected attempt. Automatic promotion skips a source SHA superseded on main by a change CI verifies; later commits that CI ignores (docs, root Markdown, `tools/visual-paradigm-mcp`) do not supersede it, because they publish no newer release. Manual selection permits an older verified release, subject to schema compatibility checks. Neither path accepts a PR build or arbitrary image tag.

Before SSH or changing containers, the workflow validates the release bundle and staging SSH configuration. It does not run an application-login preflight or alter identity bindings and permissions.

GitHub concurrency preserves a running deployment. A server `flock` excludes simultaneous mutations; `/apps/memoryos/deployments/pending` reserves the environment until health/revision verification and finalization complete. Failure or cancellation reports the attempted transaction without automatically rolling back. Any existing reservation remains for explicit operator recovery instead of allowing another release to overwrite an uncertain state.

The server validates the existing healthy runtime (API, worker, web, and the interpreter once an accepted release includes it), retains its actual image IDs and Compose paths, validates candidate configuration and image revisions, and rejects candidates missing an applied migration. After pulling images, free disk must exceed twice the database size plus 2 GB. It stops worker and API writers, creates a PostgreSQL custom-format backup, checks its restore catalogue and checksum, then starts API through normal Flyway. API readiness must succeed before worker/web rollout; the interpreter rolls out last. This single-instance topology has a maintenance interruption; it does not provide zero-downtime migration.

After rollout, the workflow finalizes the healthy runtime and reports deployment success. The user performs login, upload, indexing, Search and reader acceptance separately. CD creates no synthetic business data and does not claim those flows passed.

Acceptance rechecks running image IDs, revision labels and health. The script writes `deployments/current.env`, `deployments/current.compose`, the private `deployments/current.base.env` configuration snapshot, and the transaction result before releasing the reservation. A later rollback uses that accepted snapshot, even if the desired `.env.staging` configuration changes. Operator Compose commands must use the accepted configuration/image record; the original `.env.staging` remains the desired input for the next deployment:

```sh
# In a privileged Bash session on the staging server:
files=()
while IFS= read -r file; do files+=(-f "$file"); done < /apps/memoryos/deployments/current.compose
docker compose --project-name memoryos --env-file /apps/memoryos/deployments/current.base.env \
  --env-file /apps/memoryos/deployments/current.env "${files[@]}" ps
```

Do not run a second deployment outside this workflow/reservation protocol. Retain the current and previous image IDs, referenced configuration directories, and backups until a newer release and its recovery path have been accepted. Remove older unreferenced artifacts only after verifying those references; disk pressure fails preflight rather than pruning rollback material automatically.

## Interpreter runtime

`memoryos-interpreter` ([MEM-110](../increments/completed/mem-110-memoryos-interpreter/design.md)) runs Python for the Chat `run_python` tool. No MemoryOS component calls it until MEM-110 phase 3.

- **Release.** The CI `interpreter` job builds the service and executor images, tests them, and preserves both as `candidate-interpreter`.
- **Pull.** The deployment pulls the service through Compose and the executor with `docker pull`, both with the job-scoped token, and checks both revision labels. The executor is not a Compose service: the interpreter starts one executor container per run on the host daemon.
- **Acceptance.** The interpreter rolls out after worker and web and must be healthy. Its `/health` reports an error when Docker is unreachable or the executor image is missing.
- **First release and rollback.** The first deployment that includes the interpreter has no previous interpreter to capture. Rolling back to a release without it stops the candidate interpreter container, because the previous Compose files have no interpreter service.

Operating constraints:

- **Socket.** The service runs as root with `/var/run/docker.sock` mounted. A caller of its API runs code in containers it creates, and a compromised service controls the host's Docker daemon. It therefore joins only `memoryos-internal` with no host port, and executors run with `--network none`. Do not add a port, proxy route or network before the service has its own credential, which ships with its Java caller in MEM-110 phase 3.
- **Capacity.** Each executor is a separate host container limited to 1 GiB memory and 30 s CPU, outside Compose resource settings. The service runs at most `MAX_CONCURRENT_EXECUTIONS` executions at once (4 on staging, overridable with `MEMORYOS_INTERPRETER_MAX_CONCURRENT_EXECUTIONS`) and answers further requests with HTTP 429, so executors use at most about 4 GiB of host memory together.
- **API key.** The interpreter requires `X-Api-Key` on every `/v1` route, and the api sends the same key. With no key configured the service refuses to start, unless `ALLOW_UNAUTHENTICATED=true` is set, which only local runs and CI do. Both read one host file, `/apps/memoryos/secrets/interpreter/api-key.txt`, mounted as the Compose secret `interpreter_api_key`.
  - Create the file before the first deployment that mounts it: `install -d -m 0700 /apps/memoryos/secrets/interpreter && (umask 077; openssl rand -hex 32 > /apps/memoryos/secrets/interpreter/api-key.txt)`.
  - Compose file secrets keep the host owner and mode, so both containers read the 0600 key through `DAC_OVERRIDE` (root with every other capability dropped). The first rollout on 2026-09-16 lacked it on the interpreter, which failed startup with `PermissionError`; the key was widened to 0644 to finish that transaction (its parent directories stay 2700). Restore 0600 once a release with `cap_add: DAC_OVERRIDE` on the interpreter is accepted.
  - The deployment stops before reserving when any Compose secret file is missing.
  - To rotate the key, replace the file, then recreate `memoryos-interpreter` and `memoryos-api` with the accepted configuration; both read the key at start.
- **Uploaded files.** Files uploaded for a run expire after `FILE_TTL_SEC` (900 seconds on staging) and are removed every minute.
- **Logs.** The service writes JSON logs to stdout. Read them with `docker logs memoryos-interpreter`; they are not exported through OTLP.
- **Executor image.** The image is about 3.3 GB, including LibreOffice Calc for `recalc-xlsx`. The service's image watchdog is disabled because the service has no registry credentials. `docker image prune -a` or `docker system prune -a` removes the executor image; `/health` then fails, and runs fail until the next deployment pulls it again. The interpreter CI job has no layer cache yet, so each release adds roughly 3.5 GB of new layers on the host; remove only interpreter images that neither `deployments/current.env` nor a retained `previous.env` references.

## Keycloak runtime

Each release builds `memoryos-keycloak` from `infrastructure/keycloak/Dockerfile`: Keycloak 26.7.0 pinned by digest and optimized for PostgreSQL with health and metrics on, plus the `memoryos` login theme copied into the image. CI starts the image against a throwaway PostgreSQL and fails unless a login page uses the theme and every image the stylesheet names is served (`infrastructure/keycloak/smoke-test-image.sh`). Keycloak does not fail on a missing theme. It logs `Failed to find LOGIN theme memoryos` and shows its built-in page, so a healthy container proves nothing about the theme.

The theme is not mounted. A release directory is root-only (`deploy.sh` runs under `umask 077`), and Keycloak runs as uid 1000, so a theme mounted from the release can never be read.

**Who runs Keycloak depends on the environment file.**

- **The file names no `MEMORYOS_KEYCLOAK_IMAGE`** (production): the release owns Keycloak.
  - The deployment dumps the `keycloak` database next to the `memoryos` one, because a newer Keycloak migrates its schema on start.
  - It rolls Keycloak out before the api and checks its health and revision like the other components.
  - Sign-in is down for about a minute during each deployment.
- **The file names an image** (staging): the release leaves Keycloak alone. The deployment removes Keycloak from the candidate image list, so the release's image cannot override the file's. Staging's Keycloak is shared with OrgMemory, and its `orgmemory` realm uses the `orgmemory-shadcn` theme that only the OrgMemory image carries. `compose.staging.yaml` mounts the `memoryos` theme into it, and the operator recreates it.

**Handing Keycloak to the release on a host that ran it by hand:**

1. Remove `MEMORYOS_KEYCLOAK_IMAGE` from `.env.<environment>`.
2. Run the next deployment.

That first deployment captures no previous Keycloak: the one running carries another project's revision label. So a rollback of that deployment leaves the new Keycloak running rather than stopping sign-in. Later deployments capture and restore it like the other components.

## Failure and recovery

On deployment failure or cancellation, the workflow reports the transaction and preserves any reservation. An operator selects recovery explicitly. The existing `rollback` command stops candidate writers and compares Flyway history with the captured history before restoring prior image IDs/configuration. Changed history stops recovery without restoring data. Matching Flyway history is a structural guard, not proof of semantic compatibility for arbitrary application/data-format changes; inspect compatibility before choosing rollback. The original deployment remains failed or canceled.

Cancellation reporting and ephemeral SSH credential cleanup are best effort; runner loss can interrupt them. The manual rollback command must acquire the same nonblocking server lock; it fails without mutation if the original SSH operation still owns that lock. No timeout or cancellation path removes `pending`; only guarded finalization does.

Changed schema, failed rollback, unhealthy/mixed images, lost SSH or interrupted deployment requires an operator. Subsequent deployments remain blocked by `pending`. Do not delete that file merely to unblock CI.

When the pending transaction's candidate or already-restored previous runtime is healthy, an operator can dispatch the same workflow with `-f recovery_release=<exact-pending-SHA-run-attempt>` in addition to `ci_run_id`. The workflow requires that revision on main, then invokes that transaction's existing `finish` guard before starting the new deployment. That guard requires exact pending ownership, healthy images and matching source revisions. A mismatch or unhealthy runtime leaves the reservation intact. This path neither runs rollback nor restores data; schema recovery or a mixed runtime still requires the operator procedure below. Leave this optional input empty for normal deployments.

1. Read the pending release identifier and its private `deployments/<release>/` directory. Inspect `schema.before`, any failure snapshot, the backup catalogue/checksum, candidate/previous image references, and container health. Inspect bounded server logs locally; do not upload environments, tokens, presigned URLs or raw login traces.
2. If the schema is unchanged, rerun the same transaction's `rollback` command under sudo. The script acquires the server lock and checks that the reservation still belongs to this transaction.
3. After the selected images are healthy and match their expected revisions, run the same script with `finish`. This records deployment/recovery only; the user performs business acceptance separately.
4. If the schema changed, leave writers stopped while deciding between a forward fix and restoring the verified backup. Rehearse restoration separately; catalogue readability is not a restore rehearsal. A restore discards writes after that backup and may need reconciliation of object storage, queues and derived indexes. There is no unattended database restore or Flyway checksum edit.
5. After operator-led schema recovery, capture and verify the chosen image/configuration set, its schema and health. Reconcile the transaction record before releasing `pending`; retain the recovery decision and any data-loss boundary in Linear. Business acceptance remains a separate user decision.

Smoke prints only the ID of its newly created source for recovery. If cleanup was interrupted, remove that specific source through the normal authorized source-delete operation and wait for completion. Preserve human acceptance documents. Live smoke disables traces, screenshots and video; fixture browser reports remain separately available in CI.

Post-merge deployment, interruption duration, migration duration and recovery evidence belong in MEM-70. Do not open a documentation-only PR to record those results. See the [delivery verification matrix](../tests/delivery.md) for the distinction between checked-in automation and live acceptance.

## Model serving

Staging runs no self-hosted model. Managed serving from MEM-77 (vLLM, gateway, provisioning, drain/rotation/serving-rollback, native metrics) was removed on 2026-09-19 because no qualified environment exists: the shared host had about 2.8 GB available memory against the 5.47 GB pre-start floor. Chat uses providers configured in the model catalog. When a qualified environment exists, restore the design and source from commit `965d4a66` under a new increment; see [MEM-77 design](../increments/active/mem-77-provider-backend/design.md).

