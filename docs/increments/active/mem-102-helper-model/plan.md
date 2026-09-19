# Implementation plan

One pull request on `kl3inIT/mem-102-helper-model`.

## 1. Schema

- [x] V84: `model_flow_default` (Tenant and flow key, nullable model with `ON DELETE SET NULL`, revision) backfilled with `CHAT_NAMING` for every Tenant that has a Chat default; `llm_provider.data_boundary` (`INTERNAL | EXTERNAL`, default `EXTERNAL`).

## 2. Catalog

- [x] `ModelFlow` and `DataBoundary`; flow rows through `ModelCatalogRepository` SQL; `ModelCatalogRepository.Provider` and `LlmProviderEntity` data boundary.
- [x] `ModelCatalogService`: `flowDefaults`, `setFlowDefault` with the Chat default eligibility rule, `resolveFlow` with fallback; initialization seeds flow rows; provider input and view carry the boundary.
- [x] `ChatModelResolver.resolveFlow`; `ChatTurnService.generateTitle` uses `CHAT_NAMING`.
- [x] Tests: eligibility, clear, fallback when unset, hidden, disabled or deleted, boundary round trip and default.

## 3. HTTP and client

- [x] `GET /api/chat/model-flows`, `PUT /api/chat/model-flows/{flow}`; provider `dataBoundary`; OpenAPI snapshot; Hey API client.

## 4. Web

- [x] "Model theo tác vụ" rows with the naming picker; boundary tag in admin pickers; provider editor boundary cards and list badge; vi/en strings; tests.

## 5. Documentation and gates

- [x] `docs/specs/chat-models.md`, `docs/tests/chat.md`; web lint, typecheck, tests.
- [x] `gradlew clean check`.
