# MEM-19 design: frontend authority projection

## Outcome

MemoryOS projects the existing durable Organization invitation authority through `GET /api/identity/me`. The browser receives the current Organization presentation summary, stable membership role, and a canonical capability set. Frontend shell labels, administration navigation, deep-link gates, and invitation UI consume the projection through one `useCan` boundary.

This is not a new authorization system. Invitation endpoints continue to authorize every request from durable Organization membership. The projection improves browser presentation and suppresses requests that are known to fail; it is never trusted by the server.

## Existing defect

The browser contract previously exposed only `actorId`. `AccountMenu` hardcoded owner presentation; `AppShell` exposed `Admin Panel` and invitation navigation to every authenticated actor; `/admin` and `/admin/invitations` mounted without authority context. A member therefore saw owner UI and a generic query error after the correctly enforced invitation API returned `403`.

The live browser contract is `GET /api/identity/me` and `CurrentIdentityResponse`. The similarly named `ApplicationSessionController` returns JSON at SPA-owned routes and has no product caller; changing it would not fix the browser.

## Authority ownership

Organization owns the active tenant, membership roles, and active-status rules. It exposes a public projection port that resolves one actor's active Organization context. No active membership returns no Organization context; more than one active Organization is an invariant failure until multi-Organization support is explicitly designed.

The empty `authorization` module remains unchanged. Introducing a policy engine or a new dependency edge for one already-enforced owner rule would create a second authority model.

## Browser contract

`GET /api/identity/me` remains the one browser/bearer identity operation. Its response keeps required `actorId` and adds:

```text
organization: null | {
  displayName
  role: OWNER | MEMBER
}
capabilities: [INVITATIONS_MANAGE]
```

The initial vocabulary contains exactly one capability backed by current server enforcement: `INVITATIONS_MANAGE`. An active owner receives it; a member does not. A bound actor without active membership receives `organization: null` and an empty list. Browser-session and bearer-bound identities receive the same durable projection. Anonymous requests remain `401`.

Role exists for presentation only (`Organization owner` versus `Organization member`). Feature gating uses capabilities, never role-name comparisons.

## Frontend composition

`ApplicationSessionBoundary` remains the authentication/query boundary and provides the resolved projection through application-session context. `useCan(capability)` fails closed when the projection is absent, loading, or errored.

The capability controls:

- owner/member account label and derived initials;
- the app-area `Admin Panel` entry;
- administration navigation;
- `/admin` and `/admin/invitations` page access;
- whether `OrganizationInvitationsPage` mounts and can issue its list query;
- invitation actions and owner-only calls to action.

A non-capable deep link preserves the URL and renders one access-denied application state. The invitations page never mounts, so the browser issues zero `/api/invitations` requests. Admin-area visibility is derived from possessing any real admin-area capability; today this is exactly `INVITATIONS_MANAGE`. No speculative `ADMIN_ACCESS` capability is introduced for the Sources placeholder.

## Session isolation

A full login/logout navigation destroys the in-memory query client. The remaining cross-actor vector is an open tab whose session cookie changes in another tab. When a refetched projection reports an `actorId` different from the rendered actor, the boundary clears cached query state before rendering capability-gated UI for the replacement actor.

Capabilities are never stored in the JDBC session and never used by backend authorization.

## Clean cutover

The unreachable `ApplicationSessionController`, `ApplicationSessionResponse`, and `AccessNotProvisionedResponse` are removed. Nginx/Vite continue to serve the SPA routes. Architecture and identity contracts identify `/api/identity/me` as the canonical projection.

Owner-assumption copy in shared route/session states is replaced with actor-neutral language. No user profile name/email is added because MemoryOS does not persist provider profile claims.

## Explicit exclusions

- multi-Organization switching or tenant lists;
- user profile persistence;
- capabilities without existing backend enforcement;
- MEM-22 problem/notification taxonomy;
- MEM-27 interaction-token work;
- resource registries, policy engines, method-security migration, or session capability snapshots.
