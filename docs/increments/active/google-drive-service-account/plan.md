# Plan — Google Drive service-account credentials with domain-wide delegation

Design: [design.md](design.md). Delivered in the design's order; each step keeps `clean check` green.

## 1. Service-account credential (primary admin as the acting user)

- [ ] Migration: `GOOGLE_SERVICE_ACCOUNT` credential kind; `google_drive_credentials.auth_method` (`OAUTH`/`SERVICE_ACCOUNT`) with dedicated encrypted service-account key columns and a per-method check constraint.
- [ ] `GoogleDriveServiceAccountKey`: parse the downloaded JSON key (at most 16 KiB, `type=service_account`, client email/ID, PKCS#8 RSA private key, fixed Google token URI, no duplicate or trailing JSON); redacted `toString`; zeroed on close.
- [ ] `GoogleDriveProvider.Credential` becomes sealed (`OAuth`, `ServiceAccount(key, subject)`); `RestGoogleDriveProvider.open` mints an RS256 JWT bearer assertion with `sub` for service accounts and keeps the refresh-token path for OAuth.
- [ ] Repository and service: create/list/revoke/delete for service-account credentials with the existing Tenant, owner, revision and Source-invalidation rules; `openCredential` builds the matching provider credential.
- [ ] Creation validates by opening a session as the primary admin and reading the admin's My Drive root and Directory user record; failures are typed (`invalid key`, `delegation not granted`, `admin not found`).
- [ ] API `POST /api/credentials/google-drive/service-account`; credential responses expose `authMethod`; OpenAPI and generated client.
- [ ] Browser: the Drive credential setup offers OAuth or service account (JSON key upload + primary admin email) with shadcn primitives; list and detail show the method.

## 2. Google Group membership for Auto Sync

- [ ] Migration: `google_group_sync_runs`, `google_group_sync_groups`, `google_group_members` keyed by credential and generation; active generation on the credential.
- [ ] Provider: `listGroups(domain, pageToken)` and `listMembers(group, pageToken)` with `includeDerivedMembership=true`, existing budgets and failure mapping.
- [ ] Group sync advancer: one page per Drive sync step under the credential lock; start when due, promote on completion, keep the prior generation on failure, typed failure evidence.
- [ ] Auto Sync tokens: `google_group:` grants in `SYNC_GRANTS` and reader tokens from active generations; ADR 0013 supersedes the group consequence of ADR 0011.
- [ ] Credential revoke/reauthorize/delete removes generations.

## 3. Whole-domain traversal

- [ ] Admin SDK `users.list` over the primary-admin domain, admins first, bounded and paginated.
- [ ] Per-user impersonated traversal within SOURCE_SYNC covering My Drive and Shared Drives, with a persisted per-user stage map.
- [ ] Per-user failure isolation: 401 validation probe marks the user done; impersonation failure checks Workspace removal, then records typed evidence.
- [ ] ACL observations record the impersonated subject.

## Verification

- [ ] HTTP fixtures: JWT bearer exchange with `sub`, Directory group/member pagination, derived membership, user pagination, per-user traversal.
- [ ] PostgreSQL: credential encryption and lifecycle, group generations (promotion, retention on failure, removal on revoke), Auto Sync group grants in index-time access and the post-query recheck.
- [ ] Browser: service-account credential setup and error states.
- [ ] `gradlew clean check` and `pnpm check`; live Workspace evidence recorded separately.
