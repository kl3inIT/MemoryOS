# MEM-11 editor and workspace verification — 2026-09-11

Scope: conversation rename/delete/edit/regenerate/branch, private assistants and projects, authenticated same-Tenant sharing, owner output feedback, and the approved Spring Data JPA lifecycle migration. One branch/PR in the existing checkout. Attachments remain MEM-81; IAM Users/Groups migration is handed to Nhật in MEM-55/MEM-36. OCR work is excluded.

## Observed behavior

- `ChatPersistenceIntegrationTest` passes against PostgreSQL/Flyway/Hibernate: owner/Tenant boundaries, stale revisions, empty custom Persona precedence over Project instructions, immutable admission settings, preserved old branches, assistant-only regeneration, command identity, sharing/feedback and deletion winning late completion.
- `ModelCatalogConstraintsTest` passes against PostgreSQL: Spring Data collection/JSONB mappings, assigned UUIDs, version advancement for association changes, cross-Tenant constraints, default protection, provider/model deletion and rollback spanning JPA/JDBC. Existing catalog HTTP integration tests also pass.
- `ChatSessionApiIntegrationTest.editorsProjectsPersonasSharingAndFeedbackRoundTripThroughAuthenticatedHttp` passes through the real random-port Spring Boot server using signed synthetic identities, PostgreSQL and the native runner. It exercises all four feature groups, owner/reader denial and all-or-nothing assistant/project settings. Its model responses are synthetic, not a provider-quality result.
- `pnpm check` passes: generated OpenAPI stability, lint/format, TypeScript, 106 unit tests, route generation and production build.
- `pnpm exec playwright test chat.spec.ts chat-workspace.spec.ts --workers=2`: **26 passed**. New scenarios cover edit/regenerate/branch reload, separate feedback for regenerated versions, shared read-only view, revocation, deletion, Persona form persistence and Project instructions/deletion. Existing streaming/reconnect/source/model/mobile scenarios continue to pass. An initial mobile composer overflow was fixed by bounding the workspace below the new header; the regression passes.
- `pnpm exec playwright test --workers=2`: **72 passed** across the complete browser suite, including the existing administration, source and invitation flows.
- `SearchToolTest.personaSourceSelectionRechecksRevocationWithoutWideningToOtherReadableSources` passes: each tool call intersects the saved Persona allowlist with current source permissions, and revoking its last source produces an empty scope instead of widening access.
- Browser screenshots contain synthetic data only under ignored `.tmp/`; the 390×844 Project view was inspected for readable controls and horizontal overflow. Browser fixtures verify UI/transport behavior; they do not represent a deployed Java/provider stack.

## Static and repository gates

All edited Java/Kotlin DSL files were inspected with JetBrains warnings enabled. Actionable nullability and unused-constructor findings were fixed. Remaining entity warnings concern IDE database mappings and reflective JPA fields; Hibernate schema validation and real persistence tests establish those mappings. Suggested final/local fields would break JPA lifecycle and were not applied. Known private loopback HTTP/custom-header warnings remain in test code. The first IDE inspection of generated `openapi.yml` timed out; the second completed with no findings. The generated contract also passed the OpenAPI integration check and TypeScript generation.

The first `gradlew.bat clean check --no-daemon` failed in one of 363 core tests: `OpenSearchRetrievalIntegrationTest.indexes3072DimensionsFusesKeywordAndSemanticResultsReusesVectorsAndRepairsProjection` returned `SearchUnavailableException` at the gateway's timeout/failed-shards guard. API, worker and connector checks passed. Running the complete OpenSearch integration class again, together with the source-revocation regression above, passed in 1m17s. This does not establish the cause of the first failure. A fresh full gate is running at PR publication; its result and current-head CI/review evidence will be recorded on the PR and linked issue.

No deployment or MEM-11 issue closure is implied by these local receipts. Live corpus/latency follow-up remains deferred under the user's existing scope instruction.
