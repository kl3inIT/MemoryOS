# PR #28 — Implementation and verification plan

- [x] Inspect original PR and linked completed MEM-17; preserve unrelated checkouts.
- [x] Create an isolated PR checkout and merge current origin/main without rewriting history.
- [x] Harden concurrent Infisical cache synchronization and verify observable failure/quoting behavior.
- [x] Add direct Worker Dev Run/Debug and reconcile API/worker local-runtime documentation.
- [x] Run XML fallback validation, script regression checks, and terminating clean check; record unavailable PR IDE inspection.
- [x] Exercise the configured PowerShell command and Java argument-file class loading; record the unexecuted full IntelliJ startup limitation.
- [ ] Push the verified PR head, perform one CodeRabbit review pass, and converge latest-head CI.
- [ ] Merge with exact-head guard; verify exact merge-SHA main CI and record evidence.
- [ ] Move this increment to completed and clean only the isolated PR checkout after merge.

## Local verification evidence

- `gradlew.bat clean check --no-daemon --no-parallel --max-workers=1`: passed in 4m 10s; 23 actionable tasks.
- `powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File scripts/test-sync-env.ps1`: passed after the final test edit. Covers byte-faithful quoting/Unicode, native export failure, empty export, previous-cache preservation, concurrent first-cache publication, and temporary-secret cleanup.
- A throwaway launch smoke parsed all three shared configurations and executed the sync configuration's actual PowerShell interpreter, options, and script text from the PR checkout. Real authenticated Infisical export completed with 16 development variables; no values were printed.
- Both actual main classes loaded successfully through Java `@argFiles` with their Gradle-resolved runtime classpaths using `java --dry-run` and a 128 MiB heap. Classpath resolution used a separate 256 MiB Gradle process; no application services started.
- Full direct IntelliJ API/worker startup and health were **not exercised**. RAM exhaustion stopped dependency provisioning; the PR project is not imported in the remaining IDE window, so per-file PR IDE inspection was unavailable. XML parsing, the real sync command, JVM class loading, and the Gradle gate are bounded fallback evidence, not a claim of healthy end-to-end startup.
- Shared development secrets remain unchanged. Separately authorized staging reconciliation added four current Tenant keys, removed five obsolete Organization/Workspace keys and the shared profile override, and preserved all 13 retained values byte-for-byte. No staging process was restarted; the worker's corrected per-service profile takes effect only on a later authorized start.
