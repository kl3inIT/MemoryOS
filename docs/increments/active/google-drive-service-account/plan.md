# Plan — Google Drive service-account credentials with domain-wide delegation

## Credential and token source

- [ ] New credential kind: SA JSON key + primary admin email; validation (client email, private key, token URI, admin email is a domain user); encrypted at rest with Tenant/credential/key-version binding
- [ ] JWT bearer token minting with `subject` impersonation inside `GoogleDriveProvider.Session`; transient tokens, existing request/time/byte budgets
- [ ] Credential lifecycle: named, Tenant-owned, reusable across Sources, revision-fenced, revocable; revocation invalidates dependent Sources

## Domain traversal

- [ ] Admin SDK `users.list` enumeration over the primary-admin domain, admins first, bounded and paginated
- [ ] Per-user impersonated traversal inside SOURCE_SYNC covering My Drive + Shared Drives; `useDomainAdminAccess` where required
- [ ] Persisted per-user completion stage map so failed/timed-out runs resume instead of restarting the domain crawl
- [ ] Per-user failure isolation: 401 validation probe → user done; mid-run impersonation failure → workspace-removal check then typed evidence; other users unaffected
- [ ] ACL observations record the impersonated subject; selection/membership/`scope_revision`/run history reused unchanged

## API and browser

- [ ] Credential creation accepts SA JSON + primary admin email; setup flow distinguishes OAuth vs service-account credentials; no consent flow for SA
- [ ] Operator setup documentation (APIs, SA key, DwD grant, admin email privileges)

## Verification

- [ ] HTTP fixtures: token exchange with `subject`, user pagination, per-user traversal, Shared Drive listing, `permissions.list`
- [ ] Fixture coverage: per-user failure isolation and resume from stage map
- [ ] PostgreSQL tests: encryption, fencing, reuse, revocation, checkpoint persistence
- [ ] Repository gate + real sync path against fixtures; live Workspace evidence recorded separately (requires admin access)
