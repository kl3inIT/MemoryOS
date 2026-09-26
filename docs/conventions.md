# Engineering conventions

These conventions apply across MemoryOS. Capability-specific behavior belongs in `docs/specs/`; change-local reasoning belongs in the active increment.

For logging, tracing, metric names and labels, business outcome instrumentation,
privacy, and staging defaults, follow the [observability conventions](guidelines/observability.md).

## Change design

- Prefer the smallest complete production path over scaffolding for a hypothetical future path.
- Do not add interfaces, adapters, configuration modes, or deployment units without a current caller and owned lifecycle.
- Use a clean cutover: migrate every caller and remove obsolete code, configuration, comments, and tests in the same change.
- Keep capability boundaries from [ARCHITECTURE.md](../ARCHITECTURE.md). A new dependency edge requires an architecture decision and boundary-test update.
- `core` owns complete capability implementations, including Spring services, transactions, and persistence. `api` and `worker` are deployable composition roots; neither defines capability-owned business or persistence behavior. They, and the `sources` bundle, use only published module APIs and never import a capability's `persistence` package; `ModulithArchitectureTest` and `CoreDependencyRulesTest` check `core`, `SourcesDependencyRulesTest` checks `sources`, and review holds the line for `api` and `worker`.

## Reference-based design and scope control

Production readiness is a required quality standard for the agreed scope from the start. It includes correct behavior, authorization, data integrity, bounded resource use, failure handling, observability, and appropriate verification and deployment practices. Scope control must not be used to defer necessary engineering or deliver a prototype in place of the requested production system.

Scope creep means adding unrequested behavior or mechanisms without establishing why the agreed scope needs them. Necessary production hardening is part of the scope, even when the user has not enumerated every failure case. Establish that need through concrete requirements, source analysis, credible failure scenarios, or measurements; do not wait for a production incident. Choose the simplest sufficient mechanism and explain its tradeoffs.

When the user chooses an existing product as the baseline, preserve its agreed behavior while meeting those production requirements in the target stack. An agent's proposal is not inherently better than the reference. The label "production-ready" does not establish that a particular worker, queue, journal, or abstraction is necessary; connect the mechanism to the requirement it satisfies. Future extensibility likewise needs a concrete benefit and cost assessment.

- Inspect the reference implementation and cite the relevant checkout revision and code path. Separate observed behavior, inferred rationale, proposed changes, accepted decisions, and verified implementation. State evidence gaps rather than inferring that the reference lacks a capability.
- Before recommending a material departure, record in the active design: **current requirement or demonstrated failure; reference behavior and strengths; proposed difference; concrete benefit; implementation/operational/UX costs; simpler baseline option; evidence needed to choose**. A short paragraph or comparison row is enough. If the benefit does not justify the cost, keep the baseline.
- Port behavior to the existing Java stack using native framework abstractions. Preserve recognizable concepts and explain naming mappings. Language fit, additional layers, or more durable storage do not prove better behavior, reliability, or AI quality. Add an adapter only for an identified contract gap.
- Evaluate public high-level APIs and shipped implementations before choosing a low-level SPI or creating an application equivalent. Test the actual candidate entry point: a successful custom-SPI probe does not prove a custom bridge is necessary. Prefer native configuration/hooks, then composition or implementation of a public framework interface; extend a base class only when designed for extension. Avoid internal classes, reflection and copied engines. Preserve the high-level caller when a narrow extension can close the gap. Matching class names alone do not establish matching persistence or authorization semantics.
- Evaluate framework reuse across the whole accepted end-to-end scope, not only the next implementation phase. An adapter can be valuable without replacing the database; name its consumers, lifecycle mapping and maintenance cost. Distinguish native data types/calculations from whether the selected runtime path supplies their inputs and invokes their policies. A missing connection calls for integration, not a duplicate accounting, budget or workflow engine. Probe receipts certify their stated boundary, not the entire architecture.
- Do not prebuild workers, queues, configuration snapshots/versioning, event journals, generic registries, or stronger retroactive authorization policies for hypothetical future use. Such choices need a current requirement; existing security and capability contracts still apply. Extensibility starts with usable framework APIs and actual consumers, not empty packages or promises that future workflows require no changes.
- Carry accepted decisions across turns. Do not reopen rejected additions or silently promote suggestions into scope. Proceed with routine implementation choices inside the accepted scope; this rule does not introduce a blanket approval gate.
- Keep validation claims proportional to evidence. Source review, diagrams, isolated probes, and production acceptance are different results. Do not present subjective fit percentages as measured confidence or completion.
- When a decision changes, reconcile the design, plan, references, and diagrams together. Keep one canonical statement of each decision. Move obsolete scratch drafts and QA captures/raw receipts to ignored scratch storage; retain useful source artifacts, concise evidence and real regression tests in the appropriate repository locations.

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
- Use explicit imports and short type names in handwritten Java, including tests. Do not inline fully qualified class names merely to avoid adding an import. Keep a qualified name only when needed to disambiguate colliding type names; remove unused imports.
- Use `lowerCamelCase` for Java methods and `UpperCamelCase` for class names. Keep external protocol/tool identifiers separate: for example, expose `web_search` through `@LlmTool(name = "web_search", ...)` while naming the Java method `webSearch`. Preserve the public tool identifier when refactoring Java names.
- Prefer immutable value types and constructor validation at public boundaries.
- An expected failure travels as a typed exception or enum code and is matched by type. Never put a code into an exception message and match the string later; the Chat turn failure (`TurnFailure`, `TurnFailureException` in `ai`) is the reference shape.
- Every main Java package declares JSpecify `@NullMarked` in its `package-info.java`, so types are non-null by default; mark each optional value with `org.jspecify.annotations.Nullable`. Spring Data enforces this on repository interfaces at runtime: a non-`@Nullable` argument rejects null and a non-`@Nullable` single-entity return throws when nothing is found, so return `Optional` or annotate the absence.
- Preserve exact security identifiers. Do not normalize issuer, subject, actor ID, email, or username unless a capability contract explicitly requires it.
- Prefer Spring `JdbcClient` for explicit SQL and Spring-managed transaction/error semantics. Application services never contain SQL or row mapping; concrete capability-owned `@Repository` classes own those mechanics and need no interface when only one internal implementation exists. Group repositories by consistency/use-case boundary, not table. Use JPA only when entity lifecycle or relationships provide concrete value; never create parallel domain/entity/repository/mapper layers by default. See [persistence policy](guidelines/persistence.md).
- Centralize dependency versions in `gradle/libs.versions.toml`.

## Testing

Every test must identify the observable contract and the regression it would catch. Use the smallest boundary that can actually detect that regression:

| Contract | Default test boundary |
| --- | --- |
| Business rule, validation, ordering, state transition | Plain unit test with real value objects; substitute only external collaborators |
| HTTP mapping, binding, validation, JSON or filter behavior | MVC slice with the relevant security configuration; calling a controller method directly is insufficient |
| SQL, migration, locking, transaction or constraint | Repository/application integration against the real database engine and Flyway migrations |
| Application composition, sessions, actor binding or background lifecycle | Full application context; real HTTP or worker execution when the transport/lifecycle is part of the contract |
| Browser interaction and recovery | Component test for local behavior; browser test for routing, cookies, network and browser-owned behavior |
| Deployed feature acceptance | Authenticated runtime smoke against the deployed release and real configured dependencies |

- Do not add tests for generated accessors, framework defaults, private methods, fixed implementation call sequences or source spelling. Architecture/dependency and generated-contract drift checks are exceptions because those boundaries are repository contracts.
- Before adding a test, check the owning verification matrix and existing cases. Extend the existing case when it covers the same behavior, boundary and failure mode. Similar scenarios at different boundaries are justified only when they catch different regressions.
- Test through the public entry point. Do not export a helper, add a constructor or overload, or keep dead code alive only so a test can reach it; assert on what the public path produces instead (for example the URL a fake socket recorded, or the list an adapter returns).
- Remove a test only with an explicit explanation of its missing value or a named replacement that preserves its assertions. Never remove concurrency, migration, security or negative-path coverage merely because a happy-path integration test passes.
- Reuse compatible Spring contexts before increasing parallelism. Different profiles, properties, dynamic property methods and mock customizers can create different cache keys. Do not add blanket `@DirtiesContext`; document the concrete lifecycle/state that requires eviction.
- Keep database/tenant fixtures isolated, avoid order dependencies and shared mutable state, bound worker counts and all asynchronous waits, and clean up resources through their actual lifecycle owner. A Testcontainers-managed static container does not need a second manual lifecycle.
- Required infrastructure failure must fail the required integration gate. Optional provider checks require a named owner, prerequisite and recorded acceptance gap. A mock or skipped test never establishes live integration.
- Retries must not turn a flaky required test into accepted evidence. Classify application errors, resource exhaustion and provider outages from diagnostics; do not weaken assertions or use quarantine as a fix.
- Coverage is a diagnostic, not an arbitrary percentage gate. Use targeted fault injection or mutation testing when it answers whether important assertions catch a plausible regression; add a tool only when its value justifies the cost.

The boundary, context-reuse, parallelism and mutation principles are based on Philip Riecks' [Spring I/O 2026 talk](https://www.youtube.com/watch?v=DPi2Borv96I): [19:15](https://www.youtube.com/watch?v=DPi2Borv96I&t=1155s), [21:20](https://www.youtube.com/watch?v=DPi2Borv96I&t=1280s), [37:30](https://www.youtube.com/watch?v=DPi2Borv96I&t=2250s), [45:21](https://www.youtube.com/watch?v=DPi2Borv96I&t=2721s), and [47:10](https://www.youtube.com/watch?v=DPi2Borv96I&t=2830s). The concrete MemoryOS gate and ownership rules above are project policy. See [testing mechanics](guidelines/testing.md) for commands and evidence boundaries.

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
- Spring Boot owns built-in MVC Problem Details. Capability/provider failures and request validation have narrow typed advice; never add a global `Exception` catch. Validation entries retain safe fallback `message` and expose stable `code` plus allowlisted numeric `params` (`min`/`max`), never rejected values. UI consumers translate codes at render time rather than displaying arbitrary backend/provider text.
- Browser redirect responses and Spring Security filter failures retain their surface-specific contracts.

## Published API contracts

- Spring MVC controllers, request/response types, and backend-owned OpenAPI annotations are the source of the browser API contract.
- HTTP request/response records live as one public top-level type per file under the owning API package's `contract` subpackage. Controllers own routing and thin mapping; they never accumulate nested transport records or introduce a second business model.
- Controllers take the authenticated actor as `@CurrentActor IdentityContext`, which combines `@AuthenticationPrincipal` with a hidden OpenAPI parameter.
- An error response is declared as `@ApiResponse(responseCode = "404", description = "…")`; a springdoc operation customizer in `OpenApiConfiguration` gives every declared 4xx/5xx response the `application/problem+json` `ApiProblem` body. `content = @Content` marks a bodiless response (the Spring Security 401, a 416 range refusal). Every member a handler adds to a problem is declared, optional and typed, on the closed `ApiProblem` schema, and the web reads it through `problemOf`.
- Nullable record components on API-owned records declare `@Schema(types = {"<type>", "null"})` inline. The `OpenApiConfiguration` patch list only covers what cannot: core-owned schemas, and references and enums, whose null needs a `oneOf` branch.
- Controllers do not write `Cache-Control: no-store` or `X-Content-Type-Options: nosniff`: Spring Security's default header writer sends `Cache-Control: no-cache, no-store, max-age=0, must-revalidate`, `Pragma: no-cache`, `Expires: 0` and `nosniff` on every response, and leaves a `Cache-Control` a controller set alone. Set `Cache-Control` only as a commented, deliberate override (owner-private immutable image bytes, a public metadata document, `no-transform` on an event stream).
- `openapi.yml` is generated from the live application context and committed only as the deterministic Hey API input; never maintain paths or schemas independently in that file.
- Normal and production runtime configuration must keep springdoc API-doc endpoints disabled. Contract generation belongs to the full-context test boundary, not a temporary runtime profile or production startup task.
- Every API change refreshes `openapi.yml` and the committed Hey API client in the same change. Backend tests reject contract drift; frontend checks reject generated-client drift.
- The web Hey API client owns two defaults in `web/src/lib/api.ts`: every SDK call rejects with `ApiError` on a non-2xx response (`throwOnError` is set in the generator config and in `client.setConfig`), and a request interceptor adds `X-MemoryOS-CSRF: 1` to every request other than GET, HEAD and OPTIONS. Call sites pass neither; a caller that treats a status as an outcome catches the `ApiError` (for example `isNotFound`). Requests outside the SDK, such as the `/logout` fetch, still send `sameOriginMutationHeaders` themselves.

## Frontend feature folders

- A folder under `web/src/features` is named after the backend capability whose screens it holds ([ADR 0015](decisions/0015-capability-module-map.md#step-5-web)): `chat`, `library`, `sources` (connector), `models` (the `ai` admin UI), `voice`, `meetings`, and so on. A page that serves another capability's data still lives with the capability that owns it.
- A feature with more than about 25 files gets one level of sub-folders by sub-feature (for example `chat/thread`, `chat/composer`, `sources/google-drive`); the feature root keeps its entry pages and the modules its sub-folders share. Smaller features stay flat.
- Files keep the feature prefix they are known by; a moved file drops only a prefix that names the capability it no longer belongs to. Tests sit next to their subject. Imports across folders use `@/features/...`; `./` stays for siblings.
- A feature imports another feature only in the direction the backend modules depend on each other ([ADR 0015](decisions/0015-capability-module-map.md#step-4--web-feature-dependencies)): `chat` may import `library`, `voice`, `models`, `mcp` and `identity`, but `meetings`, `library`, `voice`, `search`, `mcp` and `identity` never import `chat` or its admin features (`agents`, `document-sets`). `preview` (the shared viewer kit) and `theme` have no backend module and any feature may import them. A part with no capability of its own goes to `components/composites` (UI) or `lib` (helpers); a feature that shows another capability's part takes it as a prop or slot, and the route or the app shell composes the two.

## Frontend shell, routes and runtime

- `AppShell` renders once, in the `_authenticated` layout route. A page never renders the shell itself; it puts its title and actions into the shell through `AppShellHeader`.
- Administration pages are declared once in `web/src/components/app-shell/admin-pages.ts`, which drives the sidebar, page titles, access and the administration entry. A new administration page is a row there plus its route.
- Identity is ensured in the `_authenticated` `beforeLoad`; the session boundary still owns 401/403 and every failure screen. A route's `loader` preloads the queries its page reads. An administration loader warms data only for a person the page admits (`mayWarmAdminPage`), so a denied page sends no request.
- Search parameters are validated by a zod schema in the route's `validateSearch` and read through the typed `useSearch`. Filter and paging state a person expects to survive reload or a shared link lives in the URL (Search, Audit log and Library do this); a search box writes the settled value, not every keystroke.
- A request driven by typed input is debounced with `useDebouncedValue` (`web/src/hooks`), so the request waits, not only the render.
- Poll only while the server reports running work, and stop (`refetchInterval` returns `false`) when nothing runs. A Source page that must notice work started elsewhere falls back to `IDLE_SOURCE_POLL_MS` (30 s) while idle.
- Files are previewed through the shared kit in `web/src/features/preview` (`FilePreview`); a feature does not build its own viewer or import another feature's.
- React root `onCaughtError` and `onUncaughtError` (`web/src/main.tsx`) report to Sentry, so an error a route error component catches is still reported. Do not swallow a failure in a `catch` that neither shows it nor rethrows it.
- The deployment CSP is `style-src 'self'`: inline `<style>` elements and style attributes parsed from markup are dropped. Styles live in a stylesheet or in token classes.
- The initial load (the entry script, its modulepreloads and linked stylesheets) has a gzip budget that `pnpm build` enforces (`web/scripts/assert-bundle-budget.mjs`). The budget only ratchets down; do not raise it to absorb an eager import. Viewers, charts, grammars and other heavy code load through dynamic `import()`. Vietnamese ships in the entry bundle and the English catalog loads when a person chooses English ([localization](specs/localization.md)).

## Component and library reuse

- Before building or extending a component, always check the existing project components and the relevant library's shipped components, hooks, adapters, and runtime behavior. Reuse them whenever they meet the actual requirement; this applies to behavior and state, not just visual markup.
- Prefer a suitable ready-made component, then its documented props, slots and composition, then lower-level primitives. Write custom logic only for a concrete gap; record the gap and the reused entry point briefly in the active increment. Do not recreate selection, upload state, preview, keyboard/focus behavior, or cleanup that the library already provides for the selected runtime.
- Keep registry-installed components close to their upstream implementation. Adapt them for the existing design tokens, language, accessibility and security contracts; preserve attribution and document meaningful deviations. Check the installed version and current documentation before integrating.
- A necessary, maintained dependency is acceptable when it enables real reuse and its compatibility, license, bundle and maintenance costs are reasonable. Do not replace library logic merely to avoid adding a package. Pin the dependency and verify its actual consumer; avoid unrelated upgrades.
- Reuse does not transfer application authority to the library. MemoryOS still owns backend authorization, persistence and business lifecycle. A component dependency does not by itself justify a new global store or duplication of state already owned by the runtime or query cache.

### Page headers

- Every page's title block is one `PageHeader` with an icon, the title, a one-line description and optional actions on the right. No page hand-rolls a heading row, adds an eyebrow above the title, or overrides the icon size.

### Detail pages

- A page about one resource is built in this order: `DetailHeader` (the breadcrumb back to the list, then the resource's own name, status and primary actions), an optional `StatStrip`, the content sections, and `DangerZone` last. A page never hand-rolls its own way back: no Cancel button standing in for a link, no second breadcrumb markup.
- The destructive action belongs to `DangerZone` at the end of the page, where reading what it destroys comes before pressing it. An action menu in the header carries the reversible actions. A compact panel embedded in another surface, such as a Project inside Chat, keeps its actions in its menu; the rule is for pages.
- Sections inside the page use `SectionHeader`; settings-shaped rows use `SettingRow`/`SettingRows`. Loading uses `BrandLoader`, and an absent, emptied or unreadable resource uses `EmptyState` with a way forward.
- Page width comes from `--page-width-narrow|standard|wide` through `SettingsLayout`. A feature does not hardcode a pixel width, and does not ship a CSS file to lay itself out or to redefine a semantic token for one screen.

### Composites

- `web/src/components/composites` holds product patterns built only from registry primitives and tokens, shared by more than one feature: `SectionHeader`, `SettingRow`/`SettingRows`, `StatStrip`/`StatTile`/`StatToggleTile`, `FilterChips`, `PersonAvatar`, `CountSeparator`, `SortableList` and the `hoverReveal` class. Every figure summary (costs, usage, counts, generated metrics) uses `StatStrip`; a stat that must stay in a table cell uses its `statLabelClass`/`statValueClass`. They carry no data fetching or authorization.
- A stat tile reads label, then figure, then at most one supporting line. Its icon repeats what the label says and takes a chart colour, or a status colour where the figure names a status; the figure itself stays in neutral ink until a real condition (an exceeded budget, unpriced usage) gives it a status tone. A comparison against the previous period is a `trend` line whose arrow, not its colour, carries the direction. While a figure loads, the tile shows the placeholder rather than a dash, because a dash reads as a recorded zero.
- The strip is the only frame: never wrap tiles in a second bordered container, and never give each tile its own border inside one.
- Hover-revealed actions stay reachable: they appear on `focus-within` and are always visible on devices without hover.

## shadcn/ui registry

- `web/components.json` registers shadcn/ui (`radix-nova` style, Lucide icons, `@/components/ui` alias). Every shared browser control comes from that registry; feature code never hand-rolls a control the registry ships.
- A missing control is installed, never reimplemented: `pnpm exec shadcn add <component>` inside `web/`, then adapt the copied file to the existing design tokens, `ui()` translation contract, and the [frontend interaction contracts](#frontend-interaction-contracts). Commit the installed file with the change that needs it.
- Do not write a raw `<input type="checkbox">`, `<input type="radio">`, `<table>`, `<details>`/`<summary>` toggle, tab strip, or tooltip in feature code. Use `Checkbox`, `RadioGroup`, `Switch`, `Table`, `Collapsible`/`Accordion`, `Tabs`, and `Tooltip` from `@/components/ui`.
- `Button`, `IconButton`, `TextButton`, `StatusBadge`, `Empty`, and `Separator` remain the MemoryOS-owned wrappers over the registry primitives; keep their `tone`/`prominence`/`size` vocabulary rather than styling a registry component inline.
- Keep an installed component recognizable: no renaming of exported parts, no removal of its accessibility wiring. Record any deliberate deviation in the active increment.

## Frontend interaction contracts

- Product code chooses action `tone` (`default` or `danger`), `prominence` (`primary`, `secondary`, `tertiary`, or `internal`), and control `size` (`sm`, `md`, or `lg`). Shared UI components own rest, hover, active, focus-visible, disabled, and pending presentation in both themes.
- Container actions use `Button`; quiet foreground-only actions use `TextButton`; icon-only actions use `IconButton` with an accessible name. Do not recreate these distinctions with feature-local color, border, background, opacity, height, or focus classes.
- `Input`, `Select`, `Button`, and `IconButton` share the 32px, 40px, and 44px size scale. Adjacent controls use the same named size rather than handwritten heights.
- Native buttons default to `type="button"`. A caller must request `submit` or `reset` explicitly. Pending actions retain their accessible name, expose busy state, and prevent repeated activation.
- Busy and disabled presentation belongs to work the person started. A polled view refetches on its own while work runs, so a control bound to the query's fetching flag blinks busy and refuses clicks between polls; refresh controls take their pending state from `useManualRefresh`, and paging controls disable on `isPlaceholderData` (the page being replaced), never on a background refetch.
- Disabled presentation uses semantic content, surface, and border tokens; opacity alone is not a disabled state. Keyboard focus must remain visible through the shared focus-ring role.
- Destructive product actions use `ConfirmDialog`: callers provide visible entity-specific title and impact copy, Cancel receives initial focus, and async confirmation prevents duplicate activation, stays open while pending or failed, announces safe action-local feedback, and closes only after success.
- Feature code may own layout and selected-resource treatment, but it must not introduce a second standard interaction matrix or raw Tailwind palette classes for product actions.

## Colour and design tokens

- Components and feature CSS use semantic tokens only: no hex, `rgb()`/`oklch()` or Tailwind palette classes (`bg-green-500`, `text-white`). A missing role is added to `web/src/styles/tokens.css` with light and dark values and mapped in `theme.css`. Brand artwork is the only exception.
- Status colours carry state only and come with an icon or label; charts use `chart-1` … `chart-8` in fixed order with `chart-neutral` for "Other". Roles, values and the chart validation rules are in the [design token guideline](guidelines/design-tokens.md).

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
