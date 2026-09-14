# CI and staging delivery

> Delivery policy — 2026-09-12: the user owns business acceptance. CD verifies release provenance, backup/migration, deployed image identity and health/readiness. Login, upload, indexing, Search and reader are not deployment gates. Recovery is manual. A green deployment does not claim business acceptance or a successful restore rehearsal.

The repository ships one GitHub Actions path: [CI](../../.github/workflows/ci.yml) verifies changes and publishes main releases; [Deploy staging](../../.github/workflows/deploy-staging.yml) promotes a verified release. Actions owns orchestration. The server runs [one Compose script](../../infrastructure/deployment/deploy-staging.sh). Existing [Playwright acceptance tooling](../../web/tests/staging/search.spec.ts) remains optional for an operator; CD does not install or execute it.

## Required verification and release identity

`CI Gate` requires successful backend/infrastructure checks, frontend checks/browser fixtures, all three production image builds, the landing page checks and image smoke, and a redacted Gitleaks history scan. Failed, canceled or skipped jobs fail the aggregate gate. Obsolete PR runs are canceled; main runs are not. PR runs have no package-write or staging authority and do not retain image archives.

Frontend runs as two independent Playwright shards with one worker each and matrix fail-fast disabled. Shard 1 also runs the frontend static/unit/build gate. Both shards must succeed for the existing `frontend` dependency to pass; reports are retained separately as `frontend-tests-1` and `frontend-tests-2`. API and worker images stay on one runner to reuse their shared build layers. MinIO fixtures and the deployment default use the official Quay mirror with the existing immutable digest, avoiding the unavailable Docker Hub repository without upgrading the service.

After successful main gates, publication loads the preserved API, worker and web images, checks their revision/source labels, and pushes those bytes to GHCR. It does not rebuild them. The release artifact is named `release-<source SHA>-<CI attempt>` and contains:

- `images.env`: three digest references and the full source SHA;
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

No application login, smoke user, Actor variable or business-test credential is required by CD. The user tests the deployed application through normal identity and authorization paths. An optional operator-run acceptance script requires its own valid account and configuration; its result is separate from deployment status.

Application secrets continue to come from the existing Infisical/server path. The workflow forwards its short-lived package-read token over SSH stdin for pulling private GHCR images; the server removes the temporary Docker credential file when that operation exits. An interrupted process can require operator cleanup of its private transaction directory after the job token expires. No application credentials are read or rotated by CD.

Branch-protection changes are outside MEM-70. An owner can separately select the stable `CI Gate` check as a required merge check.

## Deploy and accept

Start the first deployment after the implementation is merged and its main CI has published a release:

```sh
gh workflow run deploy-staging.yml --ref main -f ci_run_id=<successful-main-CI-run-id>
gh run list --workflow deploy-staging.yml --limit 5
gh run watch <deployment-run-id> --exit-status
```

The workflow verifies same-repository main-push provenance, successful CI and publication jobs, ancestry, checksums and the selected attempt. Automatic promotion skips a source SHA superseded on main. Manual selection permits an older verified release, subject to schema compatibility checks. Neither path accepts a PR build or arbitrary image tag.

Before SSH or changing containers, the workflow validates the release bundle and staging SSH configuration. It does not run an application-login preflight or alter identity bindings and permissions.

GitHub concurrency preserves a running deployment. A server `flock` excludes simultaneous mutations; `/apps/memoryos/deployments/pending` reserves the environment until health/revision verification and finalization complete. Failure or cancellation reports the attempted transaction without automatically rolling back. Any existing reservation remains for explicit operator recovery instead of allowing another release to overwrite an uncertain state.

The server validates the existing healthy three-image set, retains its actual image IDs and Compose paths, validates candidate configuration and image revisions, and rejects candidates missing an applied migration. After pulling images, free disk must exceed twice the database size plus 2 GB. It stops worker and API writers, creates a PostgreSQL custom-format backup, checks its restore catalogue and checksum, then starts API through normal Flyway. API readiness must succeed before worker/web rollout. This single-instance topology has a maintenance interruption; it does not provide zero-downtime migration.

After rollout, the workflow finalizes the healthy three-image set and reports deployment success. The user performs login, upload, indexing, Search and reader acceptance separately. CD creates no synthetic business data and does not claim those flows passed.

Acceptance rechecks running image IDs, revision labels and health. The script writes `deployments/current.env`, `deployments/current.compose`, the private `deployments/current.base.env` configuration snapshot, and the transaction result before releasing the reservation. A later rollback uses that accepted snapshot, even if the desired `.env.staging` configuration changes. Operator Compose commands must use the accepted configuration/image record; the original `.env.staging` remains the desired input for the next deployment:

```sh
# In a privileged Bash session on the staging server:
files=()
while IFS= read -r file; do files+=(-f "$file"); done < /apps/memoryos/deployments/current.compose
docker compose --project-name memoryos --env-file /apps/memoryos/deployments/current.base.env \
  --env-file /apps/memoryos/deployments/current.env "${files[@]}" ps
```

Do not run a second deployment outside this workflow/reservation protocol. Retain the current and previous image IDs, referenced configuration directories, and backups until a newer release and its recovery path have been accepted. Remove older unreferenced artifacts only after verifying those references; disk pressure fails preflight rather than pruning rollback material automatically.

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

## Managed inference operations

MEM-77 adds managed-serving source to the existing release/reservation transaction; it does **not** activate staging automation or authorize a deployment. The earlier MEM-70 health-only exception does not waive MEM-77's real Chat, workload, rotation and compatible-recovery gates. Current [controlled evidence and blockers](../increments/active/mem-77-provider-backend/verification.md#implementation-and-controlled-evidence--2026-09-11) are not a target acceptance receipt.

### Install, provision and readiness

Use the selected release's [managed manifest](../../infrastructure/inference/managed/manifest.json), [Compose definition](../../infrastructure/deployment/compose.inference.yaml), [application overlay](../../infrastructure/deployment/compose.inference.application.yaml) and [environment contract](../../infrastructure/deployment/inference.env.example), not the MEM-66 research runtime or `.tmp` secrets. The release validates the serving-manifest hash and launcher/configuration identities separately from the three application-image provenance checks; pinned third-party images do not need MemoryOS SHA labels.

Before first use, an authorized operator must provide `/apps/memoryos/inference.env` (0600), `inference-operator.json` and `observability.env` (0600). The operator receipt names staging/single-host topology, operator, exact serving-manifest hash, credential version and a `/apps/memoryos/` capacity-evidence file plus its SHA-256. It does not replace measured physical backing-disk, host and application/Docling co-load headroom. Preflight additionally checks CPU features, Linux available memory, Docker/cache/assets disk, image identities, protected paths and secret/cache ownership. Unknown unreceipted serving containers or credentials changed outside rotation require reconciliation.

Keep assets operator-owned with a nonsymlink root; provisioning publishes revision directories 0555 and exact files 0444. Cache is separate UID/GID1654 mode0700; control directory is operator-owned0755. The inference key is a regular nonsymlink UID/GID1654 mode0400 file containing exactly 64 lowercase hex characters plus at most one final LF. Never put its value in environment metadata, command arguments or logs. Both serving processes use UID/GID1654 and readonly roots with dropped capabilities. API alone joins the client network; only gateway, engine and authorized Prometheus join the backend. Neither model port is published and ordinary serving networks have no download egress.

The CPU engine needs executable private scratch for generated native libraries: its 256 MiB `/tmp` tmpfs explicitly uses `exec`, mode0700, UID/GID1654, `nosuid` and `nodev`. Docker's implicit `noexec` default otherwise causes `.so: failed to map segment from shared object` after model weights load. The gateway's private `/tmp` remains `noexec`. Do not replace this narrow native-loading requirement with a writable root filesystem or higher memory limits.

For an approved isolated candidate, the environment example supplies the exact provision/verify-only/Compose/probe commands. `provision.py` performs bounded HTTPS download of only the pinned allowlist, size/hash verification and atomic publication; `--verify-only` checks installed assets without downloading. Readiness checks expected model/fingerprint and authenticated gateway listing; `--mode generation` is a distinct real SSE smoke. Cold provisioning timing is **not** cold model startup. Qualify model cold startup with verified assets/empty separate runtime caches, warm/offline restart with caches retained, and corrupt/missing-asset failure. Never delete the MEM-66 cache or retained previous assets to create a test condition.

Provisioning uses a stable `.provision.lock` inode with nonblocking process-lifetime locking. A killed downloader releases ownership automatically; rerun the normal provisioning command without deleting the lock file. A live publisher returns `ASSET_PROVISIONING_BUSY`. Abandoned `.provision-*` directories and current/previous revisions are not automatically deleted: retained disk still counts against preflight and any later cleanup requires explicit operator review.

Do not start the model while host capacity fails. On Docker Desktop, measure outer-host available physical memory as well as guest headroom; the guest does not override the manifest's outer-host floor. A successful downloader/tokenizer probe does not authorize model startup or weakening that floor. [Current local and target limitations](../increments/active/mem-77-provider-backend/verification.md#unverified-and-blocked-acceptance) remain separate from the operational procedure; target SSH/operator/capacity and normal-login/model credentials are prerequisites.

### Local Windows API with Docker Desktop inference

Use [compose.inference.local.yaml](../../infrastructure/deployment/compose.inference.local.yaml) only for a developer API running on the Docker host. It adds authenticated gateway `127.0.0.1:18081` → container8080 and a project-scoped ordinary bridge attached only to the gateway. This is an explicitly accepted local exception: the gateway gains an outbound route so Docker can publish its host port, while the engine remains solely on the internal backend with no published ports or ordinary egress. Engine8000 and readiness8081 remain unpublished. Do not include this overlay in staging/production. The release checker allows only the gateway bridge/loopback exception and rejects engine attachment to the bridge, public binding, engine publication, resource changes or base-network egress.

The local preparation uses project `memoryos-inference-local` and the separately labeled persistent volume `memoryos-inference-local-data`. Its POSIX `assets`, `cache`, `control` and `secrets` paths retain the ownership/modes above; Compose binds their Docker-reported mountpoint, verified with the real non-root interpreter. `%LOCALAPPDATA%\MemoryOS\inference\inference.env` contains paths/network names only and has a current-user-only Windows ACL. Keep this environment file outside Git and retain the volume between sessions; neither provisioning nor cleanup may overwrite an existing key or reuse MEM-66 state.

Inspect the composition without starting any service:

```powershell
$inferenceEnv = Join-Path $env:LOCALAPPDATA 'MemoryOS\inference\inference.env'
docker compose --env-file $inferenceEnv -p memoryos-inference-local `
  -f infrastructure/deployment/compose.inference.yaml `
  -f infrastructure/deployment/compose.inference.local.yaml config --quiet
```

Before replacing `config --quiet` with `up -d --wait --wait-timeout 600`, verify the manifest's memory floor on **both** Windows and the Docker guest, physical/Docker disk reserve, pinned assets, restricted mounts and the normal API/UI prerequisites. `config` does not perform those runtime checks. Do not start inference below the floor or stop unrelated applications to manufacture headroom.

Once real readiness/generation pass, use endpoint `http://127.0.0.1:18081/v1`, the existing `openai` adapter, encrypted local BYOK and `smollm2-135m-12fd25f-v1` with1,024/128 limits. Create a separate manager-only provider/model; keep the hosted Tenant default and select the local UUID explicitly for acceptance. The browser remains API-only. If the normal API/UI is not running, restore its existing development configuration rather than creating a one-shot application profile or writing the catalog directly in SQL.

Current preparation/engine/Chat evidence and retained state belong to [local qualification](../increments/active/mem-77-provider-backend/verification.md#local-application-qualification--2026-09-12). Prepared assets, a Compose pass or a listening gateway do not imply a loaded model or a usable Chat path.

### Drain, rotation, resume and recovery

Use the selected release's `deploy-staging.sh` under its existing root-only lock and pending reservation; do not invoke sourced `inference-operations.sh` as a second orchestrator. The command contract is:

| Operation | Arguments after script path | Guard / outcome |
| --- | --- | --- |
| Maintenance | `drain <accepted-release-id>` | Opens a serving-only reservation, writes the maintenance marker, blocks new generation with 503, and waits at most 150 seconds for gateway settlement and native idle/counter quiet evidence. |
| Resume | `resume <accepted-release-id>` | Refuses incomplete rotation; checks readiness, removes maintenance, runs real generation smoke, and closes admission again on failure. |
| Rotate | `rotate-key <accepted-release-id> <new-key-file> <handoff-request-file> <new-version>` | Requires the drained reservation, distinct protected new key/version and valid authorized revision-checked handoff inputs before changing credentials. |
| Resume rotation | `complete-rotation <accepted-release-id> <reconciled-handoff-request-file>` | Reconciles only the pending rotation; does not bypass failed catalog handoff or credential drift. |
| Serving recovery | `serving-rollback <accepted-release-id>` | Restores compatible accepted previous serving assets/configuration with valid current credentials and unchanged schema/application profile support. |
| Finalization | `finish <accepted-release-id>` | Requires exact reservation ownership, healthy application images/source revisions, verified serving images/readiness and no maintenance marker before releasing pending. Deployment retains its separate generation/monitoring receipts. Finalization records deployment or recovery, not business acceptance; authenticated Chat and the broader MEM-77 acceptance matrix remain separate. |

Drain timeout leaves admission closed and does not manufacture success from an unreachable/stopped engine. Explicitly Stop/settle affected turns through normal authorized Chat and retry. A stopped engine is a valid recovery witness only after a previously proven drain with admission continuously closed. Opening admission invalidates that witness.

The protected 0600 handoff JSON uses `applicationOrigin` (exact HTTPS origin), `providerId`, positive `providerRevision`, `sessionCookieFile` (protected normal authorized browser session) and `expectedProviderBaseUrl` (`http://inference-gateway:8080/v1`). It identifies the explicit enabled managed OpenAI provider, never discovers one by alias or grants authority. Keep all credential/session content out of receipts. A 409 or changed provider requires full revision/Access reconciliation, not automatic retry.

Rotation installs the new restricted key, recreates **both** containers (atomic file replacement cannot refresh old bind inodes/in-process keys), revision-checks BYOK replacement preserving Access, verifies new-key readiness and old-key rejection at both engine/gateway, then resumes. Keep the catalog AES key unchanged and backed up with the database; no dual-key rotation or AES re-encryption is implemented. Failed rotation retains maintenance/reservation for explicit completion/recovery; do not delete `pending` or manually resume around it.

Compatible serving-only recovery must preserve prior assets/profile support and still-valid secret references. Application/schema-changing failure retains the existing Flyway-history guard, stopped writers and reservation for approved forward repair/operator recovery; it does not automatically restore a database or run an incompatible old binary.

Application rollback retains the accepted `MEMORYOS_INFERENCE_CLIENT_NETWORK` rather than falling back to a different default network. Monitoring Compose derives its backend network from the selected serving configuration, overriding stale candidate values in the observability environment. Serving-only recovery requires the previous client **and** backend network names to match the current accepted runtime; a mismatch refuses recovery before drain/start, because the API and monitoring project are not being migrated. Reconcile topology through an approved deployment instead of forcing the recovery path.

### Capacity, monitoring and smoke

One shared gateway generation slot has zero intentional queue and immediate excess 429; authenticated model-list probes are outside admission. Chat and Validate contend for that same slot. Maintenance 503, overload 429, inactivity limits and native absolute deadlines are distinct. Final real-model qualification must exercise omitted/raised output fields, `n>1`, cold/warm startup, Stop/deadline slot release and application co-load under the manifest thresholds. Readiness or a green Validate is not this acceptance.

Prometheus alone gets the private native scrape; expected model/gateway readiness remains a separate authenticated probe. Verify actual series/units/labels and dashboard/rule evaluation before accepting them. Gateway logs are bounded local diagnostics, not automatically Loki-ingested metrics; operator cgroup/host/disk/cache/restart receipts remain necessary. Telemetry failure must not gate healthy inference. For disk pressure, retain referenced current/previous assets/images and fail preflight; identify unreferenced cache/assets before approved cleanup rather than automatic pruning.

Models administration keeps hosted bootstrap/Tenant default unchanged. Configure the local manager-only provider through normal encrypted BYOK, choose the installed `smollm2-135m-12fd25f-v1` profile with 1024/128 candidate limits, and select its configured UUID explicitly or through an eligible Persona default. Do not substitute the wire alias for UUID selection, silently accept fallback, or promote provider Access for smoke.

`web/tests/staging/chat.spec.ts` is implemented in existing `test:staging` tooling but **UNRUN**. Supply `MEMORYOS_SMOKE_ORIGIN`, `MEMORYOS_SMOKE_ISSUER`, `MEMORYOS_SMOKE_CHAT_USERNAME`, `MEMORYOS_SMOKE_CHAT_PASSWORD`, `MEMORYOS_SMOKE_CHAT_ACTOR_ID` and `MEMORYOS_SMOKE_CHAT_MODEL_ID` only through the protected runtime environment. The test reads them inside execution, not collection. Use a real existing `MODELS_MANAGE` actor with its builtin Persona already selecting that local configured model UUID; the scenario neither edits defaults nor grants authority.

The scenario uses normal Keycloak login, creates a named owned session, selects the composer's Auto mode to inherit the preconfigured Persona model, and requires 202 expected UUID/no fallback, SSE/visible reply and Unicode persisted reload. A second request must be observed RUNNING, stopped with the real UI and durably CANCELED/reloaded as Đã dừng. A model finishing before that observation or winning the completion/Stop race fails rather than skipping or retrying away the gate. Finally it queries/cancels and settles active replies only in its owned session, then uses the normal authenticated `DELETE /api/chat/sessions/{id}` API and requires204. Uncertain cleanup reports only that owned UUID and failure phase. Target execution/results belong in [MEM-77 verification](../increments/active/mem-77-provider-backend/verification.md#pending-integration-results); no target success is implied by implementation.
