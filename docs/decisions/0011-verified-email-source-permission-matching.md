# 0011 — Verified login email matches provider Source permissions

Status: Accepted, implementation started 2026-09-15.

## Context

Auto Sync Google Drive Sources ([MEM-105](../increments/active/mem-105-source-access-modes/design.md)) enforce the per-file permissions collected by MEM-88. Google grants name people by email address or domain, so enforcement must know which Google identities a MemoryOS reader holds. The [identity contract](../specs/identity.md) resolves readers only by exact `(issuer, subject)` and treats email as a profile observation that never links, admits or provisions an identity. MemoryOS has no reader-side Google linking or Directory group sync, and the connected Drive account is an ingestion credential, not a reader.

## Decision

Match provider Source permissions against the reader's verified login email: `actor_profiles.email` with `email_verified = true`, as last observed by browser admission, compared in lower case. It yields `google_user:<email>` and `google_domain:<domain of that email>` tokens, resolved per request and only while the Actor's Tenant membership is active.

The email remains a matching input only. It never binds an external identity, admits a member, JIT-provisions an Actor, grants a capability or authorizes Source management. Exact `(issuer, subject)` resolution and every existing admission rule are unchanged.

## Consequences

- Readers without a verified email, including brokered-IdP users whose provider does not assert verification, see no Auto Sync documents until it is verified. Public and Private Sources are unaffected.
- There are no prior-email aliases. A changed login email changes the matched Google grants on the next request, without index writes.
- Domain grants are approximated by the email domain, because Workspace domain membership is not synchronized. Google Group grants match nothing until Group membership is expanded (MEM-90).
- Auto Sync is only as strong as the identity provider's email verification: a provider that asserts `email_verified` for an address its user does not control lets that user read Auto Sync documents shared with the address.
