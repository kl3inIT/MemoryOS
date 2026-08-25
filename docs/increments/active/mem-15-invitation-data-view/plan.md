# MEM-15 implementation plan: organization invitation data view

## Contract and documentation

- [x] Expand the existing MEM-15 Linear scope instead of creating a duplicate pagination issue; mark MEM-21 duplicate.
- [x] Record offset-versus-cursor, resource naming, URL state, cache identity, and abstraction-threshold decisions before implementation.
- [x] Update the invitation capability spec and verification matrix with the bounded list contract and renamed product route.
- [x] Add MEM-15 to the active-increment map and roadmap without changing MEM-12 lifecycle ownership.
- [x] Extract published HTTP request/response records into capability-local `contract` packages and remove nested controller transport records.

## Backend list contract

- [x] Add typed status, email, sort, page, and size request values with explicit defaults and bounds.
- [x] Add a resource-specific `InvitationPage` response and keep plaintext secrets/digests absent.
- [x] Settle expired invitations, count the filtered result, and select one page in one transaction.
- [x] Map the sort enum to fixed SQL fragments with an invitation-ID tie-breaker; never concatenate client sort text.
- [x] Apply one Organization-scoped predicate to count and selection, with optional status and case-insensitive email matching.
- [x] Preserve active-owner authorization and the existing `401`/`403`/RFC 9457 behavior.
- [x] Test defaults, bounds, filter combinations, deterministic ties, totals, empty out-of-range pages, expiry, and Organization isolation.

## OpenAPI and generated client

- [x] Update the checked-in OpenAPI operation, query schemas, sort enum, and page response.
- [x] Regenerate the Hey API client and TanStack Query options; add no handwritten parallel client.
- [x] Verify generated query keys include every list parameter and generated artifacts remain stable.

## Frontend route and naming

- [x] Rename `/admin/people` to `/admin/invitations` through the router-aware file rename path and migrate every route/nav/test caller.
- [x] Replace `People` labels with `Invitations`; reserve `Members` for future durable membership administration.
- [x] Audit frontend routes, components, variables, tests, and navigation for similarly misleading identity/member/invitation names; rename the generic `AdminShell` to the resource-owned `SourcesPage`.
- [x] Remove obsolete route names and references without aliases or compatibility redirects.

## Frontend data view

- [x] Define Zod-validated typed route search defaults for status, email, sort, page, and size.
- [x] Make Apply, Clear, sort, page-size, and page navigation update the URL with the documented reset rules.
- [x] Query the generated paginated operation with the full list state and retain prior data during page transitions.
- [x] Use exact-pinned TanStack Table v9 in controlled manual server mode without extracting a generic DataTable.
- [x] Render semantic headers, sortable controls, contextual action labels, dates, statuses, result range, and labelled pagination.
- [x] Distinguish first-load, background-fetch, no invitations, no matching filters, page query failure, and row mutation failure.
- [x] Keep rotate/revoke pending state scoped to the affected row and invalidate the invitation-list base key after lifecycle mutations.
- [x] Verify narrow-screen table access and keyboard focus/order.

## Verification and delivery

- [x] Inspect every edited Java file with JetBrains warnings enabled and resolve all errors/unresolved warnings; retain only the intentional custom same-origin header weak warning.
- [x] Run focused backend repository/service/API tests and affected Gradle module compilation.
- [x] Run frontend typecheck, lint, format check, unit tests, generated-client drift checks, and route-generation checks.
- [x] Exercise filter, sort, pagination, refresh/deep link, empty/error, and rotate/revoke behavior in Chromium at desktop and narrow widths.
- [x] Run repository-wide `gradlew.bat clean check --no-daemon` under bounded local JVM settings and `pnpm check` after focused behavior passes.
- [x] Consolidate durable facts into conventions, invitation specs, and the verification matrix; keep the increment active until its guarded PR merges.
- [ ] Complete the guarded PR review, exact-head merge, merge-SHA CI, Linear closure, and increment move to `completed/`.
