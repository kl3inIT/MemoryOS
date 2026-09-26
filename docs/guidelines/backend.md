# Backend guide

For work in the Gradle modules `core`, `api`, `worker` and `sources`. This page is a checklist that points to the canonical rule; it does not restate it. When a rule here and its linked source disagree, the linked source wins.

## Where things live

| Module | Holds | Never holds |
| --- | --- | --- |
| `core` | The Spring Modulith capabilities under `io.memoryos.<capability>` and the `shared` kernel; services, transactions, persistence and migrations (`core/src/main/resources/db/migration`) | HTTP types, a dependency on `sources`, `api` or `worker` |
| `sources` | Provider adapters under `io.memoryos.connector.adapter.<provider>` and content extraction under `io.memoryos.ingestion.extraction` ([ADR 0016](../decisions/0016-integration-bundle-named-sources.md)) | Anything but public `core` APIs; a `persistence` import |
| `api` | Controllers, security, the `contract/` records of each API package, OpenAPI configuration | Business rules, SQL, a `persistence` import, a core type returned as an HTTP body |
| `worker` | Redis Streams and db-scheduler composition of durable background work | Capability business or persistence behavior |

The capability map and allowed dependency edges are in [ARCHITECTURE.md](../../ARCHITECTURE.md#code-and-capability-boundaries); the layout inside a module is in [ADR 0015](../decisions/0015-capability-module-map.md#layout-inside-a-module).

## Rules to check before you edit

- **Module boundary.** A module is `CLOSED`; its `package-info.java` lists `allowedDependencies`. The root package is the published API and feature subpackages are internal. Use `@NamedInterface` only for a deliberate extra surface. A new dependency edge needs an architecture decision ([conventions](../conventions.md#change-design)).
- **No speculative structure.** No empty packages, single-implementation interfaces, temporary runtime modes or one-shot endpoints ([conventions](../conventions.md#change-design), [ADR 0002](../decisions/0002-no-speculative-operational-surfaces.md)).
- **Persistence.** SQL, row mapping, locks, claims and bulk writes live in the capability's `persistence` package; application services own authorization, validation, orchestration and the transaction boundary ([persistence policy](persistence.md)). Migrations are append-only once applied.
- **Nullness.** Every main package declares `@NullMarked`; mark optional values `@Nullable` and return `Optional` from a Spring Data lookup that can miss ([conventions](../conventions.md#java-and-gradle)).
- **Imports.** Explicit imports, no inline fully qualified names except to resolve a name collision ([conventions](../conventions.md#java-and-gradle)).
- **Failures.** Expected failures are typed `BusinessException` subclasses with stable codes, matched by type; REST failures are `ApiProblem` (RFC 9457) and every extension member is declared on it ([API errors](../conventions.md#api-errors), [published API contracts](../conventions.md#published-api-contracts)).
- **HTTP contract.** Request and response records live one per file under the API package's `contract/`; the authenticated actor is `@CurrentActor IdentityContext`; do not hand-write `Cache-Control: no-store` or `nosniff` ([published API contracts](../conventions.md#published-api-contracts)).
- **Contract regeneration.** Every API change refreshes `openapi.yml` and the committed Hey API client in the same change: run `:api:test --tests '*OpenApiContractTest*'` with `MEMORYOS_OPENAPI_WRITE=true`, then `pnpm --dir web generate:api`.
- **Logging.** Fluent SLF4J with `event` = `<capability>.<subject>.<outcome>`, `error_type` and `error_code`; no payloads or provider messages; inside a worker delivery do not repeat MDC fields ([observability](observability.md)).
- **Security identifiers.** Preserve issuer, subject and actor IDs exactly; fail closed on missing configuration or unknown bindings ([conventions](../conventions.md#data-and-security)).
- **Reuse the framework.** Check Spring, Spring Modulith, Spring Data and Embabel before writing an equivalent ([reference-based design](../conventions.md#reference-based-design-and-scope-control)).

## Tests

- Pick the narrowest boundary that catches the regression ([testing policy](../conventions.md#testing)); commands, fixtures and gates are in the [testing guideline](testing.md).
- Boundary tests that must stay green: `ModulithArchitectureTest` and `CoreDependencyRulesTest` (`core`), `SourcesDependencyRulesTest` (`sources`), `OpenApiContractTest` (`api`).
- Update the capability matrix under `docs/tests/` when a contract changes.

## Common mistakes

- Importing `io.memoryos.<capability>.persistence` from `api`, `worker`, `sources` or another capability.
- Returning a core record as an HTTP body instead of mapping it to a `contract/` record.
- Encoding a failure code in an exception message and matching the string.
- Adding a `Default*` implementation behind a single-implementation interface, or a constructor only a test calls.
- Editing an applied migration instead of adding the next version.
- Changing a controller without refreshing `openapi.yml` and the web client.
- Logging an exception message, request body or provider payload.
