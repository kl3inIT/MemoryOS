# MEM-19 implementation plan: frontend authority projection

## Scope and contracts

- [x] Replace the Linear issue body with the reviewer-corrected `/api/identity/me` contract, projection/enforcement split, exclusions, and observable acceptance criteria.
- [x] Record Organization ownership, initial capability vocabulary, deep-link outcome, query suppression, cache isolation, and clean-cutover decisions before implementation.
- [x] Add MEM-19 to the repository active-increment map and roadmap.
- [x] Update architecture, identity/invitation specs, and verification matrices in the same change.

## Backend projection

- [x] Add an Organization-owned public authority summary and resolver port for one `ActorId`.
- [x] Implement active Organization role projection with the existing ambiguity guard.
- [x] Define the stable initial capability enum with only `INVITATIONS_MANAGE`.
- [x] Extend `CurrentIdentityResponse` and `IdentityController` for owner/member/no-membership and browser/bearer parity.
- [x] Remove the unreachable `ApplicationSessionController` and its transport records.
- [x] Preserve per-request durable invitation authorization; projected capabilities never enter core commands or JDBC session state.
- [x] Regenerate `openapi.yml` and the Hey API client without adding a parallel handwritten contract.

## Frontend authority

- [x] Provide the current projection through application-session context and a fail-closed `useCan` hook.
- [x] Derive account initials and owner/member presentation from server Organization context; add no user-profile fields.
- [x] Gate the app-area Admin Panel entry and administration navigation from real admin capabilities.
- [x] Gate `/admin` and `/admin/invitations` with one access-denied state that preserves the URL.
- [x] Ensure `OrganizationInvitationsPage` never mounts and no invitation request fires for a non-capable actor.
- [x] Keep owner invitation behavior unchanged and remove role-name comparisons used for behavior gating.
- [x] Reset cached query state before rendering when a refetch changes `actorId`.
- [x] Replace remaining owner-only shared copy with actor-neutral language.

## Verification and delivery

- [x] Test projection resolution for owner, member, inactive/no-membership, and ambiguous membership states.
- [x] Test browser and bearer `/api/identity/me` projection parity plus unchanged anonymous `401`.
- [x] Add real member-session HTTP `403 INVITATION_NOT_OWNER` coverage for list/create/rotate/revoke.
- [x] Test `useCan`, shell/account visibility, deep-link denial, zero invitation requests, owner regression, and actor-change cache reset.
- [x] Inspect every changed Java file with JetBrains warnings enabled, then run focused compilation/tests.
- [x] Run `gradlew.bat clean check --no-daemon`, `pnpm check`, and the full Playwright suite.
- [x] Exercise owner/member browser surfaces at desktop and narrow widths and record evidence.
- [x] Keep the increment active until guarded PR merge, exact main CI, staging smoke, Linear closure, and move to `completed/`.
