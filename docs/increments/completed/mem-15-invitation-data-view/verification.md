# MEM-15 verification: organization invitation data view

## Contract matrix

| Contract | Verification boundary | Required evidence |
| --- | --- | --- |
| Default list is bounded and newest-first | API integration and repository test | Default page/size/sort, bounded row count, response totals |
| Status and email filters are server-owned | Repository and API integration tests | Matching count/items; browser receives no full unfiltered history |
| Sort input is allowlisted and deterministic | Request validation and repository test | Unknown sort rejected; equal primary values ordered by ID tie-breaker |
| Page and size bounds are explicit | API integration test | Negative page and invalid size return RFC 9457 `400`; valid out-of-range page is empty |
| Expiry, count, and page share one lifecycle view | Service/repository transaction test | Expired row leaves pending filter and totals before selection |
| Organization owner scope remains authoritative | API integration test | Owner succeeds; member receives `403`; anonymous receives `401`; no client Organization selector |
| Listed rows contain no secret material | Contract/API test | Page items expose lifecycle metadata only |
| URL is canonical list state | Frontend unit and browser test | Refresh, back/forward, and copied URL reconstruct filters/sort/page/size |
| Query cache is list-state-specific | Frontend unit test | Every list input contributes to the generated query key |
| Lifecycle mutations cannot leave stale pages | Frontend unit and browser test | Create/rotate/revoke invalidate the list base key and refresh totals/membership |
| Table semantics and keyboard behavior are preserved | DOM assertions and browser test | Scoped headers, sort state, contextual actions, pagination label/current state, focusability |
| Narrow-screen access remains usable | Browser test | All columns/actions reachable without clipped controls or duplicate content |
| Naming matches domain ownership | Route generation, source audit, browser test | `/admin/invitations` and `Invitations`; no stale `/admin/people`/People resource labels |

## Focused scenarios

1. Seed lifecycle rows covering every stored status, repeated timestamps, mixed email case, and more than one page.
2. Load defaults and prove newest-first bounded results plus correct totals.
3. Filter each status and a case-insensitive email fragment; combine filters and prove count/items use the same predicate.
4. Exercise every allowed sort and prove deterministic ordering for ties.
5. Reject malformed query input through the standard problem contract; load a valid page beyond the final page as an empty result.
6. Cross an expiry boundary and prove pending count and rows settle consistently.
7. Exercise owner/member/anonymous access without introducing client-supplied Organization scope.
8. Load a deep URL, change each control, navigate browser history, and refresh.
9. Transition between pages while prior rows remain visible with a subordinate fetching indicator.
10. Revoke or rotate a row under filtered/sorted pagination and prove rows plus totals refetch from the server.
11. Use keyboard-only sort, pagination, and row actions at desktop and narrow viewport widths.

## Evidence log

Evidence is appended here only after the corresponding command or runtime scenario completes. No planned check is recorded as passed before observation.

## Observed implementation evidence

- `DefaultInvitationServiceTest` passes its full focused class, including server filtering/sorting/pagination, out-of-range totals, lifecycle expiry, and the database's UUID tie-breaker order.
- `SessionSecurityIntegrationTest.filtersSortsAndPaginatesInvitationHistoryOverHttp` passes against the real API composition with owner session, three lifecycle rows, combined status/email filter, email sort, two pages, and RFC 9457 rejection of size `101`.
- `OpenApiContractTest` passes after moving all published Invitation/Identity/application-session request and response records into capability-local `contract` packages. `openapi.yml` and the Hey API client regenerate deterministically.
- JetBrains inspections with warnings enabled report no problems in every changed main/test Java file except the existing intentional weak warning for the custom `X-MemoryOS-CSRF` header. Focused `core`/`api` compilation passes.
- Repository-wide `gradlew.bat clean check --no-daemon` passes with one worker and bounded local JVM settings (`Gradle -Xmx128m`; forked JVM `-Xmx160m`) after the workstation had insufficient native memory under its ordinary local daemon settings.
- `pnpm check` passes generated-client drift, image pin, zero-warning Oxlint, Oxfmt, TypeScript, 5 unit-test files/14 tests, route generation, and the production Vite build.
- Invitation-specific unit tests pass Zod search defaults/normalization/fallbacks/API projection and TanStack Table v9 semantics plus controlled sorting/page/page-size callbacks.
- Full `pnpm test:e2e` passes 11/11 Chromium contracts. The invitation cases prove create/copy failure recovery/rotate/revoke and deep URL restoration, server filtering/sorting/pagination, page reset, page-size update, and reload persistence.
- Browser-tool inspection at `1440 × 900` rendered 25 server records with explicit status/lifecycle columns, contextual row actions, filter controls, result count, and pagination. Inspection at `390 × 844` kept body width equal to viewport width, stacked the filter controls, exposed the table through its horizontal container, and retained the mobile application header.
- The naming audit removed `/admin/people`, the `People` navigation/page discriminator, and the generic `AdminShell`; the resource routes are `/admin/invitations` and `/admin`, backed by `OrganizationInvitationsPage` and `SourcesPage`.

- PR #29 reviewed head `934dcf6fd2310c9e9d14e69edf4dd8a6f5281aae` passed latest-head CI `32877425377` and merged as `d9cfa9954fd99441a90bf5603afcdc3fb0fd78a4`; exact main CI `32877956194` passed.
- The original review evidence collection initially acted on one of four inline findings because displayed paginated output was truncated. Corrective PR #30 added OpenAPI integer bounds, hoisted the date formatter, and moved TanStack body rendering to declared column cells plus `FlexRender`. Its reviewed head `306d273c032543d077b88be6876de84e547e970f` passed CI `32879179639`; CodeRabbit explicitly rate-limited the one requested pass, while the complete fallback collection found no comments or threads.
- PR #30 merged as `9b083ad9e2c00d8938d9b7fd98aadc2f19dd9142`; exact main CI `32879513229` passed `check`, `frontend`, and `frontend-image`, including 11/11 Chromium contracts.
- Staging API and web images are labeled with exact final merge SHA `9b083ad9e2c00d8938d9b7fd98aadc2f19dd9142`, both containers are healthy, public `/actuator/health` returns `UP`, and the anonymous bounded invitation request retains its expected `401` authorization boundary.

The increment is delivered and moves to `completed/`; Linear closes only after this lifecycle record merges.
