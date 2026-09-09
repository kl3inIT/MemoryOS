# Testing and verification

## Choose the boundary

The canonical test policy and boundary-selection table are in [engineering conventions](../conventions.md#testing). Apply them before choosing an annotation or writing another test. Capability matrices under `docs/tests/` name the existing checks for each contract.

## Local execution

Use `gradlew.bat :core:test --tests '*IdentityValueObjectsTest' --no-daemon` (or another owning test) while changing a narrow contract. Unit tests do not require Docker simply because the full gate does. The complete `clean check` requires a working Docker daemon: PostgreSQL, Redis, MinIO and OpenSearch integration checks are mandatory.

The JVM modules run with a ten-minute deadline per Test task. Keep JUnit method parallelism disabled while fixtures have class-owned servers, shared schemas or lifecycle transitions. API tests share a PostgreSQL container but allocate a fresh database for each context through `ApiPostgresDatabase`; do not replace that isolation with a shared schema to improve timing.

Frontend commands run from the repository root:

```text
pnpm --dir web install --frozen-lockfile
pnpm --dir web check
pnpm --dir web test:e2e
```

Vitest uses two isolated workers. Browser CI uses one worker and no retry; browser artifacts contain only synthetic fixture data. The browser suite uses a local synthetic OAuth server and HTTP fixtures. It establishes browser behavior, not real Keycloak, MinIO, document processing or provider acceptance.

## Optional live provider

`DoclingServeIntegrationTest` has three live checks enabled by `DOCLING_TEST_ENDPOINT`. Its owner is the file-extraction capability maintainer. Run it against the pinned Docling service when extraction formats, the client contract or service image change; record an explicit unverified provider gap when the endpoint is absent. The always-running HTTP fixture tests cover parser mapping and failure behavior, not OCR/model quality. No Docker-backed database or transport test shares this optional status.

## Fixture ownership

- `TestDatabase.freshPostgres()` returns a fixture-owned bounded connection pool. Close it in `@AfterEach` or try-with-resources, including setup/failure paths; do not open a new physical connection for every statement or leave pools across schema resets.
- Redis-dependent worker integration tests own their JUnit container and dynamic host/port. Their execution must not depend on a developer Redis port, the `CI` environment or Arconia Dev Services activation.
- When a fixture deliberately advances a scheduled operation, make its due time unambiguously past. Database `CURRENT_TIMESTAMP` followed by an immediate JVM-clock claim is not deterministic across Windows/Docker clocks. Do not conceal that race with sleeps or retries.

## Required gates

1. Run focused tests while changing a contract.
2. Inspect every changed IDE-supported file with JetBrains static analysis; include warnings.
3. Run `gradlew.bat clean check --no-daemon` on Windows or `./gradlew clean check --no-daemon` elsewhere.
4. Exercise the actual changed runtime surface. For authentication or provider changes, use a normal temporary OIDC user, verify both rejection and success paths, and remove temporary records afterward.

A green compile does not prove runtime configuration, database behavior, or view/HTTP behavior.

## Evidence

Record concise evidence in the active increment's `verification.md`: exact command or scenario, observed result, environment boundary, cleanup, and remaining risk. Never store tokens, passwords, authorization codes, or raw secret-bearing logs.

Capability matrices under `docs/tests/` map stable requirements to their durable checks. Update the matrix whenever a contract is added, removed, or materially changed.

JUnit XML excludes standard output/error. Retain failure assertions, safe stack traces and bounded CI reports; do not upload raw runtime environments, container inspection output with environment fields, authentication state or provider payloads. Record skipped tests separately from passed tests. For a performance change, name the command, host, suite size and before/after measurements; one successful run is not proof that a historical intermittent failure is fixed.
