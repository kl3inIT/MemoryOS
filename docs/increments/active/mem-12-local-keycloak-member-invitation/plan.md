# MEM-12 implementation plan: production member invitation

## Contract and persistence

- [ ] Reconcile the identity and organization capability specs with the approved invitation lifecycle and failure outcomes.
- [ ] Add the invitation verification matrix before implementation so expiry, rotation, revocation, conflict, and concurrency behavior remain explicit.
- [ ] Add a Flyway migration for invitation lifecycle facts, digest uniqueness, foreign keys, and one pending invitation per normalized email and Organization.
- [ ] Implement Organization-owned invitation types and JDBC persistence without introducing a second membership or tenant-mapping model.

## Owner lifecycle

- [ ] Add active-`OWNER` authorization for create, list, revoke, and rotate operations.
- [ ] Generate 256-bit URL-safe secrets, persist SHA-256 digests only, and return plaintext only from create or rotate.
- [ ] Make revoke and rotate conditional, race-safe state transitions with explicit unavailable/conflict results.
- [ ] Expose production API contracts through the checked-in OpenAPI document and regenerate the TypeScript client.
- [ ] Define a multi-replica rate-limit path through existing production infrastructure; do not ship an in-memory-only limit.

## Recipient intake and authentication

- [ ] Add invitation intake with digest lookup, expiry/revocation/consumption checks, no-store/no-referrer headers, and redacted JDBC session continuation.
- [ ] Extend the browser OAuth2 success path so an unbound identity is provisioned only from a valid invitation continuation.
- [ ] Require exact issuer/subject, verified matching email, matching nonce, and locked pending invitation before acceptance.
- [ ] Commit Actor binding, Organization `MEMBER`, default-Workspace `MEMBER`, and invitation acceptance in one transaction.
- [ ] Rotate the session ID, persist the `ActorId`-only principal, discard provider state, and invalidate every failed partial session.
- [ ] Reconcile Keycloak realm desired state and deployment prerequisites for the recipient sign-in/account-creation experience without giving MemoryOS administrator credentials.

## Product experience

- [ ] Add `People` administration navigation and a production invitations page using the existing app-shell and semantic token system.
- [ ] Implement one-email invitation dialog, validation, submit progress, and plain-language error handling with Onyx/Opal interaction quality.
- [ ] Show pending, accepted, expired, and revoked invitations without exposing digests or secrets.
- [ ] Provide copy/share for a newly created or rotated link, plus revoke and rotate recovery actions.
- [ ] Add the recipient invitation landing and failure surfaces, then return successful recipients directly to `New Session`.
- [ ] Keep email delivery absent unless a concrete provider and observable production failure contract are implemented; copy/share must remain complete.

## Verification and delivery

- [ ] Inspect every changed Java, Kotlin DSL, YAML, properties, and XML file with JetBrains warnings enabled, then compile affected modules.
- [ ] Test owner authorization, normalization, digest-only storage, expiry, replay, revoke, rotate, identity conflicts, membership conflicts, and concurrent acceptance at the narrowest useful boundary.
- [ ] Exercise the real Spring browser chain with Authorization Code + S256 PKCE, JDBC session continuation, verified-email match/mismatch, and provider-state absence.
- [ ] Exercise owner and recipient flows in Chromium at desktop and mobile widths, including recovery states and reload behavior.
- [ ] Run `gradlew.bat clean check --no-daemon`, `pnpm check`, and the browser contract suite.
- [ ] Deploy the exact reviewed head, execute the complete owner-to-recipient flow against shared Keycloak and PostgreSQL, and record secret-safe database/session evidence.
- [ ] Complete the guarded PR loop, exact merge-SHA CI, Linear evidence, increment closure, and checkout cleanup.
