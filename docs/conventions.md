# Engineering conventions

These conventions apply across MemoryOS. Capability-specific behavior belongs in `docs/specs/`; change-local reasoning belongs in the active increment.

## Change design

- Prefer the smallest complete production path over scaffolding for a hypothetical future path.
- Do not add interfaces, adapters, configuration modes, or deployment units without a current caller and owned lifecycle.
- Use a clean cutover: migrate every caller and remove obsolete code, configuration, comments, and tests in the same change.
- Keep capability boundaries from [ARCHITECTURE.md](../ARCHITECTURE.md). A new dependency edge requires an architecture decision and boundary-test update.
- `core` owns complete capability implementations, including Spring services, transactions, and persistence. `api` and `worker` are deployable composition roots; neither defines capability-owned business or persistence behavior.

## Boundary discovery

Capability and bounded-context boundaries are discovered from domain evidence, not inferred from folders, entities, frameworks, or desired future services. Every new capability or material boundary change follows this sequence:

```text
Domain Story
→ Visual Glossary
→ Events, Commands, Aggregates, and Read Models
→ Data and Invariant Owner
→ Context Map and Communication Pattern
→ Package-Level Application Module
→ Boundary Verification
→ Gradle or Deployment Split Only with Evidence
```

1. **Domain Story:** record actors, work objects, ordered actions, outcome, and important failure/recovery paths in the active increment.
2. **Visual Glossary:** define one ubiquitous term per concept, its relationships/cardinality, and where the same real-world object has different meanings. Do not proceed while terms such as Actor, Member, Recipient, Connector, Pair, Item, and Document are overloaded.
3. **Events, Commands, Aggregates, and Read Models:** identify intent, observed business facts, consistency boundaries, and projections. Distinguish synchronous invariants from asynchronous reactions.
4. **Data and Invariant Owner:** assign one capability as source of truth for each table, lifecycle, and invariant. No two capabilities write the same owned persistence or import each other's persistence package.
5. **Context Map:** state provided/required APIs, dependency direction, synchronous calls, events, failure/consistency semantics, actors, and non-functional requirements. Reject unexplained cycles.
6. **Package Module First:** implement the smallest complete vertical slice as a closed Spring Modulith package module with a narrow public root and internal application/persistence/provider packages. A bounded context may contain several application modules; an application module is not automatically a microservice or Gradle artifact.
7. **Verify:** enforce module completeness, allowed dependencies, internal/persistence ownership, observable contracts, and generated module documentation in CI.
8. **Physical Split with Evidence:** add a Gradle module or deployment unit only for a concrete classpath/dependency conflict, independently selected runtime, release/team ownership, scaling/failure boundary, or demonstrated build bottleneck. Record the accepted tradeoff in an ADR after implementation starts.

Keep parts together when they share one language, invariant owner, transaction/lifecycle, and reason to change. Separate them when language, source of truth, invariants, actors, lifecycle, failures, non-functional requirements, or change ownership diverge and an explicit one-way contract exists. When evidence is incomplete, prefer fewer modules and preserve extraction through public APIs rather than predeclaring placeholders.

## Java and Gradle

- Target JDK 25 and use the checked-in Gradle wrapper.
- Prefer immutable value types and constructor validation at public boundaries.
- Preserve exact security identifiers. Do not normalize issuer, subject, actor ID, email, or username unless a capability contract explicitly requires it.
- Prefer Spring `JdbcClient` for explicit SQL and Spring-managed transaction/error semantics. Application services never contain SQL or row mapping; concrete capability-owned `@Repository` classes own those mechanics and need no interface when only one internal implementation exists. Group repositories by consistency/use-case boundary, not table. Use JPA only when entity lifecycle or relationships provide concrete value; never create parallel domain/entity/repository/mapper layers by default. See [persistence policy](guidelines/persistence.md).
- Centralize dependency versions in `gradle/libs.versions.toml`.

## API discovery and product boundaries

Published APIs are derived after domain boundaries, not from tables, repositories, entity fields, controller convenience, or provider SDKs. Every new API product or material contract change follows:

```text
Domain Story and Consumer
→ Visual Glossary
→ Commands, Events, and Read Models
→ Context Map and API Product Canvas
→ Synchronous/Asynchronous Surface
→ HTTP/Event Contract
→ Generated Specification
→ Consumer and Runtime Verification
```

1. Name the consumer, goal, authority, frequency, latency/consistency need, and failure/recovery path before choosing REST, event, browser navigation, or background operation.
2. Use consumer-facing ubiquitous language. Do not expose persistence joins, framework types, provider SDK objects, or internal orchestration names as resources merely because they exist in code.
3. Separate commands from read models. A read model may compose projections from several capabilities without moving source-of-truth ownership into the API layer.
4. Use resource creation/list/detail where a durable resource is the product concept. Use an explicit POST command when behavior is a domain transition and the resource remains durable; do not label revoke/disable as DELETE if history remains addressable under the same identity.
5. Keep responses minimal and consumer-owned. Expose internal identifiers, lifecycle facts, authority projections, and diagnostic metadata only when a current consumer requires them.
6. Model asynchronous work as `202 Accepted` plus a durable operation/status resource and polling/recovery contract. Do not return synchronous success or Problem Details for work that has not completed.
7. Define idempotency, retries, concurrency winner, ordering, pagination, bounded filters/sorts, and deletion/retention semantics before publishing the operation.
8. Distinguish browser navigation/capability-link routes from JSON API products. OpenAPI includes only programmable API contracts; native OAuth, logout, callback, and invitation-link navigation retain browser/security contracts.
9. Give OpenAPI operations stable consumer-facing `operationId`, product tags, summaries, security requirements, and explicit success/failure responses. Generated controller-class tags are not accepted as API taxonomy.
10. Generate OpenAPI/AsyncAPI from the implemented contract, verify it against the API Product Canvas and glossary, regenerate clients, and exercise one real consumer flow.

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

## Frontend interaction contracts

- Product code chooses action `tone` (`default` or `danger`), `prominence` (`primary`, `secondary`, `tertiary`, or `internal`), and control `size` (`sm`, `md`, or `lg`). Shared UI components own rest, hover, active, focus-visible, disabled, and pending presentation in both themes.
- Container actions use `Button`; quiet foreground-only actions use `TextButton`; icon-only actions use `IconButton` with an accessible name. Do not recreate these distinctions with feature-local color, border, background, opacity, height, or focus classes.
- `Input`, `Select`, `Button`, and `IconButton` share the 32px, 40px, and 44px size scale. Adjacent controls use the same named size rather than handwritten heights.
- Native buttons default to `type="button"`. A caller must request `submit` or `reset` explicitly. Pending actions retain their accessible name, expose busy state, and prevent repeated activation.
- Disabled presentation uses semantic content, surface, and border tokens; opacity alone is not a disabled state. Keyboard focus must remain visible through the shared focus-ring role.
- Destructive product actions use `ConfirmDialog`: callers provide visible entity-specific title and impact copy, Cancel receives initial focus, and async confirmation prevents duplicate activation, stays open while pending or failed, announces safe action-local feedback, and closes only after success.
- Feature code may own layout and selected-resource treatment, but it must not introduce a second standard interaction matrix or raw Tailwind palette classes for product actions.

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

## Observability

Follow the [logging and telemetry policy](guidelines/observability.md) for event fields,
levels, sensitive content, durable correlation, sampling and metric cardinality.
