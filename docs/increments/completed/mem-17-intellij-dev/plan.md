# PR #28 — Implementation and verification plan

- [x] Inspect original PR and linked completed MEM-17; preserve unrelated checkouts.
- [x] Create an isolated PR checkout and merge current origin/main without rewriting history.
- [x] Harden concurrent Infisical cache synchronization and verify observable failure/quoting behavior.
- [x] Add direct Worker Dev Run/Debug and reconcile API/worker local-runtime documentation.
- [x] Run XML fallback validation, script regression checks, and terminating clean check; record unavailable PR IDE inspection.
- [x] Exercise the configured PowerShell command and Java argument-file class loading; record the unexecuted full IntelliJ startup limitation.
- [x] Push the verified PR head, perform one CodeRabbit review pass, and converge latest-head CI.
- [x] Merge with exact-head guard; verify exact merge-SHA main CI and record evidence.
- [x] Move this increment to completed after merge; remove the isolated checkout after publishing closeout.

## Local verification evidence

- `gradlew.bat clean check --no-daemon --no-parallel --max-workers=1`: passed in 4m 10s; 23 actionable tasks.
- `powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File scripts/test-sync-env.ps1`: passed after the final test edit. Covers byte-faithful quoting/Unicode, native export failure, empty export, previous-cache preservation, concurrent first-cache publication, and temporary-secret cleanup.
- A throwaway launch smoke parsed all three shared configurations and executed the sync configuration's actual PowerShell interpreter, options, and script text from the PR checkout. Real authenticated Infisical export completed with 16 development variables; no values were printed.
- Both actual main classes loaded successfully through Java `@argFiles` with their Gradle-resolved runtime classpaths using `java --dry-run` and a 128 MiB heap. Classpath resolution used a separate 256 MiB Gradle process; no application services started.
- Full direct IntelliJ API/worker startup and health were **not exercised**. RAM exhaustion stopped dependency provisioning; the PR project is not imported in the remaining IDE window, so per-file PR IDE inspection was unavailable. XML parsing, the real sync command, JVM class loading, and the Gradle gate are bounded fallback evidence, not a claim of healthy end-to-end startup.
- At the merge checkpoint, shared development secrets were unchanged. Separately authorized staging reconciliation had added four current Tenant keys, removed five obsolete Organization/Workspace keys and the shared profile override, and preserved all 13 retained values byte-for-byte. No staging process was restarted; the worker's corrected per-service profile takes effect only on a later authorized start.

## Review and merge evidence

- Reviewed head: `2a0be30d8114aa560bb7a5b1bfdd2af1873400a0`; all four [PR CI jobs](https://github.com/kl3inIT/MemoryOS/actions/runs/34041163343) passed.
- The single CodeRabbit pass completed. Its only actionable finding requested Linux/macOS IDE configurations; the owner explicitly confirmed Windows-only scope. The [evidence reply and resolved thread](https://github.com/kl3inIT/MemoryOS/pull/28#discussion_r3944376066) retain that decision. No second review was requested.
- Additional analyzer notes did not require source changes: background-job values are declared in the script block's parameter list and supplied through `-ArgumentList`; the cleanup-only catch preserves the original failure and still stops jobs; test-helper plural naming does not alter runtime behavior. The real CLI export confirmed `INFISICAL_DOMAIN`.
- [PR #28](https://github.com/kl3inIT/MemoryOS/pull/28) merged as `e6bcd40bd45c570795c124491a510aa2cf85bb5f`; fetched `origin/main` contained the reviewed head. All four jobs in [exact merge-SHA CI](https://github.com/kl3inIT/MemoryOS/actions/runs/34041695406) passed.

## Subsequent Infisical-only reconciliation

The user prioritized Infisical `dev`/`staging`, especially MinIO, and explicitly deferred datasource work. No application deployment or service restart accompanied these secret-store updates.

- Development now has 19 root keys: six verified isolated MinIO values, four stable development Tenant values, and known current identity/browser/session settings. The five legacy Organization/Workspace aliases, three staging-tunnel database values, and shared Spring profile override were removed. The seven retained preexisting values remained exact.
- Staging now has 21 root keys, including four MinIO metadata values verified against both deployed services. `/minio/api` and `/minio/worker` each hold their existing service-specific access/secret pair; readback matched the mounted credentials exactly. No shared root identity override, credential rotation, or MinIO root credential was introduced.
- Development is not claimed runnable: an active development `MEMORYOS_KEYCLOAK_ADMIN_CLIENT_SECRET` is still unavailable, and development browser callback/client provisioning must be reconciled at Keycloak. Local realm provisioning had been stopped for RAM pressure. Infisical cannot make an unprovisioned client credential valid, and staging management credentials were not copied into development.
- Users/Groups work was handed to a separate Codex session on the existing MEM-55/MEM-36 checkout. Datasource/JPA work remains deferred and outside this increment.
