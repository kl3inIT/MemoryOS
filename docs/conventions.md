# Engineering conventions

These conventions apply across MemoryOS. Capability-specific behavior belongs in `docs/specs/`; change-local reasoning belongs in the active increment.

## Change design

- Prefer the smallest complete production path over scaffolding for a hypothetical future path.
- Do not add interfaces, adapters, configuration modes, or deployment units without a current caller and owned lifecycle.
- Use a clean cutover: migrate every caller and remove obsolete code, configuration, comments, and tests in the same change.
- Keep capability boundaries from [ARCHITECTURE.md](../ARCHITECTURE.md). A new dependency edge requires an architecture decision and boundary-test update.
- `core` owns complete capability implementations, including Spring services, transactions, and persistence. `api` and `worker` are deployable composition roots; neither defines capability-owned business or persistence behavior.

## Java and Gradle

- Target JDK 25 and use the checked-in Gradle wrapper.
- Prefer immutable value types and constructor validation at public boundaries.
- Preserve exact security identifiers. Do not normalize issuer, subject, actor ID, email, or username unless a capability contract explicitly requires it.
- Prefer Spring `JdbcClient` for explicit SQL and Spring-managed transaction/error semantics. Use JPA when entity lifecycle or relationships provide concrete value; never create parallel domain/entity/repository/mapper layers by default.
- Centralize dependency versions in `gradle/libs.versions.toml`.

## API errors

- Expected capability failures use capability-prefixed stable codes through root-package typed `BusinessException` subclasses; HTTP types never enter core.
- REST failures use RFC 9457 `application/problem+json`. Clients branch on status or `code`, never on `title`, `detail`, or diagnostic exception messages.
- Spring Boot owns built-in MVC Problem Details. Custom advice handles only `BusinessException`; never add a global `Exception` catch.
- Browser redirect responses and Spring Security filter failures retain their surface-specific contracts.

## Published API contracts

- Spring MVC controllers, request/response types, and backend-owned OpenAPI annotations are the source of the browser API contract.
- HTTP request/response records live as one public top-level type per file under the owning API package's `contract` subpackage. Controllers own routing and thin mapping; they never accumulate nested transport records or introduce a second business model.
- `openapi.yml` is generated from the live application context and committed only as the deterministic Hey API input; never maintain paths or schemas independently in that file.
- Normal and production runtime configuration must keep springdoc API-doc endpoints disabled. Contract generation belongs to the full-context test boundary, not a temporary runtime profile or production startup task.
- Every API change refreshes `openapi.yml` and the committed Hey API client in the same change. Backend tests reject contract drift; frontend checks reject generated-client drift.

## Data and security

- Follow [production-first persistence](guidelines/persistence.md).
- Fail closed on missing configuration, invalid credentials, unknown bindings, and ownership conflicts.
- Never write passwords, tokens, private keys, raw authorization codes, or secret values to Git, docs, Linear, logs, or command history. Record only the managed secret location and retrieval method.
- Administrative or ownership-changing behavior requires authorization and audit design; do not expose an unauthenticated convenience endpoint or one-shot application mode.

## Documentation

- One canonical home per fact; use links rather than copies.
- `ARCHITECTURE.md` states what exists. `docs/vision.md` states intended outcomes. ADRs state accepted rationale. Guidelines state reusable policy. Specs state capability contracts. Increment documents state change-local design and progress.
- Update documents in the same change that makes them true.
- Do not mark an increment completed or move it from `active/` until verification passes and the pull request is merged.
