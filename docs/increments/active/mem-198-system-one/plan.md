# Implementation plan

Design: [design.md](design.md). Two pull requests: the backend with its API and generated client, then the page.

## Pull request 1: connections, the classifier and the API

### `ai.systemone`

- [x] `gradle/libs.versions.toml`, `core/build.gradle.kts`: `org.springaicommunity:typesafe-java-sdk` 0.4.0.
- [x] `V146__system_one_connection.sql`: table `system_one_connection`; `model_flow_default.system_one_connection_id`
  with its checks.
- [x] `SystemOneProvider`, `SystemOneAdapter`, `SystemOneAdapterRegistry`, `SystemOneProtocol`, the five adapters.
- [x] `SystemOneConnectionService`; audit action `SYSTEM_ONE_CONNECTION_CHANGE`.
- [x] `SystemOneClient`: `choose` and the connection test.
- [x] `FlowModelDefault`, `ModelCatalogRepository`, `ModelCatalogService`: the connection of a flow.

### Chat

- [x] `GroundingClassifier`: the choice question and its verdict.
- [x] `ChatGuardrailCheck`, `ChatTurnService`, `ChatExecutionConfiguration`: the classifier of the turn, usage, the
  `classifier` metric tag.

### API

- [x] `SystemOneController` and its `contract/` records; `ChatModelFlowResponse`.
- [x] `openapi.yml` and the Hey API client regenerated.

### Tests and documents

- [x] Adapter, service, classifier and turn tests; the V146 constraints on Postgres.
- [x] `ARCHITECTURE.md`, `docs/specs/chat-models.md`, `docs/specs/chat.md`, `docs/tests/chat.md`,
  `docs/guidelines/observability.md`, the roadmap row.
- [x] An HTTP test of the System One endpoints (manager only, `409` on a delete in use).

## Pull request 2: the page

- [x] `web/src/features/system-one/`, route `/admin/system-one`, the navigation entry, `provider-marks.ts` (TypeSafe,
  Laya), messages in `vi` and `en`.
- [x] The Models page without the check's row; the Chat settings link opens the System One page.
- [x] `system-one-page.test.tsx` (6 cases) and `tests/e2e/system-one-administration.spec.ts` at 1440 and 390 px;
  screenshots reviewed at both widths.

## After both

- [ ] Measurement on self-written questions; results in `verification.md`.
- [ ] Staging checks; then the close-out.
