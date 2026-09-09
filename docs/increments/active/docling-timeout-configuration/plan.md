# Docling timeout configuration plan

- [x] Locate the active runtime checkout and preserve unrelated edits and the original checkout's unresolved merge.
- [x] Expose all twelve Docling service environment settings and the Worker timeout through Compose and the deployment env example, retaining defaults.
- [x] Relax the Java cap to fifteen minutes; retain rejection above that bound and for nonpositive budgets, without changing OCR.
- [x] Resolve default and external-env-file configurations for staging and production, and smoke-test Spring binding through the production extractor.
- [x] Consolidate the configuration contract and verification evidence for the separate commit.

## Verification

Verification used JDK 25.0.3 and the checked-in wrapper. An ignored init script redirected build output to `.tmp/docling-timeout-verification/<project>/build`, leaving the running application's normal build directories intact. `CI=true`, `ARCONIA_DEV_SERVICES_ENABLED=false`, and `MEMORYOS_OPENAPI_WRITE=false` were used for project gates.

| Check | Observed result |
| --- | --- |
| `clean check --no-daemon --no-parallel --max-workers=2 --no-configuration-cache`, with temporary output redirection | API and connector checks passed; connector had 53 tests with zero failures/errors/skips, including three real Docling cases at port 15062. Core ran 272 tests with two architecture failures caused by the initial nonstandard temporary build layout. Worker checks were not reached in this invocation. |
| Corrected layout plus `:core:test --tests io.memoryos.CoreDependencyRulesTest --tests io.memoryos.ModulithArchitectureTest :worker:check` | PASS in 3m8s: all three architecture checks and 26 Worker tests passed with zero failures/errors/skips, including real Docling at port 15063. Test tasks executed; five compilation/dependency tasks came from cache. |
| Throwaway Java launcher through `:connector:doclingTimeoutSmoke`, followed by connector test/Worker compilation tasks | PASS in 1m47s. Real Spring environment binding printed `BOUND_TIMEOUT_SECONDS=900`; the production extractor read a generated scanned PDF and printed `PRODUCTION_EXTRACTOR_OCR_AND_PAGE_PROVENANCE=PASS`. The follow-on connector test task was up-to-date, not a second test execution. |
| Compose config with staging and production overlays | Four passing cases: defaults and an external env file for each overlay, checking all twelve Docling service settings and Worker timeout. Overrides resolved to `15m`, `900`, and `910`. |
| Pinned service settings in a separate process | `DoclingServeSettings` accepted document maximum 900 and sync wait 910 from environment variables. |

The initial output layout ended in `build/<project>` rather than `<project>/build`; ArchUnit/Modulith then included test classes as production dependencies. Correcting the throwaway layout fixed the affected checks without modifying architecture code or excluding additional classes. This was not a single successful `clean check` invocation; the corrected architecture/Worker run completes the verification evidence above.

The first 900-second request against the unchanged 300-second service returned HTTP 422. The successful smoke used a separately named, digest-pinned Docling container with 900/910-second budgets, two CPUs and 4 GiB. This verifies coordinated runtime configuration, not HUT completion, throughput or financial OCR accuracy. No user Source was reindexed and existing runtime settings were not changed.

JetBrains MCP and Java LSP were unavailable; compiler, Compose and runtime checks are the fallback, not an IDE-inspection pass. The canonical configuration and evidence are in the [Ingestion contract](../../../specs/ingestion.md#docling-deployment-environment) and [verification matrix](../../../tests/ingestion.md#docling-environment-configuration--2026-09-09).

Cleanup completed: the temporary `memoryos-docling-timeout-smoke` container exited successfully and was removed; its supervised process was released. The throwaway launcher, env file, init script, isolated builds and caches under `.tmp/docling-timeout-verification` were removed. Unrelated working-tree edits remain outside this change.
