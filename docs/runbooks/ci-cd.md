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
