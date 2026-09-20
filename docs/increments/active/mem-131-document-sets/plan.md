# MEM-131 — Onyx-compatible Document Sets

## Goal

Ship shared, Source-narrowing Document Sets for agents and direct Search without widening existing Source or document authority.

## Tasks

- [x] Add the Tenant-qualified Document Set migration, concrete persistence repository, and lifecycle/share service. → Verified by PostgreSQL-backed `ChatPersistenceIntegrationTest.documentSetsShareAndAttachToPersonasWithoutReplacingDirectSources`.
- [x] Add generated HTTP contracts for Document Set lifecycle and Search filtering. → Verified by generated `OpenApiContractTest` and stable Hey API client output.
- [x] Attach Document Sets to Persona while retaining direct Sources. → Verified by the additive Persona attachment integration case.
- [x] Apply Set narrowing to direct Search and expose the agent/Search selectors with existing shadcn primitives. → Verified by `DocumentSearchServiceTest.documentSetFilterUsesOnlyTheCurrentNarrowedSourceScope` and the Search page filter test.
- [x] Ship the Onyx-style admin surfaces: `/admin/document-sets` table plus separate create/edit form pages, and the public flag with viewer-trimmed Source names. → Verified by `ChatPersistenceIntegrationTest.publicDocumentSetsAreUsableWithoutSharesAndHideSourcesTheViewerCannotSelect` and `pnpm check`.
- [x] Regenerate OpenAPI/client and consolidate Chat/Search/architecture/roadmap/test-matrix facts. → Verified by `pnpm check`; the repository gate is recorded below.

## Verification record

- `gradlew.bat :core:test --tests "io.memoryos.chat.ChatPersistenceIntegrationTest" --tests "io.memoryos.retrieval.DocumentSearchServiceTest" --tests "io.memoryos.retrieval.SearchRequestTest"` passed.
- `pnpm check` passed: generated-client stability, i18n, lint, format, typecheck, 87 Vitest files/431 tests, route validation and production build.
- `gradlew.bat clean check` passed after the strict Modulith gate required the retrieval-to-connector `SourceCollectionScopeResolver` port; retrieval no longer depends on Chat.
- Browser smoke reached `/document-sets`, where the running frontend correctly rendered the authenticated-session error boundary because no local API/identity runtime was available. No privileged page interaction could be exercised.

## Done when

- A shared Document Set groups existing Sources, narrows agent and direct Search retrieval, and never grants Source or Document access.
- Existing `persona_source` attachments continue to work alongside new Document Set attachments.
- Lifecycle, authorization, API, browser behavior, and durable documents agree.
