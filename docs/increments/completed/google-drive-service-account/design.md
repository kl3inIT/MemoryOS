# MEM-90 — Google Drive service-account credentials with domain-wide delegation

## Approved boundary

Add a service-account credential type for Google Drive Sources so enterprise deployments index a whole Google Workspace domain without per-user OAuth consent. The service account always uses domain-wide delegation: a Workspace admin grants the SA client ID Drive + Admin SDK scopes in Admin console, and the connector impersonates domain users to cover My Drive and Shared Drives. A plain service account reading only items shared to its email was considered and rejected — neither Onyx nor Glean offers it, because coverage then depends on every owner remembering to share, My Drive is unreachable, and Admin SDK user/group enumeration is impossible without DwD.

Decisions recorded 2026-09-13:

- Source selection stays **editable**. The Onyx-style immutable-connector model was considered and rejected: the `scope_revision` fence (membership reset, ACL invalidation, index eligibility) is already implemented and verified, and immutability would not reduce re-index cost — users would still delete and recreate the Source, additionally losing document identity and run history.
- Service account is **additive** to the existing `GOOGLE_OAUTH` credential type; per-user OAuth remains supported for individual/small-team use.
- A future optimization may reuse byte-identical content across scope changes by updating `scope_revision` instead of re-acquiring; it is independent of this increment and not committed here.

## Reference models

- **Onyx** (local checkout `20f4427`, 2026-09-01): the service-account credential is `google_service_account_key` + `google_primary_admin` and always implies domain-wide delegation. `ServiceAccountCredentials.from_service_account_info` is built with `drive.readonly`, `drive.metadata.readonly`, `admin.directory.user.readonly` and `admin.directory.group.readonly` scopes (`google_utils/shared_constants.py`). Every service builder impersonates via `creds.with_subject(user_email)` (`google_utils/resources.py:80-90`); `useDomainAdminAccess` is set for service-account calls. User enumeration pages `admin.users().list` over the domain derived from the primary admin email, admins first (`connector.py:471-500`). Retrieval runs `MAX_DRIVE_WORKERS` threads each impersonating one user with a per-user checkpoint stage map; a 401 at the validation gate marks that user DONE without blocking others, and a mid-run `RefreshError` checks workspace removal before recording `ImpersonationError` (`connector.py:1108-1300`). Setup requires enabling Drive/Admin SDK/Docs/Sheets APIs, an SA key (orgs created after 2024-04 need the `iam.disableServiceAccountKeyCreation` policy override), DwD scope grant in Admin console, and a primary admin email holding Users/Groups/OU read privileges.
- **Glean** is service-account-only with domain-wide delegation and indexes the whole Workspace domain, then filters.

## Runtime design

- New credential kind alongside `GOOGLE_OAUTH`: the uploaded Google Cloud service-account JSON key plus a primary admin email are validated (client email, private key, token URI; admin email is a real domain user, not the SA email) and encrypted at rest with the same Tenant/credential/key-version binding as OAuth grants. No OAuth consent flow, no refresh token; access tokens are minted on demand via the JWT bearer grant with `subject` set to the impersonated user and remain transient.
- User enumeration pages the Admin SDK `users.list` over the domain derived from the primary admin email, admins first, bounded and paginated. The enumerated set is the traversal frontier for My Drive coverage; Shared Drive discovery follows the existing root/membership model.
- Traversal impersonates each enumerated user in turn (`subject = user_email`) within the existing SOURCE_SYNC run. Per-user progress must survive run boundaries: a per-user completion stage map is persisted so a failed or timed-out run resumes instead of restarting the domain crawl. A user without Drive API access (401 at the validation probe) or removed from the Workspace mid-run is marked done and does not block other users; the failure is recorded as typed evidence, not a silent skip.
- The existing selection model, membership table, `scope_revision` fencing, ACL observation (MEM-88) and run history are reused. `GoogleDriveProvider.Session` gains an impersonating token source; `permissions.list`, `files.list/get` and `supportsAllDrives` behavior is identical. ACL observations record which impersonated subject produced them.
- Selection semantics for a service-account Source: roots may be Shared Drive IDs or folder/file IDs reachable by impersonated users. Whole-domain My Drive coverage is the default frontier; explicit roots narrow it.
- Authorization: service-account credential creation and Source creation keep the existing global `SOURCES_MANAGE` authority. No new Actor-facing role is introduced in this increment.
- Credential lifecycle mirrors OAuth credentials: named, Tenant-owned, reusable across Sources, revision-fenced, revocable. Revocation invalidates dependent Sources the same way OAuth revocation does.

## Google Group membership for Auto Sync

MEM-105 blocks Google Group grants because membership was not synchronized ([ADR 0011](../../../decisions/0011-verified-email-source-permission-matching.md)). A domain-wide-delegated service account can read the Directory, so this increment expands them, following Onyx `ee/onyx/external_permissions/google_drive/group_sync.py` (checkout `ec08b5f948`): `groups.list` over the primary-admin domain, then `members.list` per group, both as the primary admin.

- Membership is a Workspace fact owned by the service-account credential, not by a Source. It is stored per credential as a generation: a run lists groups page by page and then each group's members page by page, and only a completed run becomes the active generation. A failed or interrupted run keeps the previous active generation, matching the MEM-105 rule that the last successful observation stays in force.
- `members.list` uses `includeDerivedMembership=true`, so a member of a nested group is a member of the outer group. Onyx reads direct members only; a file shared with an outer group would otherwise stay hidden from nested-group members.
- Group sync advances inside Drive SOURCE_SYNC steps of Sources whose credential is a service account, one bounded page per step under the credential's group-sync lock, so a large domain never exceeds a step budget and concurrent Sources on one credential never duplicate work. It restarts when the active generation is older than `memoryos.google-drive.group-sync-interval` (default one hour).
- Auto Sync grant tokens gain `google_group:<lower-case group email>` for `type=group` permissions. Reader tokens gain `google_group:<group email>` for every group of an active service-account credential in the Tenant whose active generation lists the reader's verified login email. Reader tokens stay per request, so membership changes never require an index write. OAuth credentials sync no groups; their group grants still match nothing.
- Revoking, reauthorizing or deleting the credential removes its generations, so its memberships stop granting access immediately.

## Delivery order

1. Service-account credential with the primary admin as the acting user: create, validate, list, revoke, delete, and every existing Drive path (selection tree, General and Specific scope, sync, ACL observation) through an impersonating token source. This makes a service account a drop-in replacement for OAuth without consent.
2. Google Group membership for Auto Sync, as above.
3. Whole-domain traversal: user enumeration and the per-user stage map.

Each step is independently shippable; steps 1–2 close the Google Group gap left by MEM-105.

## Setup contract (documented for operators)

Enable Drive, Admin SDK, Docs and Sheets APIs in the Google Cloud project; create the SA and download a JSON key (orgs created after 2024-04 may need the `iam.disableServiceAccountKeyCreation` org-policy override); grant the SA client ID domain-wide delegation with `drive.readonly`, `documents.readonly`, `spreadsheets.readonly`, `admin.directory.user.readonly` and `admin.directory.group.readonly` scopes (Docs and Sheets are read through their own APIs by the native extractors, unlike Onyx); supply a primary admin email holding Users/Groups/OU read privileges. Multiple credentials allow multiple Workspaces per deployment.

## Verification

- Controlled Google-compatible HTTP fixtures for JWT bearer token exchange with `subject`, Admin SDK user pagination, per-user My Drive traversal, Shared Drive listing and `permissions.list` under impersonated tokens.
- Fixture coverage for per-user failure isolation: 401 at validation probe, mid-run impersonation failure, removed user, and resume from the persisted per-user stage map.
- PostgreSQL tests for the new credential kind: encryption binding, revision fencing, reuse across Sources, revocation invalidation, and per-user checkpoint persistence.
- Repository gate plus exercising the real sync path against fixtures. Live verification requires a Google Workspace with admin access for DwD grant and a test SA; record separately from controlled evidence. Development is blocked on that Workspace access for live evidence only — controlled fixtures carry the automated gate.
