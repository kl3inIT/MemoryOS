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

