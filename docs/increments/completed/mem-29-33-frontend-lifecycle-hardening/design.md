# MEM-29–33 design: frontend lifecycle hardening

## Outcome

MemoryOS keeps one browser document and one accepted authenticated-session boundary across internal route transitions. TanStack Router owns internal navigation and persistent authenticated/admin layouts; TanStack Query converges cached private state with the current browser session; invitation creation is single-flight; and Vite emits CSP-compatible font files.

This increment fixes five evidence-backed frontend defects in one pull request because their runtime contracts overlap. MEM-29 enables client navigation, MEM-31 preserves shared layouts after that cutover, and MEM-30 makes the persistent layout the owner of session/query isolation. MEM-32 and MEM-33 are bounded production correctness fixes with independent verification.

## Router and layout ownership

All application-owned destinations use TanStack Router `Link` semantics. Native anchors remain only where the browser must leave the SPA or perform a native action: OAuth2 authorization, provider logout, invitation continuation, `mailto:`, and the in-document skip link.

A pathless `_authenticated` layout owns `ApplicationSessionBoundary` and its application-session provider. The root application page remains its index child. A nested `admin` layout performs the `INVITATIONS_MANAGE` gate before any administration child mounts and owns one persistent admin `AppShell` around an `Outlet`. Sources and Invitations become content-only child pages. Existing public URLs remain `/`, `/admin`, and `/admin/invitations`.

The separate app and administration shells remain intentional. Moving from the app to administration may replace the shell, while moving between administration pages preserves desktop collapse state and mobile dialog lifecycle. Mobile navigation is controlled and closes explicitly after a successful internal navigation.

## Authenticated query lifecycle

`GET /api/identity/me` remains the canonical browser-session projection. Its query refetches whenever the browser returns to the foreground, regardless of freshness, because cookie authority can change outside the current tab.

The accepted session fingerprint belongs to the `QueryClient`, not a route component. It contains the actor id plus the Organization role and sorted capability projection. Before accepting a different fingerprint, the client removes every non-identity query and all mutations. This covers actor replacement and same-actor authority loss without discarding the identity query that drives the boundary.

A global QueryCache/MutationCache unauthenticated handler owns convergence from private endpoint failures. An identity `401` forgets the accepted fingerprint and purges private queries/mutations while preserving the identity error that renders signed-out state. A `401` from another query or mutation performs the same purge and resets the active identity query so the persistent boundary resolves the authoritative signed-out state. Other errors retain their existing local behavior.

Projected capabilities remain presentation gates only. Backend authorization remains authoritative for every invitation request.

## Invitation submission

The create-invitation dialog uses one semantic form and one submit path. A synchronous ref guard acquires the single-flight lock before starting the mutation, because React pending state cannot prevent two events delivered before the next commit. The lock releases in `finally`; pending state still disables visible controls. Two Enter/click submissions in the same render can issue at most one POST, and a successful one-time secret cannot be overwritten by a duplicate conflict response.

## Production assets

Vite emits every imported asset as a file by setting `build.assetsInlineLimit` to zero. This preserves the production `font-src 'self'` policy instead of weakening CSP to permit `data:`. The build gate scans emitted CSS for `data:font` and requires at least one emitted WOFF2 asset.

## Verification boundaries

Focused unit coverage proves QueryClient-scoped fingerprint persistence, actor/authority change purge, identity and non-identity `401` convergence, and invitation single-flight submission. Playwright proves same-document internal navigation, stable identity request count, persistent admin shell state, mobile drawer closure, deep-link gating, and duplicate-submit serialization. The production build proves CSP-compatible font output, followed by a real browser smoke of the changed routes.

## Explicit exclusions

- Backend identity, invitation, authorization, or OpenAPI contract changes.
- Cross-tab messaging beyond foreground identity synchronization.
- Persisting sidebar state across full document reloads or browser restarts.
- A generic route registry, design-system package, or alternate frontend state manager.
- Weakening CSP to allow `data:` fonts.
