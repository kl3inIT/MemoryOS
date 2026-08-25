# MEM-15 design: organization invitation data view

## Outcome

An authenticated Organization owner can inspect invitation lifecycle history through a bounded, filterable, sortable, URL-addressable data view. The server owns filtering, ordering, counting, and pagination; the browser owns presentation and current-view navigation without loading or slicing the full history.

The misleading `People` route and label are removed. The current resource is invitation administration, so its public application route is `/admin/invitations`, its page is `OrganizationInvitationsPage`, and its list types use `Invitation` vocabulary. `Members` remains reserved for a future Organization membership directory; `Users` remains identity-provider or platform-account vocabulary.

This increment establishes one resource-specific convention. It does not create a generic data-table framework. Shared table, filter-bar, or resource-registry abstractions require a second real data view that proves common behavior.

## Reference boundary

Refine is a contract reference for resource/action vocabulary, server-owned list operations, URL-addressable list state, cache identity, and access-control boundaries. The local Onyx checkout is a production reference for explicit administration resource naming, separate users/groups/service-account surfaces, Opal's TanStack Table v8 conventions, and browser/visual verification. MemoryOS keeps TanStack Router, TanStack Query, the generated Hey API client, semantic tokens, and Spring-owned authorization; it uses exact-pinned TanStack Table v9 for the invitation row/column model and Zod 4 for the untrusted URL boundary.

Neither reference authorizes generic CRUD APIs, frontend-owned permissions, provider pyramids, or a second API abstraction.

## List contract

`GET /api/invitations` accepts an explicit query:

- optional existing invitation `status`;
- optional trimmed case-insensitive `email` match;
- `sort`, defaulting to newest first and restricted to an enum allowlist;
- zero-based `page`, default `0`;
- bounded `size`, default `20`, maximum `100`.

The initial sort vocabulary is:

```text
CREATED_AT_DESC
CREATED_AT_ASC
EMAIL_ASC
EMAIL_DESC
```

The response is resource-specific:

```text
InvitationPage
- items: Invitation[]
- page: integer
- size: integer
- totalItems: integer
- totalPages: integer
```

Negative pages, sizes outside the accepted range, and unknown status/sort values fail through the existing RFC 9457 validation contract. An out-of-range nonnegative page returns an empty `items` array with correct totals rather than pretending the request is malformed.

## HTTP transport contract placement

Published request/response records live as one public top-level type per file under the owning API package's `contract` subpackage. Controllers retain routing, authorization context, validation translation, and thin domain-to-transport mapping; they do not accumulate nested transport records. `contract` names the records' OpenAPI/public-client role more precisely than a global technical `dto` bucket, while avoiding speculative request/response subtrees.

The same convention is applied consistently to current Invitation, Identity, and application-session HTTP records in this increment; core business records remain in their capability packages and are not duplicated into a second domain model.

## Pagination decision

Invitation administration uses bounded offset pagination. Operators benefit from total count, numbered pages, filtering, and direct links to a page; invitation lifecycle volume is bounded and is not a high-churn append feed.

Every sort maps to an application-owned SQL order and appends invitation ID as a deterministic tie-breaker. Each response is deterministic for its transaction snapshot. The UI does not promise a stable snapshot across later page requests when invitations are concurrently created or settled.

Cursor pagination is reserved for high-churn append streams such as future audit or activity views, where continuation stability matters more than numbered pages and exact totals.

## Persistence and transaction behavior

Listing remains Organization-scoped through durable owner authority; clients never submit an Organization ID. The existing expiry settlement runs before count and page selection in the same transaction so `status=PENDING`, `totalItems`, and returned rows observe one lifecycle state.

The repository applies one shared predicate to count and selection:

- current Organization;
- optional status;
- optional normalized email match.

Raw client sort text is never concatenated into SQL. A Java enum maps to fixed SQL fragments, followed by `id` as the stable tie-breaker. Selection applies `LIMIT` and `OFFSET`; count omits ordering and pagination.

No index is added speculatively. Existing access paths and the focused query plan are reviewed after the final predicate exists; a new index requires measured or structurally evident benefit.

## Browser state and cache identity

TanStack Router search parameters are the canonical list state:

```text
/admin/invitations?status=PENDING&email=alice&sort=CREATED_AT_DESC&page=0&size=20
```

Zod 4 parses and defaults the untrusted search object into typed canonical state. Applying or clearing a filter, changing sort, or changing page size resets `page` to `0`. Page navigation retains every other list parameter. Refresh, back/forward navigation, and copied URLs reconstruct the same view.

The generated list query receives the complete search state. Its TanStack Query key therefore contains status, email, sort, page, and size. Page transitions retain prior rows through `keepPreviousData` while exposing background fetching without replacing the table with the initial loading surface.

Create, rotate, and revoke invalidate the generated invitation-list base key. The client does not optimistically move rows across filters or recompute totals because server expiry, lifecycle conflicts, ordering, and page membership are authoritative.

## Product surface

The page separates:

- filter controls with explicit Apply and Clear actions;
- a semantic invitation table;
- per-row rotate/revoke actions and pending/error state;
- result range and page controls;
- initial loading, background fetching, first-invitation empty, no-filter-match, and query failure states.

TanStack Table v9 runs in controlled manual pagination/sorting mode over the server page; it owns row/column interaction models but never fetches, filters, sorts, or slices server data. React renders semantic `table`, `thead`, `tbody`, scoped column headers, `aria-sort`, contextual row-action labels, `time` elements, and a labelled pagination `nav`. A narrow viewport uses a horizontal table container without duplicating the resource into an unrelated card contract.

## Naming convention

Frontend names must describe the owned resource or application state, not a vague visual grouping:

- `Invitation*` for invitation lifecycle administration;
- `OrganizationMember*` for future membership views;
- `ApplicationSession*` for MemoryOS session state;
- `Identity*` for external/internal identity concepts;
- `User*` only for identity-provider or platform-account administration;
- `People` only if a future aggregate surface genuinely combines multiple human-resource types.

The increment audits touched navigation, routes, components, tests, and generated references for mismatches. Unrelated ambiguous names are reported and corrected when the final domain name is already known; speculative renames are excluded.

## Explicit exclusions

- bulk selection or bulk lifecycle actions;
- member-directory implementation;
- multi-Organization or Workspace switching;
- generic `DataTable`, resource registry, router provider, or data provider;
- saved views, column resize/reorder, export, and infinite scroll;
- audit events or realtime subscriptions;
- frontend authorization expansion beyond the current owner-protected API contract.
