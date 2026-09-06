# MEM-55 — Users directory and membership lifecycle

## Authority and scope

The user authorized MEM-55 implementation after the planning/Onyx audit and then approved combining MEM-36 on the same `kl3inIT/mem-55-users-management` branch and IntelliJ checkout. The preserved Users work was restored after fast-forwarding to main, with its profile migration moved after main V10–V12. The [combined IAM design](../mem-36-iam-jpa/design.md) and [ADR 0007](../../../decisions/0007-unified-jpa-iam-and-group-authorization.md) define the accepted extension; neither issue implies deployment or merge.

Deliver `/admin/users`, a global-`USERS_MANAGE` directory with invitations and activate/deactivate, integrated with real Groups and persisted `STANDARD` Account Type. Preserve invitation credential/provisioning behavior and add the `IAM_ADMIN` ordinary-membership editor. MEM-36 supplies the Groups UI and scoped FILE Source authority in the same vertical flow. No JIT, unsupported account creation, user-detail route, hard deletion, role editing, SCIM or Requests controls are added.

## Existing structure and decision

Identity, Tenant membership, invitations, Users and Groups now belong to one closed `iam` capability. JPA owns lifecycle entities and writes; public roots expose identifiers/operations rather than entities. Concrete bounded Users/invitation/authorization projections and locks remain JDBC, and Connector retains Source persistence and associations.

The Users query stays a dedicated read projection within IAM, independent of invitation-history listing. It reads membership/profile/Group state plus eligible pending invitations without becoming a generic User framework or duplicating lifecycle writers. Profile recording, membership commands and invitation acceptance share IAM's transaction and authority protocols.

The query requires fresh global `USERS_MANAGE` and runs counts/page selection in a consistent transaction. It uses UNION ALL for membership rows and eligible pending/unexpired invitations. Verified-profile email evidence may suppress a pending presentation row but never merges Actors or changes bindings. Actors without an observed profile remain visible. Global counts describe the complete current Tenant directory; filtered totals describe search/status/role/Group selection. Stable row kind/UUID tie-breakers make paging deterministic.

## API contract

- `GET /api/users`, operation `listUsers`: optional `search`, `status` (`ACTIVE`, `INACTIVE`, `INVITED`), `role` (`OWNER`, `MEMBER`), `groupId`, allowlisted name/email/status/role sort, zero-based `page` default 0 and `size` default 20, maximum 100.
- Page response: `items`, `page`, `size`, `totalItems`, `totalPages`, and global `counts` (`active`, `inactive`, `invited`).
- Entries expose nullable Actor/invitation identifiers, profile fields, role, account type, status, invitation expiry and ordered Group summaries. Exactly one Actor/invitation identifier is present.
- Membership rows expose real Group edges and `STANDARD`; invitation rows expose no admitted Actor/account classification or Group membership. Directory responses contain no secrets.
- `POST /api/users/{actorId}/groups` requires `IAM_ADMIN` and replaces ordinary edges while preserving system memberships and retained manager flags.
- `POST /api/users/{actorId}/activate`, operation `activateUser`, and `/deactivate`, operation `deactivateUser`: 204 on an authorized idempotent MEMBER transition. Reject owner target, unknown/out-of-scope target and unauthorized actor through typed safe Problem Details.
- Existing create/rotate/revoke Invitation commands remain. Their generated clients are reused. The old invitation-history browser route is removed without a redirect alias; public invitation landing/activation routes remain.
- `/api/identity/me` exposes fresh global/scoped capability sets and `authorizationVersion`, not owner-derived permissions or session-stored authority. Invitation and membership administration require global `USERS_MANAGE`; revision-only changes invalidate mounted private data.

## Profile and access lifecycle

IAM exposes `ActorProfileRecorder.record(ActorId, ExternalIdentity, String displayName, String email, boolean emailVerified)`. The JPA recorder updates only the admitted Actor's exact binding and records observation time/provenance before saving the Actor-only session. It creates neither Actor nor membership and stores no raw provider token.

`TenantMemberManagement.activate(ActorId administrator, ActorId target)` and `deactivate(...)` require current global `USERS_MANAGE` under the exclusive Tenant authorization lock. Existing non-owner membership transitions preserve identity and Group history and protect the final active `STANDARD` administrator. Any existing membership, including inactive history, blocks invitation admission from creating another membership.

Every protected API request uses current membership; `/api/identity/me` retains the existing null-Tenant projection for bound actors without authority so the frontend can converge to access-not-provisioned. Public invitation-continuation lookup remains public and no-store. Session and bearer clients cannot continue protected requests after deactivation. Do not broaden public endpoints to make smoke tests pass.

Real Keycloak verification exposed that an undeclared `memoryos.provisioned` attribute is discarded under the default user-profile policy. Realm reconciliation must declare this optional, single-valued `true` marker with admin-only view/edit permissions while preserving other profile configuration. New MemoryOS-created unverified recipients can then be recognized on revoke/re-invite; existing accounts lacking provenance remain fail-closed and are never automatically relabeled. The Users UI must distinguish identity-account conflicts from pending-invitation conflicts.

## Frontend direction

Match the existing monochrome semantic tokens and Hanken Grotesk typography. Learn Onyx's centered table-first layout, compact summary filters, clear name/email hierarchy and status-aware overflow menu, not its client-side all-user loading or enterprise placeholders. Keep search/filter/page state in TanStack Router and requests in generated TanStack Query clients.

Use the local Onyx Users page and English message catalog as the naming reference. Keep one `Users` heading and `Invite member`, `Search users…` and `No users found`, without a duplicate directory heading. Membership Role remains distinct from the implemented Actor Account Type. Show real Group tags/overflow and guarded editing; do not copy unsupported Requests or bulk-invite controls. Counts remain in summary filters and pagination, with accessible background refresh announcements.

Separate feature orchestration, query/search model, table columns, filters, summary and invitation modal. Shared UI primitives should represent real reusable interaction contracts, not a generalized admin-table framework. Reuse existing confirmation/buttons/menu/session shell. Preserve keyboard/focus, responsive horizontal table scrolling, loading skeletons, retry states and mutation feedback. Invitation submission is synchronously single-flight; successful secret receipt is not turned into a failed invite if list refresh fails.

Create and rotate share a synchronous single-flight issuance boundary because only one one-time result can be presented. Secret-bearing responses transfer directly into dialog state, never mutation-cache results, and clear on close. Rotation restores its initiating row when connected; transitions that can remove a filtered/sorted row use the stable page fallback. Placeholder totals never rewrite a restored page URL. Inactive canonical identity is invalidated after private `403`, and actor changes remount private local state as well as purging caches.

## Verification

The security review adds regression coverage for inflated provider claims, bearer requests without a session, bearer requests alongside another Actor's browser session, invalid-bearer rejection without cookie fallback, and reuse of the same token after membership deactivation. These defend the existing authentication/authorization contract; provider revocation, absolute session lifetime and enterprise broker/JIT remain follow-up scope.

Core behavior tests defend profile provenance, same-email distinct Actors, pending expiry/history exclusion, stable bounded paging, owner protection, idempotent member transitions and inactive-member precedence. PostgreSQL verifies production query and transaction behavior. API tests exercise unauthorized direct calls, live authority changes, OIDC profile observation and existing invitation flows. Update only meaningful existing tests broken by the contract.

After parallel implementation, perform per-file JetBrains inspection with warnings, affected compile, Gradle `clean check`, frontend `pnpm check`, focused E2E and actual browser/API/Keycloak runtime smoke. Record observed boundaries rather than asserting mocks prove Keycloak delivery. Update canonical specs/matrices after successful verification; keep this increment active until merge.
