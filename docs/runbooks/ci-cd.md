# CI and staging delivery

The repository ships one GitHub Actions path: [CI](../../.github/workflows/ci.yml) verifies changes and publishes main releases; [Deploy staging](../../.github/workflows/deploy-staging.yml) promotes a verified release. Actions owns orchestration. The server runs [one Compose script](../../infrastructure/deployment/deploy-staging.sh); authenticated acceptance uses the existing [Playwright stack](../../web/tests/staging/search.spec.ts).

## Required verification and release identity

`CI Gate` requires successful backend/infrastructure checks, frontend checks/browser fixtures, all three production image builds, and a redacted Gitleaks history scan. Failed, canceled or skipped jobs fail the aggregate gate. Obsolete PR runs are canceled; main runs are not. PR runs have no package-write or staging authority and do not retain image archives.

After successful main gates, publication loads the preserved API, worker and web images, checks their revision/source labels, and pushes those bytes to GHCR. It does not rebuild them. The release artifact is named `release-<source SHA>-<CI attempt>` and contains:

- `images.env`: three digest references and the full source SHA;
- `configuration.tar`: tracked infrastructure and Flyway migrations from that revision;
- `SHA256SUMS`: configuration and image-reference checksums;
- `manifest.json`: repository, source SHA, CI run ID and attempt.

Test reports and main candidate image archives are retained seven days; release bundles are retained 90 days. There are no mutable deployment tags. A partially published image set without a successful publication job and complete artifact is not deployable. A manual rerun must produce a complete successful CI attempt containing both `CI Gate` and `Publish verified release`.

## First-use configuration

These are external prerequisites, not evidence that the workflow has already deployed staging. Provision a GitHub `staging` environment restricted to `main` and a dedicated SSH identity for this server. The identity can execute reviewed deployment scripts with root-equivalent deployment authority; protect its private key accordingly. Do not copy a general operator's SSH key into Actions.

| Location | Required value |
| --- | --- |
| Environment variables | `STAGING_HOST`, `STAGING_USER`, `STAGING_KNOWN_HOSTS` (host key verified through the existing trusted SSH connection) |
| Environment variables | `STAGING_ORIGIN`, `STAGING_ISSUER`, `STAGING_STORAGE_ORIGIN`, `STAGING_SMOKE_ACTOR_ID` |
| Environment secrets | `STAGING_SSH_KEY`, `STAGING_SMOKE_USERNAME`, `STAGING_SMOKE_PASSWORD` |
| Repository variable | Leave `STAGING_AUTO_DEPLOY` unset until the first manual deployment and compatible rollback have passed; then set it to `true` |
| Server | Docker/Compose with `--wait`, Bash, jq, flock, coreutils, tar; the existing `/apps/memoryos/.env.staging` must be a root-only regular file with mode `0600` |
| Server identity | SSH/SFTP access to its private `/apps/memoryos/incoming` directory and noninteractive sudo to run the reviewed deployment script; disable SSH forwarding and interactive terminals for this dedicated key |

Create the smoke user through the normal Keycloak and MemoryOS membership paths. It needs FILE source creation/management/deletion and access to its own source. Use its real `actorId`; the current identity API exposes a fixed active Tenant context, not a tenant-ID selector. All smoke data is synthetic and created in that configured Tenant. The account must support the existing normal login flow; this increment does not alter realm authentication policy.

Application secrets continue to come from the existing Infisical/server path. The workflow forwards its short-lived package-read token over SSH stdin for pulling private GHCR images; the server removes the temporary Docker credential file when that operation exits. An interrupted process can require operator cleanup of its private transaction directory after the job token expires.

Branch-protection changes are outside MEM-70. An owner can separately select the stable `CI Gate` check as a required merge check.

## Deploy and accept

Start the first deployment after the implementation is merged and its main CI has published a release:

```sh
gh workflow run deploy-staging.yml --ref main -f ci_run_id=<successful-main-CI-run-id>
gh run list --workflow deploy-staging.yml --limit 5
gh run watch <deployment-run-id> --exit-status
```

The workflow verifies same-repository main-push provenance, successful CI and publication jobs, ancestry, checksums and the selected attempt. Automatic promotion skips a source SHA superseded on main. Manual selection permits an older verified release, subject to schema compatibility checks. Neither path accepts a PR build or arbitrary image tag.

GitHub concurrency preserves a running deployment. A server `flock` excludes simultaneous mutations; `/apps/memoryos/deployments/pending` reserves the environment until authenticated smoke and finalization complete. A canceled or disconnected workflow leaves that reservation for recovery instead of allowing another release to overwrite an uncertain state.

The server validates the existing healthy three-image set, retains its actual image IDs and Compose paths, validates candidate configuration and image revisions, and rejects candidates missing an applied migration. After pulling images, free disk must exceed twice the database size plus 2 GB. It stops worker and API writers, creates a PostgreSQL custom-format backup, checks its restore catalogue and checksum, then starts API through normal Flyway. API readiness must succeed before worker/web rollout. This single-instance topology has a maintenance interruption; it does not provide zero-downtime migration.

Playwright then uses real HTTPS/Keycloak login, creates a uniquely named FILE source, uploads one Markdown file to the presigned storage origin, waits up to three minutes for indexing, and searches/reads that file in the real UI. It checks anonymous rejection and waits for source deletion. No HTTP fixtures, source ACL bypass or application test profile are involved. This covers one account plus anonymous denial; it does not replace multi-user authorization integration checks or human feature acceptance.

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

On deployment or smoke failure, the workflow attempts to stop candidate writers and compare Flyway history with the captured history. With unchanged history, it restores the prior image IDs and Compose configuration, repeats authenticated smoke, and finalizes the restored runtime. The original deployment remains failed. Matching Flyway history is a structural guard, not proof of semantic compatibility for arbitrary application/data-format changes; reviewers must preserve rollback compatibility or choose an operator-managed maintenance release.

Changed schema, failed rollback, failed recovery smoke, lost SSH, or cancellation requires an operator. Subsequent deployments remain blocked by `pending`. Do not delete that file merely to unblock CI.

1. Read the pending release identifier and its private `deployments/<release>/` directory. Inspect `schema.before`, any failure snapshot, the backup catalogue/checksum, candidate/previous image references, and container health. Inspect bounded server logs locally; do not upload environments, tokens, presigned URLs or raw login traces.
2. If the schema is unchanged, rerun the same transaction's `rollback` command under sudo. The script acquires the server lock and checks that the reservation still belongs to this transaction.
3. Run `pnpm --dir web test:staging` with the configured smoke credentials through a secure operator environment. Then run the same script with `finish`. Both success and rollback require smoke before finalization.
4. If the schema changed, leave writers stopped while deciding between a forward fix and restoring the verified backup. Rehearse restoration separately; catalogue readability is not a restore rehearsal. A restore discards writes after that backup and may need reconciliation of object storage, queues and derived indexes. There is no unattended database restore or Flyway checksum edit.
5. After operator-led schema recovery, capture and verify the chosen image/configuration set, its schema and real smoke. Reconcile the transaction record before releasing `pending`; retain the recovery decision and any data-loss boundary in Linear.

Smoke prints only the ID of its newly created source for recovery. If cleanup was interrupted, remove that specific source through the normal authorized source-delete operation and wait for completion. Preserve human acceptance documents. Live smoke disables traces, screenshots and video; fixture browser reports remain separately available in CI.

Post-merge deployment, interruption duration, migration duration and recovery evidence belong in MEM-70. Do not open a documentation-only PR to record those results. See the [delivery verification matrix](../tests/delivery.md) for the distinction between checked-in automation and live acceptance.
